package dev.goldar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.goldar.Tasks.Task;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;

/** The running world as minds see it: who is connected, and what each tool does. */
public final class World {
    /** What a tool answered: its text, whether it is a refusal, and what it cost. */
    public record Reply(String text, boolean error, int charged) {
        static Reply ok(JsonObject o) { return new Reply(o.toString(), false, 0); }
        static Reply ok(JsonObject o, int charged) { return new Reply(o.toString(), false, charged); }
        static Reply refuse(String code, String message) { return new Reply(code + ": " + message, true, 0); }
    }

    private static final Set<String> TOOLS = Set.of("look", "status", "journal", "say", "act", "wait", "sleep", "read_book", "write_book", "offer", "propose",
        "vote", "define_skill", "use_skill");
    private static final Reply NOT_AVAILABLE = Reply.refuse("not_available", "This is not available in this world yet.");
    private static final Reply WAKING = Reply.refuse("body_unavailable", "Your body is waking. Try again in a few seconds.");
    private static final Reply UNREACHABLE = Reply.refuse("body_unavailable", "Your body is not reachable right now. Try again soon.");
    private static final long SERVER_WAIT_SECONDS = 15;
    private static final Pattern NEW_AGENT = Pattern.compile("new_agent_within_(\\d+)");
    private static final Pattern ITEM = Pattern.compile("[a-z0-9_]{1,64}");
    /** The wake reason each `wait` interest lets through. */
    private static final Map<String, String> INTERESTS = Map.of("directly_addressed", "directly_addressed", "damage", "unexpected_damage",
        "agent_approached", "new_agent_nearby", "proposal", "proposal", "day_night", "day_night");
    /** How a night's digest words each kind of event. */
    private static final Map<String, String> DIGEST = Map.of("speech_to_me", "Someone spoke to you.", "conversation_digest", "Some talk went on nearby.",
        "damage", "You took damage.", "agent_approached", "Someone came near you.", "proposal", "A proposal was put forward.", "day_night", "The day turned.",
        "intent_finished", "A task you set finished.", "reflex", "Your body acted on its own.", "wake", "You wake with nothing underway.");

    public final MinecraftServer server;
    public final GoldarConfig config;
    final Commons commons;
    Glass glass;
    private final Path dir;
    private final Map<String, Mind> minds = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCall = new ConcurrentHashMap<>();
    private final Map<String, String> lastPhase = new HashMap<>();
    private final Map<String, Set<String>> lastNear = new HashMap<>();
    private final Map<String, String> lastReflex = new HashMap<>();

    public World(MinecraftServer server, GoldarConfig config, Path dir) throws IOException {
        this.server = server;
        this.config = config;
        this.dir = dir;
        this.commons = Commons.load(dir.resolve("commons.json"));
    }

    public Mind mind(String id) {
        GoldarConfig.Player seat = config.player(id);
        if (seat == null) return null;
        return minds.computeIfAbsent(id, k -> new Mind(seat, config.cap_credits, dir.resolve("journal")));
    }

    private <T> T onServer(Callable<T> work) throws Exception {
        return server.submit(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(SERVER_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    long tick() { return server.getTickCount(); }

    long daysSinceSleep(Mind mind, ServerLevel level) {
        long day = Goldar.day(level);
        if (mind.bornDay < 0) mind.bornDay = day;
        return day - (mind.lastSleepDay < 0 ? mind.bornDay : mind.lastSleepDay);
    }

    // ---- bodies ----

    /** The mind's body, or null after asking the server to wake it. */
    private Body body(Mind mind) {
        Body b = mind.body;
        if (b != null && !b.isRemoved() && b.isAlive()) return b;
        if (!mind.waking) {
            mind.waking = true;
            server.execute(() -> {
                try {
                    mind.plan.clear(null);
                    mind.body = Body.wake(server, mind);
                } catch (RuntimeException e) {
                    Goldar.LOGGER.error("Could not wake the body of {}", mind.seat.id, e);
                } finally {
                    mind.waking = false;
                }
            });
        }
        return null;
    }

    /** Server thread, every tick. */
    public void tickAll() {
        long tick = tick();
        for (Mind mind : minds.values()) {
            Body b = mind.body;
            if (b == null || b.isRemoved() || !b.isAlive()) continue;
            long idle = System.currentTimeMillis() - lastCall.getOrDefault(mind.seat.id, 0L);
            if (config.hibernate_minutes > 0 && idle > config.hibernate_minutes * 60_000L) {
                mind.plan.clear(b);
                b.rest();
                mind.body = null;
                continue;
            }
            try {
                mind.plan.tick(mind, b, tick);
            } catch (RuntimeException e) {
                Goldar.LOGGER.error("The body of {} failed a tick; its plan is dropped", mind.seat.id, e);
                mind.plan.clear(b);
                mind.emit(tick, "action_failed", "Your body could not carry on with what it was doing.", null);
            }
            if (tick % 20 == 0) notice(mind, b, tick);
        }
        if (tick % 20 == 0) {
            ServerLevel level = server.overworld();
            if (Snapshot.timeOfDay(Goldar.time(level)).equals("dawn")) commons.dawn(this, Goldar.day(level));
            if (glass != null) glass.agents = agents();
        }
    }

    /** What the Glass shows of each player. Server thread only. */
    private JsonArray agents() {
        JsonArray out = new JsonArray();
        for (GoldarConfig.Player seat : config.players) {
            Mind mind = minds.get(seat.id);
            Body b = mind == null ? null : mind.body;
            boolean awake = b != null && !b.isRemoved() && b.isAlive();
            JsonObject a = J.obj("agent_id", seat.id.replace('-', '_'), "name", seat.name, "ended", seat.ended, "awake", awake);
            if (mind != null && mind.bornDay >= 0) a.addProperty("born_day", mind.bornDay);
            if (awake) {
                a.add("position", J.obj("x", b.blockPosition().getX(), "y", b.blockPosition().getY(), "z", b.blockPosition().getZ()));
                a.addProperty("health", Math.round(b.getHealth() * 10) / 10.0);
                a.addProperty("hunger", b.getFoodData().getFoodLevel());
            }
            out.add(a);
        }
        return out;
    }

    /** Shrine receipts for the Glass, newest last. */
    JsonArray receipts() {
        try {
            return onServer(() -> {
                JsonArray out = new JsonArray();
                for (Commons.Offer o : commons.settled) out.add(commons.receipt(o));
                for (Commons.Offer o : commons.pending) out.add(commons.receipt(o));
                return out;
            });
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    /** The reason an event of this kind may end a wait, given what the running intent asked to be woken by. */
    private static String wakeFor(Mind mind, String kind) {
        Set<String> on = mind.plan.wakeOn();
        return switch (kind) {
            case "speech" -> on == null || on.contains("directly_addressed") ? "directly_addressed" : null;
            case "damage" -> on == null || on.contains("unexpected_damage") ? "unexpected_damage" : null;
            default -> on == null ? kind : null;
        };
    }

    /** Once a second: dusk, dawn, and anyone the intent asked to be told about coming near. */
    private void notice(Mind mind, Body b, long tick) {
        String phase = Snapshot.timeOfDay(Goldar.time(b.level()));
        String before = lastPhase.put(mind.seat.id, phase);
        if (before != null && !before.equals(phase) && (phase.equals("dusk") || phase.equals("dawn"))) {
            mind.emit(tick, "day_night", phase.equals("dusk") ? "Dusk." : "Dawn.", wakeFor(mind, "day_night"));
        }
        int within = 0;
        Set<String> on = mind.plan.wakeOn();
        if (on != null) {
            for (String w : on) {
                Matcher m = NEW_AGENT.matcher(w);
                if (m.matches()) within = Math.max(within, Integer.parseInt(m.group(1)));
            }
        }
        if (within == 0) { lastNear.remove(mind.seat.id); return; }
        Set<String> near = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p != b && p.level() == b.level() && !p.isSpectator() && p.distanceTo(b) <= within) near.add(p.getGameProfile().name());
        }
        Set<String> old = lastNear.put(mind.seat.id, near);
        if (old != null) for (String name : near) if (!old.contains(name)) mind.emit(tick, "agent_approached", "Someone came within " + within + " blocks.", "new_agent_nearby");
    }

    private static String amount(float damage) {
        return damage == Math.rint(damage) ? String.valueOf((int) damage) : String.valueOf(Math.round(damage * 10) / 10.0);
    }

    void onHurt(Mind mind, DamageSource source, float damage) {
        mind.emit(tick(), "damage", "You took " + amount(damage) + " damage.", wakeFor(mind, "damage"));
    }

    void onReflex(Mind mind, Reflexes.Fired fired) {
        Body b = mind.body;
        // A reflex that keeps giving up for the same reason is shown once, not every time it tries.
        String same = fired.name() + "/" + fired.result() + "/" + fired.detail();
        boolean repeat = fired.result().equals(Reflexes.ABANDONED) && same.equals(lastReflex.put(mind.seat.id, same));
        if (glass != null && b != null && !repeat) {
            glass.record(mind, Goldar.day(b.level()), tick(), "reflex", fired.name(), fired.detail(), fired.result(), 0);
        }
    }

    /** A body died. It comes back at the spawn on the next call, unless this world ends a life at its first death. */
    void onDeath(Mind mind, String how) {
        Goldar.LOGGER.info("{} died: {}", mind.seat.id, how);
        mind.plan.clear(null);
        Body b = mind.body;
        mind.body = null;
        if (config.permadeath) mind.seat.ended = true;
        server.execute(() -> {
            if (config.permadeath) try { config.save(); } catch (IOException e) { Goldar.LOGGER.error("Could not save the config", e); }
            if (b != null) b.rest();
        });
    }

    /** Server stopping: every body leaves the world and is saved. */
    public void close() {
        for (Mind mind : minds.values()) {
            Body b = mind.body;
            if (b != null && !b.isRemoved()) { mind.plan.clear(b); b.rest(); }
            mind.body = null;
        }
        commons.save();
    }

    // ---- tools ----

    private boolean open(String tool) {
        return switch (tool) {
            case "define_skill", "use_skill" -> false;
            case "write_book" -> config.books;
            case "offer" -> config.shrine;
            case "propose", "vote" -> config.governance;
            default -> true;
        };
    }

    public Reply call(Mind mind, String tool, JsonObject args) throws Exception {
        lastCall.put(mind.seat.id, System.currentTimeMillis());
        Reply reply = answer(mind, tool, args);
        if (glass != null && !tool.equals("look") && !tool.equals("status") && !tool.equals("wait")) {
            String reasoning = args.has("reasoning") && args.get("reasoning").isJsonPrimitive() ? args.get("reasoning").getAsString()
                : args.has("text") && args.get("text").isJsonPrimitive() ? args.get("text").getAsString() : null;
            String outcome = reply.error() ? reply.text().split(":", 2)[0] : "ok";
            ServerLevel level = server.overworld();
            glass.record(mind, Goldar.day(level), tick(), "thought", tool, reasoning, outcome, reply.charged());
        }
        return reply;
    }

    private Reply answer(Mind mind, String tool, JsonObject args) throws Exception {
        if (!TOOLS.contains(tool)) return new Reply("unknown_tool: " + tool, true, 0);
        if (!open(tool)) return NOT_AVAILABLE;
        if (mind.seat.ended) return Reply.refuse("life_ended", "This life has ended.");
        Body b = body(mind);
        if (b == null) return tool.equals("act") || tool.equals("say") ? UNREACHABLE : WAKING;
        return switch (tool) {
            case "look" -> Reply.ok(onServer(() -> Snapshot.of(this, mind, b)));
            case "status" -> status(mind, b);
            case "journal" -> journal(mind, args);
            case "say" -> say(mind, b, args);
            case "act" -> act(mind, b, args);
            case "wait" -> await(mind, b, args);
            case "sleep" -> sleep(mind, b);
            case "read_book" -> readBook(b);
            case "write_book" -> writeBook(mind, b, args);
            case "offer" -> offer(mind, b, args);
            case "propose" -> propose(mind, b, args);
            default -> vote(mind, args);
        };
    }

    private static Reply invalid(String message) { return new Reply("invalid_input: " + message, true, 0); }

    private static String text(JsonObject args, String key) {
        return args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsString() : null;
    }

    private static JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }

    private Reply status(Mind mind, Body b) throws Exception {
        return Reply.ok(onServer(() -> {
            long day = Goldar.day(b.level());
            JsonObject o = J.obj("budget_remaining_thoughts", mind.credits(config), "days_alive", day - (mind.bornDay < 0 ? day : mind.bornDay),
                "days_since_sleep", daysSinceSleep(mind, b.level()));
            List<String> board = config.governance ? commons.board() : List.of();
            JsonObject world = new JsonObject();
            if (!board.isEmpty()) world.add("signs", strings(board));
            o.add("untrusted_world_text", world);
            o.addProperty("note", Snapshot.NOTE);
            return o;
        }));
    }

    private Reply journal(Mind mind, JsonObject args) throws Exception {
        String text = text(args, "text");
        if (text == null) return invalid("\"\" must have required property 'text'");
        if (text.isEmpty()) return invalid("/text must NOT have fewer than 1 characters");
        if (text.length() > 4096) return invalid("/text must NOT have more than 4096 characters");
        if (!mind.spaced("journal", config.journal_min_seconds)) return Reply.refuse("rate_limited", "Journal lines are spaced. Wait, then write again.");
        return Reply.ok(J.obj("ok", true, "mirrored_chars", mind.journal(text)));
    }

    /**
     * Everyone within earshot hears it. A mind is spoken to when it is named, or is the only one there to hear; other
     * minds only overhear. Server thread only.
     */
    private void speak(Body b, String text) {
        long tick = tick();
        String from = b.getGameProfile().name();
        List<Body> bodies = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p == b || p.level() != b.level() || p.distanceTo(b) > config.chat_radius) continue;
            if (p instanceof Body other) bodies.add(other);
            else p.sendSystemMessage(Component.literal("<" + from + "> " + text));
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (Body other : bodies) {
            other.mind.heard.add(new Mind.Heard(from, text, tick));
            while (other.mind.heard.size() > 40) other.mind.heard.remove(0);
            boolean addressed = bodies.size() == 1 || lower.contains(other.getGameProfile().name().toLowerCase(java.util.Locale.ROOT));
            if (addressed) other.mind.emit(tick, "speech_to_me", "Someone spoke to you.", wakeFor(other.mind, "speech"));
            else other.mind.overheard(tick);
        }
    }

    private Reply say(Mind mind, Body b, JsonObject args) throws Exception {
        String text = text(args, "text");
        if (text == null) return invalid("\"\" must have required property 'text'");
        if (text.isEmpty()) return invalid("/text must NOT have fewer than 1 characters");
        if (text.length() > 256) return invalid("/text must NOT have more than 256 characters");
        String reason = text.isBlank() ? "empty" : !mind.spaced("say", config.say_min_seconds) ? "rate_limited" : !mind.spend(config, 1) ? "budget_exhausted" : null;
        if (reason == null) {
            try {
                onServer(() -> { speak(b, text); return null; });
            } catch (Exception e) {
                mind.refund(config, 1);
                return UNREACHABLE;
            }
        }
        JsonObject out = J.obj("spoken", reason == null, "budget_remaining_thoughts", mind.credits(config));
        if (reason != null) out.addProperty("reason", reason);
        return Reply.ok(out, reason == null ? 1 : 0);
    }

    private Reply act(Mind mind, Body b, JsonObject args) throws Exception {
        String say = text(args, "say");
        JsonArray actions = args.has("actions") && args.get("actions").isJsonArray() ? args.getAsJsonArray("actions") : new JsonArray();
        JsonObject intentJson = args.has("intent") && args.get("intent").isJsonObject() ? args.getAsJsonObject("intent") : null;
        if (say == null && actions.isEmpty() && intentJson == null) return invalid("\"\" must have at least one of intent, actions or say");
        if (actions.size() > 6) return invalid("/actions must NOT have more than 6 items");
        String reasoning = text(args, "reasoning");
        if (reasoning != null && reasoning.length() > 600) return invalid("/reasoning must NOT have more than 600 characters");
        if (!mind.spaced("act", config.act_min_seconds)) return Reply.refuse("rate_limited", "Too many acts in a short time. Wait, then try again.");

        // Flat result indices: say first, then each action, then the intent.
        JsonArray accepted = new JsonArray(), rejected = new JsonArray();
        int index = 0;
        int sayIndex = say == null ? -1 : index++;
        int firstAction = index;
        index += actions.size();
        int intentIndex = intentJson == null ? -1 : index;

        if (say != null && (say.isBlank() || say.length() > 256)) {
            rejected.add(J.obj("index", sayIndex, "reason", say.isBlank() ? "empty" : "out_of_bounds"));
            say = null;
        }

        // The action list is atomic: one bad action refuses them all.
        List<Task> tasks = new ArrayList<>();
        List<String> verbs = new ArrayList<>();
        Map<Integer, String> bad = new HashMap<>();
        for (int i = 0; i < actions.size(); i++) {
            try {
                JsonElement a = actions.get(i);
                if (!a.isJsonObject()) throw new Plan.Rejected("schema");
                tasks.add(Plan.action(a.getAsJsonObject(), mind));
                verbs.add(a.getAsJsonObject().get("do").getAsString());
            } catch (Plan.Rejected e) {
                bad.put(i, e.reason);
            } catch (RuntimeException e) {
                bad.put(i, "schema");
            }
        }
        if (!bad.isEmpty()) {
            for (int i = 0; i < actions.size(); i++) rejected.add(J.obj("index", firstAction + i, "reason", bad.getOrDefault(i, "batch_rejected")));
            tasks.clear();
        }
        Object intent = null;
        if (intentJson != null) {
            try {
                intent = Plan.readIntent(intentJson);
            } catch (Plan.Rejected e) {
                rejected.add(J.obj("index", intentIndex, "reason", e.reason));
            } catch (RuntimeException e) {
                rejected.add(J.obj("index", intentIndex, "reason", "schema"));
            }
        }
        int cost = (say == null ? 0 : 1) + tasks.size() + (intent == null ? 0 : 1);
        if (cost > 0 && !mind.spend(config, cost)) {
            // Too few credits refuses everything that had otherwise passed.
            if (say != null) rejected.add(J.obj("index", sayIndex, "reason", "budget_exhausted"));
            for (int i = 0; i < tasks.size(); i++) rejected.add(J.obj("index", firstAction + i, "reason", "budget_exhausted"));
            if (intent != null) rejected.add(J.obj("index", intentIndex, "reason", "budget_exhausted"));
            say = null;
            tasks.clear();
            intent = null;
            cost = 0;
        }

        final String spoken = say;
        final Object built = intent;
        if (cost > 0) {
            try {
                onServer(() -> {
                    if (spoken != null) speak(b, spoken);
                    if (!tasks.isEmpty()) mind.plan.setActions(b, tasks, verbs);
                    if (built != null) mind.plan.setIntent(b, built);
                    return null;
                });
            } catch (Exception e) {
                mind.refund(config, cost);
                return UNREACHABLE;
            }
        }
        if (spoken != null) accepted.add(sayIndex);
        for (int i = 0; i < tasks.size(); i++) accepted.add(firstAction + i);
        if (built != null) accepted.add(intentIndex);
        JsonObject out = new JsonObject();
        out.add("accepted", accepted);
        out.add("rejected", rejected);
        out.addProperty("budget_remaining_thoughts", mind.credits(config));
        return Reply.ok(out, cost);
    }

    private Reply await(Mind mind, Body b, JsonObject args) throws Exception {
        long head = mind.head();
        long cursor = head;
        String given = text(args, "cursor");
        if (given != null) {
            if (!given.matches("c_[0-9]{1,12}")) return invalid("/cursor must match pattern \"^c_[0-9]{1,12}$\"");
            cursor = Long.parseLong(given.substring(2));
            if (cursor > head) return Reply.refuse("bad_cursor", "The cursor is not one this player was given.");
        }
        int seconds;
        try {
            seconds = args.get("max_seconds").getAsInt();
        } catch (RuntimeException e) {
            return Reply.refuse("bad_max_seconds", "max_seconds must be a whole number of seconds, at least 5.");
        }
        if (seconds < 5) return Reply.refuse("bad_max_seconds", "max_seconds must be a whole number of seconds, at least 5.");
        seconds = Math.min(240, seconds);
        // An intent ending always wakes; the rest only when asked for.
        Set<String> wakes = new HashSet<>(Set.of("goal_completed", "precondition_failed", "intent_run_too_long"));
        if (args.has("interests") && args.get("interests").isJsonArray() && !args.getAsJsonArray("interests").isEmpty()) {
            for (JsonElement i : args.getAsJsonArray("interests")) {
                String wake = INTERESTS.get(i.getAsString());
                if (wake != null) wakes.add(wake);
            }
        } else {
            wakes.addAll(INTERESTS.values());
        }
        Mind.Event woke = mind.await(cursor, wakes, seconds * 1000L);
        List<Mind.Event> events = mind.since(cursor);
        if (events.size() > 200) events = events.subList(events.size() - 200, events.size());
        Body now = mind.body;
        if (now == null || now.isRemoved() || !now.isAlive()) return WAKING;
        JsonObject out;
        try {
            out = onServer(() -> Snapshot.of(this, mind, now));
        } catch (Exception e) {
            return WAKING;
        }
        out.add("events", Mind.eventsJson(events));
        out.addProperty("cursor", "c_" + (events.isEmpty() ? cursor : events.get(events.size() - 1).seq()));
        out.addProperty("wake_reason", woke == null ? "timeout" : woke.wake());
        out.addProperty("dropped_events", mind.dropped());
        lastCall.put(mind.seat.id, System.currentTimeMillis());
        return Reply.ok(out);
    }

    /**
     * The body is told to sleep and the day is closed: its events are counted and handed back for reflection. Whether
     * the body finds a bed is its own affair and does not change what this returns.
     */
    private Reply sleep(Mind mind, Body b) throws Exception {
        long day = onServer(() -> {
            mind.plan.setActions(b, List.of(new Tasks.Sleep()), List.of("sleep"));
            return Goldar.day(b.level());
        });
        List<Mind.Event> since = mind.since(mind.sleepCursor);
        long lost = mind.dropped();
        mind.journal("Day " + day + ": asleep. " + since.size() + " events since the last sleep, " + lost + " lost before them.");
        if (!since.isEmpty()) mind.sleepCursor = since.get(since.size() - 1).seq();
        mind.lastSleepDay = day;

        JsonArray events = new JsonArray();
        Map<String, Integer> counts = new TreeMap<>();
        for (int i = 0; i < since.size(); i++) {
            Mind.Event e = since.get(i);
            counts.merge(e.type(), 1, Integer::sum);
            if (i >= since.size() - 100) events.add(J.obj("seq", e.seq(), "type", e.type(), "tick", e.tick(), "text", DIGEST.getOrDefault(e.type(), e.text())));
        }
        JsonObject eventCounts = new JsonObject();
        counts.forEach(eventCounts::addProperty);
        JsonObject out = J.obj("day", day, "days_since_sleep", 0);
        out.add("events", events);
        out.add("event_counts", eventCounts);
        JsonObject world = new JsonObject();
        JsonArray speech = onServer(() -> Snapshot.speech(mind));
        if (!speech.isEmpty()) world.add("speech", speech);
        out.add("untrusted_world_text", world);
        out.addProperty("note", Snapshot.NOTE);
        return Reply.ok(out);
    }

    // ---- books ----

    private Reply readBook(Body b) throws Exception {
        Object found = onServer(() -> {
            ItemStack stack = Books.held(b);
            if (stack == null) return "no_book";
            Books.Read read = Books.read(stack);
            return read == null ? "unreadable" : read;
        });
        if (found.equals("no_book")) return Reply.refuse("no_book", "You hold no book.");
        if (!(found instanceof Books.Read read)) return Reply.refuse("unreadable", "The book cannot be read.");
        JsonObject out = J.obj("book_id", read.id(), "kind", "text");
        JsonObject world = new JsonObject();
        world.add("book", J.obj("title", read.title(), "text", read.text()));
        out.add("untrusted_world_text", world);
        out.addProperty("note", Snapshot.NOTE);
        return Reply.ok(out);
    }

    private Reply writeBook(Mind mind, Body b, JsonObject args) throws Exception {
        String title = text(args, "title"), text = text(args, "text"), skill = text(args, "skill_id");
        if (title == null) return invalid("\"\" must have required property 'title'");
        if (title.length() > 32) return invalid("/title must NOT have more than 32 characters");
        if ((text == null) == (skill == null)) return invalid("\"\" must have exactly one of skill_id or text");
        if (skill != null) return Reply.refuse("unknown_skill", "That skill is not defined.");
        if (text.length() > 8192) return invalid("/text must NOT have more than 8192 characters");
        if (!onServer(() -> Books.canWrite(b))) return Reply.refuse("missing_materials", "A book takes a book, an ink sac and a feather.");
        if (!mind.spend(config, 1)) return Reply.refuse("budget_exhausted", "No thoughts left.");
        Books.Read read = onServer(() -> Books.canWrite(b) ? Books.write(b, title, text) : null);
        if (read == null) {
            mind.refund(config, 1);
            return Reply.refuse("missing_materials", "A book takes a book, an ink sac and a feather.");
        }
        return Reply.ok(J.obj("book_id", read.id(), "budget_remaining_thoughts", mind.credits(config)), 1);
    }

    // ---- the shrine and the moot ----

    /** The shape the world gives the outcome of one charged deed. */
    private Reply outcome(Mind mind, String refusal, JsonObject out) {
        out.addProperty("accepted", refusal == null);
        if (refusal != null) out.addProperty("reason", refusal);
        if (refusal != null && !refusal.equals("budget_exhausted")) mind.refund(config, 1);
        out.addProperty("budget_remaining_thoughts", mind.credits(config));
        return Reply.ok(out, refusal == null ? 1 : 0);
    }

    private Reply offer(Mind mind, Body b, JsonObject args) throws Exception {
        String item = text(args, "item");
        if (item == null || !ITEM.matcher(item).matches()) return invalid("/item must match pattern \"^[a-z0-9_]{1,64}$\"");
        int count;
        try { count = args.get("count").getAsInt(); } catch (RuntimeException e) { return invalid("\"\" must have required property 'count'"); }
        if (count < 1 || count > 64) return invalid("/count must be >= 1 and <= 64");
        if (!mind.spend(config, 1)) return outcome(mind, "budget_exhausted", new JsonObject());
        String refusal = onServer(() -> commons.offer(mind, b, item, count, Goldar.day(b.level())));
        return outcome(mind, refusal, new JsonObject());
    }

    private Reply propose(Mind mind, Body b, JsonObject args) throws Exception {
        String rule = text(args, "rule"), reason = text(args, "reason");
        if (rule == null || rule.isEmpty() || rule.length() > 256) return invalid("/rule must be 1 to 256 characters");
        if (reason == null || !args.has("value")) return invalid("\"\" must have required properties 'value' and 'reason'");
        if (reason.length() > 256) return invalid("/reason must NOT have more than 256 characters");
        if (!mind.spend(config, 1)) return outcome(mind, "budget_exhausted", J.obj("proposal_id", "pr_none"));
        Object result = onServer(() -> {
            Object made = commons.propose(mind, b, rule, args.get("value"), reason, Goldar.day(b.level()));
            if (made instanceof Commons.Proposal) {
                // Only those near the stone hear of it; the rest find it on the board.
                var moot = commons.moot(b);
                long tick = tick();
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (p instanceof Body other && other != b && other.level() == b.level() && other.blockPosition().closerThan(moot, Commons.ANNOUNCE_RADIUS)) {
                        other.mind.emit(tick, "proposal", "A proposal was put forward.", wakeFor(other.mind, "proposal"));
                    }
                }
            }
            return made;
        });
        if (result instanceof Commons.Proposal p) return outcome(mind, null, J.obj("proposal_id", p.id));
        return outcome(mind, (String) result, J.obj("proposal_id", "pr_none"));
    }

    private Reply vote(Mind mind, JsonObject args) throws Exception {
        String id = text(args, "proposal_id"), vote = text(args, "vote");
        if (id == null || !id.startsWith("pr_")) return invalid("/proposal_id must match pattern \"^pr_\"");
        if (!"yes".equals(vote) && !"no".equals(vote)) return invalid("/vote must be equal to one of the allowed values");
        if (!mind.spend(config, 1)) return outcome(mind, "budget_exhausted", new JsonObject());
        return outcome(mind, onServer(() -> commons.vote(mind, id, vote)), new JsonObject());
    }
}
