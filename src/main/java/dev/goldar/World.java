package dev.goldar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.goldar.Tasks.Task;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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

    /** The largest result a tool may give, in bytes. */
    public static final int MAX_RESULT_BYTES = 16384;
    private static final int MAX_INVALID_INPUT_CHARS = 600;
    private static final int MAX_WAIT_EVENTS = 200, SPEECH_LINES = 40;
    private static final Set<String> TOOLS = Set.of("look", "status", "journal", "say", "act", "wait", "sleep", "read_book", "write_book", "offer", "propose",
        "vote", "define_skill", "use_skill");
    private static final Set<String> ALL_INTERESTS = Set.of("directly_addressed", "damage", "agent_approached", "proposal", "day_night");
    private static final Reply NOT_AVAILABLE = Reply.refuse("not_available", "This is not available in this world yet.");
    private static final Reply WAKING = Reply.refuse("body_unavailable", "Your body is waking. Try again in a few seconds.");
    private static final Reply UNREACHABLE = Reply.refuse("body_unavailable", "Your body is not reachable right now. Try again soon.");
    private static final long SERVER_WAIT_SECONDS = 15;
    /** A body that wakes with nothing to do tells its mind so after this long without a plan. */
    private static final long WAKE_GRACE_MS = 45_000;
    /** How a night's digest words each kind of event. */
    private static final Map<String, String[]> DIGEST = Map.of("speech", new String[] {"speech_to_me", "Someone spoke to you."},
        "damage", new String[] {"damage", "You took damage."}, "agent_approached", new String[] {"agent_approached", "Someone came near you."},
        "proposal", new String[] {"proposal", "A proposal was put forward."}, "day_night", new String[] {"day_night", "The day turned."},
        "intent_finished", new String[] {"intent_finished", "A task you set finished."}, "reflex", new String[] {"reflex", "Your body acted on its own."});

    public final MinecraftServer server;
    public final GoldarConfig config;
    final Commons commons;
    Glass glass;
    /** Each tool's input schema, by tool name. Set by the endpoint when it loads the contract. */
    volatile Map<String, JsonObject> inputSchemas = Map.of();
    private final Path dir;
    private final Map<String, Mind> minds = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCall = new ConcurrentHashMap<>();
    private final Map<String, String> lastPhase = new HashMap<>();
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
        return minds.computeIfAbsent(id, k -> new Mind(seat, config.cap_credits, dir));
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

    /** The age of the world in ticks: the clock events are stamped with. */
    long tick() { return server.overworld().getGameTime(); }

    long daysSinceSleep(Mind mind, ServerLevel level) {
        long day = Goldar.day(level);
        if (mind.bornDay < 0) mind.bornDay = day;
        return day - (mind.lastSleepDay < 0 ? mind.bornDay : mind.lastSleepDay);
    }

    /** What `geldar://player/self` holds. */
    JsonObject self(Mind mind) {
        long born = mind.bornDay < 0 ? Goldar.day(server.overworld()) : mind.bornDay;
        return J.obj("name", mind.seat.name, "seed", mind.seat.seed == null ? "" : mind.seat.seed, "born_day", born);
    }

    // ---- bodies ----

    private static boolean alive(Body b) { return b != null && !b.isRemoved() && b.isAlive(); }

    /** The mind's body, or null after asking the server to wake it. */
    private Body body(Mind mind) {
        Body b = mind.body;
        if (alive(b)) return b;
        if (mind.seat.ended) return null;
        if (!mind.waking) {
            mind.waking = true;
            server.execute(() -> {
                try {
                    if (!alive(mind.body)) {
                        mind.plan.clear(null);
                        mind.body = Body.wake(server, mind);
                        mind.wokeAtMs = System.currentTimeMillis();
                        mind.wakeTold = false;
                        if (mind.bornDay < 0) mind.bornDay = Goldar.day(server.overworld());
                    }
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
            if (!alive(b)) continue;
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
                boolean had = !mind.plan.idle();
                mind.plan.clear(b);
                if (had) mind.intentFinished(tick, "failed", null);
            }
            if (!mind.wakeTold) {
                if (!mind.plan.idle()) mind.wakeTold = true;
                else if (System.currentTimeMillis() - mind.wokeAtMs > WAKE_GRACE_MS) { mind.wakeTold = true; mind.simple(tick, "wake"); }
            }
            if (tick % 20 == 0 && config.day_night_events) notice(mind, b, tick);
        }
        if (tick % 20 == 0) {
            ServerLevel level = server.overworld();
            if (Snapshot.timeOfDay(Goldar.time(level)).equals("dawn")) commons.dawn(this, Goldar.day(level));
            if (glass != null) glass.agents = agents();
        }
        if (tick % 1200 == 0) for (Mind mind : minds.values()) mind.save();
    }

    /** What the Glass shows of each player. Server thread only. */
    private JsonArray agents() {
        JsonArray out = new JsonArray();
        for (GoldarConfig.Player seat : config.players) {
            Mind mind = minds.get(seat.id);
            Body b = mind == null ? null : mind.body;
            boolean awake = alive(b);
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

    /** Once a second: dusk and dawn. */
    private void notice(Mind mind, Body b, long tick) {
        String phase = Snapshot.timeOfDay(Goldar.time(b.level()));
        String before = lastPhase.put(mind.seat.id, phase);
        if (before != null && !before.equals(phase) && (phase.equals("dusk") || phase.equals("dawn"))) mind.dayNight(tick, phase);
    }

    /** A number as the world writes it into an event: whole when it is whole. */
    static String amount(float damage) {
        if (damage == Math.rint(damage)) return String.valueOf((long) damage);
        return Float.toString(damage);
    }

    /** The body took a blow. An intent that asked to be woken by harm ends there. */
    void onHurt(Mind mind, Body b, float damage) {
        long tick = tick();
        mind.damage(tick, amount(Math.max(0, damage)));
        if (mind.plan.interrupt(mind, b, tick, "unexpected_damage")) mind.damage(tick, "0");
    }

    /** The blow that killed: the harm it did, then the death itself, which the world words as harm of no size. */
    void onKilled(Mind mind, float damage) {
        long tick = tick();
        mind.damage(tick, amount(Math.max(0, damage)));
        mind.damage(tick, "0");
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

    /**
     * A body died. It comes back at once as a player does, without what it carried, and goes on with what it was
     * doing; unless this world ends a life at its first death.
     */
    void onDeath(Mind mind, String how) {
        Goldar.LOGGER.info("{} died: {}", mind.seat.id, how);
        Body dead = mind.body;
        if (config.permadeath) {
            mind.seat.ended = true;
            mind.plan.clear(null);
            mind.body = null;
        }
        mind.waking = true;
        server.execute(() -> {
            try {
                if (config.permadeath) try { config.save(); } catch (IOException e) { Goldar.LOGGER.error("Could not save the config", e); }
                if (dead != null) dead.rest();
                if (!config.permadeath) mind.body = Body.wake(server, mind);
            } catch (RuntimeException e) {
                Goldar.LOGGER.error("Could not bring back the body of {}", mind.seat.id, e);
                mind.body = null;
            } finally {
                mind.waking = false;
            }
        });
    }

    /** Server stopping: every body leaves the world and is saved. */
    public void close() {
        for (Mind mind : minds.values()) {
            Body b = mind.body;
            if (b != null && !b.isRemoved()) { mind.plan.clear(b); b.rest(); }
            mind.body = null;
            mind.save();
        }
        commons.save();
    }

    // ---- speech ----

    /**
     * The body's voice. The line is cleaned first; nothing is said when it is empty, when it is a command, or when
     * the body spoke too recently. Returns "said" or why not. Server thread only.
     */
    String voice(Body b, String text) {
        String line = Voice.clean(text, Voice.MAX_LINE_CHARS);
        if (line.isEmpty()) return "empty";
        if (line.startsWith("/")) return "command";
        long now = System.currentTimeMillis();
        if (now - b.mind.lastSpokeMs < config.say_min_seconds * 1000) return "rate_limited";
        b.mind.lastSpokeMs = now;
        heard(b, Compat.name(b.getGameProfile()), line, true);
        return "said";
    }

    /**
     * Everyone within earshot hears the line. A mind is spoken to when the line calls it by name; other minds only
     * overhear. Server thread only.
     */
    void heard(ServerPlayer speaker, String from, String line, boolean tellPeople) {
        long tick = tick();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p == speaker || p.level() != speaker.level() || p.distanceTo(speaker) > config.chat_radius) continue;
            if (p instanceof Body other) {
                Mind mind = other.mind;
                mind.hear(from, line, tick);
                boolean addressed = Voice.addressed(line, mind.seat.name);
                mind.speech(tick, from, line, addressed);
                if (addressed) mind.plan.interrupt(mind, other, tick, "directly_addressed");
            } else if (tellPeople) {
                p.sendSystemMessage(Component.literal("<" + from + "> " + line));
            }
        }
    }

    /** A person typed in chat: the bodies near them hear it as they hear each other. Server thread only. */
    public void onChat(ServerPlayer player, String text) {
        if (player instanceof Body) return;
        String line = Voice.clean(text, Voice.MAX_LINE_CHARS);
        if (!line.isEmpty()) heard(player, Compat.name(player.getGameProfile()), line, false);
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
        if (!reply.error() && reply.text().getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) reply = new Reply("result_too_large: " + tool, true, reply.charged());
        if (glass != null && TOOLS.contains(tool) && !tool.equals("look") && !tool.equals("status") && !tool.equals("wait")) {
            String reasoning = args.has("reasoning") && args.get("reasoning").isJsonPrimitive() ? args.get("reasoning").getAsString()
                : args.has("text") && args.get("text").isJsonPrimitive() ? args.get("text").getAsString() : null;
            String outcome = reply.error() ? reply.text().split(":", 2)[0] : "ok";
            ServerLevel level = server.overworld();
            glass.record(mind, Goldar.day(level), tick(), "thought", tool, reasoning, outcome, reply.charged());
        }
        return reply;
    }

    /** The faults in a tool's arguments, in Geldar's words: the schema's, or else the tool's either/or rule. */
    private List<String> faults(String tool, JsonObject args) {
        JsonObject schema = inputSchemas.get(tool);
        List<String> errors = schema == null ? List.of() : Schema.check(schema, args);
        if (!errors.isEmpty()) return errors;
        if (tool.equals("act")) {
            boolean hasBody = args.has("intent") || args.has("actions") || args.has("say") && args.get("say").isJsonPrimitive();
            if (!hasBody) return List.of("\"\" must have at least one of intent, actions or say");
        }
        if (tool.equals("write_book") && args.has("skill_id") == args.has("text")) return List.of("\"\" must have exactly one of skill_id or text");
        return List.of();
    }

    private Reply answer(Mind mind, String tool, JsonObject args) throws Exception {
        if (!TOOLS.contains(tool)) return new Reply("unknown_tool: " + tool, true, 0);
        List<String> faults = faults(tool, args);
        if (!faults.isEmpty()) {
            String text = "invalid_input: " + String.join("; ", faults);
            return new Reply(text.length() > MAX_INVALID_INPUT_CHARS ? text.substring(0, MAX_INVALID_INPUT_CHARS) : text, true, 0);
        }
        if (!open(tool)) return NOT_AVAILABLE;
        if (mind.seat.ended) return Reply.refuse("life_ended", "This life has ended.");
        switch (tool) {
            case "journal": return journal(mind, args);
            case "say": return say(mind, args);
            case "act": return act(mind, args);
            case "wait": return await(mind, args);
            default: break;
        }
        Body b = body(mind);
        if (b == null) return WAKING;
        return switch (tool) {
            case "look" -> look(mind, b);
            case "status" -> status(mind, b);
            case "sleep" -> sleep(mind, b);
            case "read_book" -> readBook(b);
            case "write_book" -> writeBook(mind, b, args);
            case "offer" -> offer(mind, b, args);
            case "propose" -> propose(mind, b, args);
            default -> vote(mind, args);
        };
    }

    private static String text(JsonObject args, String key) {
        return args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsString() : null;
    }

    private static JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }

    private Reply look(Mind mind, Body b) throws Exception {
        Snapshot.View view = onServer(() -> Snapshot.of(this, mind, b));
        JsonObject out = new JsonObject();
        out.add("snapshot", view.snapshot());
        out.add("untrusted_world_text", J.obj("speech", view.speech(), "signs", view.signs()));
        out.addProperty("note", Snapshot.NOTE);
        return Reply.ok(out);
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
        if (!mind.spaced("journal", config.journal_min_seconds)) return Reply.refuse("rate_limited", "Journal lines are spaced. Wait, then write again.");
        return Reply.ok(J.obj("ok", true, "mirrored_chars", mind.journal(text(args, "text"))));
    }

    private Reply say(Mind mind, JsonObject args) throws Exception {
        String text = text(args, "text");
        String reason = !mind.spaced("say", config.say_min_seconds) ? "rate_limited" : !mind.spend(config, 1) ? "budget_exhausted" : null;
        if (reason == null) {
            Body b = body(mind);
            String said;
            try {
                if (b == null) throw new IllegalStateException("no body");
                said = onServer(() -> voice(b, text));
            } catch (InterruptedException e) {
                mind.refund(config, 1);
                throw e;
            } catch (Exception e) {
                mind.refund(config, 1);
                return UNREACHABLE;
            }
            if (!said.equals("said")) {
                mind.refund(config, 1);
                reason = "body_refused";
            }
        }
        JsonObject out = J.obj("spoken", reason == null, "budget_remaining_thoughts", mind.credits(config));
        if (reason != null) out.addProperty("reason", reason);
        return Reply.ok(out, reason == null ? 1 : 0);
    }

    private Reply act(Mind mind, JsonObject args) throws Exception {
        if (!mind.spaced("act", config.act_min_seconds)) return Reply.refuse("rate_limited", "Too many acts in a short time. Wait, then try again.");
        Body b = body(mind);
        if (b == null) return UNREACHABLE;
        String say = text(args, "say");
        JsonArray actions = args.has("actions") ? args.getAsJsonArray("actions") : new JsonArray();
        JsonObject intentJson = args.has("intent") ? args.getAsJsonObject("intent") : null;

        // Flat result indices: say first, then each action, then the intent.
        List<JsonObject> rejected = new ArrayList<>();
        int index = 0;
        int sayIndex = say == null ? -1 : index++;
        int firstAction = index;
        index += actions.size();
        int intentIndex = intentJson == null ? -1 : index;

        // The action list is atomic: one bad action refuses them all.
        List<Task> tasks = new ArrayList<>();
        Map<Integer, String> bad = new HashMap<>();
        for (int i = 0; i < actions.size(); i++) {
            try {
                tasks.add(Plan.action(actions.get(i).getAsJsonObject(), mind, config));
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
                intent = Plan.readIntent(intentJson, config);
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
                    mind.revision++;
                    mind.plan.submit(mind, b, tick(), tasks, built, spoken);
                    return null;
                });
            } catch (InterruptedException e) {
                mind.refund(config, cost);
                throw e;
            } catch (Exception e) {
                mind.refund(config, cost);
                return UNREACHABLE;
            }
        }
        JsonArray accepted = new JsonArray();
        if (spoken != null) accepted.add(sayIndex);
        for (int i = 0; i < tasks.size(); i++) accepted.add(firstAction + i);
        if (built != null) accepted.add(intentIndex);
        rejected.sort(Comparator.comparingInt(o -> o.get("index").getAsInt()));
        JsonArray refused = new JsonArray();
        rejected.forEach(refused::add);
        JsonObject out = new JsonObject();
        out.add("accepted", accepted);
        out.add("rejected", refused);
        out.addProperty("budget_remaining_thoughts", mind.credits(config));
        return Reply.ok(out, cost);
    }

    private static int bytes(JsonObject o) { return o.toString().getBytes(StandardCharsets.UTF_8).length; }

    private Reply await(Mind mind, JsonObject args) throws Exception {
        long head = mind.head();
        long cursor = head;
        String given = text(args, "cursor");
        if (given != null) {
            cursor = Long.parseLong(given.substring(2));
            if (cursor > head) return Reply.refuse("bad_cursor", "The cursor is not one this player was given.");
        }
        int seconds = Math.min(240, args.get("max_seconds").getAsBigDecimal().intValue());
        Set<String> interests = ALL_INTERESTS;
        if (args.has("interests")) {
            interests = new HashSet<>();
            for (JsonElement i : args.getAsJsonArray("interests")) interests.add(i.getAsString());
        }
        // A body that is away is called back; the wait goes on meanwhile.
        body(mind);
        Mind.Woke woke = mind.await(cursor, interests, seconds * 1000L);
        if (woke == null) return Reply.refuse("wait_replaced", "A newer wait for this player replaced this one.");
        Body now = mind.body;
        if (!alive(now)) return WAKING;
        Snapshot.View view;
        try {
            view = onServer(() -> Snapshot.of(this, mind, now));
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return WAKING;
        }
        lastCall.put(mind.seat.id, System.currentTimeMillis());

        Mind.Since since = mind.since(cursor);
        long dropped = since.dropped();
        Set<Long> waking = woke.seqs();
        JsonArray events = Mind.present(since.events());

        // At most forty lines of speech: those that ended the wait first, then the newest of the rest.
        List<Mind.Event> lines = new ArrayList<>();
        for (Mind.Event e : since.events()) if (e.kind.equals("speech")) lines.add(e);
        if (lines.size() > SPEECH_LINES) {
            List<Mind.Event> owned = new ArrayList<>(), others = new ArrayList<>();
            for (Mind.Event e : lines) (waking.contains(e.seq) ? owned : others).add(e);
            if (owned.size() > SPEECH_LINES) owned = owned.subList(owned.size() - SPEECH_LINES, owned.size());
            int room = SPEECH_LINES - owned.size();
            Set<Mind.Event> keep = new HashSet<>(owned);
            if (room > 0) keep.addAll(others.subList(Math.max(0, others.size() - room), others.size()));
            lines.removeIf(e -> !keep.contains(e));
        }
        JsonArray speech = new JsonArray();
        for (Mind.Event e : lines) speech.add(J.obj("from", Snapshot.cut(e.from, 32), "text", Snapshot.cut(e.text, 512)));

        JsonObject world = new JsonObject();
        if (!speech.isEmpty()) world.add("speech", speech);
        if (!view.signs().isEmpty()) world.add("signs", view.signs());
        JsonObject out = new JsonObject();
        out.add("snapshot", view.snapshot());
        out.add("events", events);
        out.addProperty("cursor", "c_" + mind.head());
        out.addProperty("wake_reason", woke.reason());
        out.addProperty("dropped_events", dropped);
        out.add("untrusted_world_text", world);
        out.addProperty("note", Snapshot.NOTE);

        // Too much to send: the oldest events that did not end the wait go first, then speech that did not.
        while (events.size() > MAX_WAIT_EVENTS || bytes(out) > MAX_RESULT_BYTES) {
            int at = -1;
            for (int i = 0; i < events.size() && at < 0; i++) if (!waking.contains(events.get(i).getAsJsonObject().get("seq").getAsLong())) at = i;
            if (at < 0 && events.size() > MAX_WAIT_EVENTS) at = 0;
            if (at < 0) break;
            events.remove(at);
            out.addProperty("dropped_events", ++dropped);
        }
        for (int i = 0; i < speech.size() && bytes(out) > MAX_RESULT_BYTES; ) {
            if (waking.contains(lines.get(i).seq)) { i++; continue; }
            speech.remove(i);
            lines.remove(i);
        }
        return Reply.ok(out);
    }

    /**
     * The body is told to sleep and the day is closed: its events are counted and handed back for reflection. Whether
     * the body finds a bed is its own affair and does not change what this returns.
     */
    private Reply sleep(Mind mind, Body b) throws Exception {
        long day = onServer(() -> {
            mind.revision++;
            mind.plan.submit(mind, b, tick(), List.of(new Tasks.Sleep()), null, null);
            return Goldar.day(b.level());
        });
        Mind.Since since = mind.since(mind.sleepCursor);
        JsonArray events = new JsonArray();
        List<JsonObject> digested = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<JsonObject> speech = new ArrayList<>();
        for (Mind.Event e : since.events()) {
            String[] as = DIGEST.get(e.kind);
            if (as == null) continue;
            digested.add(J.obj("seq", e.seq, "type", as[0], "tick", e.tick, "text", as[1]));
            counts.merge(as[0], 1, Integer::sum);
            if (e.kind.equals("speech")) speech.add(J.obj("from", e.from == null ? "someone" : e.from, "text", e.text == null ? "" : e.text));
        }
        mind.journal("Day " + day + ": asleep. " + digested.size() + " events since the last sleep, " + since.dropped() + " lost before them.");
        if (!since.events().isEmpty()) mind.sleepCursor = since.events().get(since.events().size() - 1).seq;
        mind.lastSleepDay = day;
        mind.save();

        for (JsonObject e : digested.subList(Math.max(0, digested.size() - 100), digested.size())) events.add(e);
        JsonObject eventCounts = new JsonObject();
        counts.forEach(eventCounts::addProperty);
        JsonObject out = J.obj("day", day, "days_since_sleep", 0);
        out.add("events", events);
        out.add("event_counts", eventCounts);
        JsonObject world = new JsonObject();
        if (!speech.isEmpty()) {
            JsonArray lines = new JsonArray();
            speech.subList(Math.max(0, speech.size() - SPEECH_LINES), speech.size()).forEach(lines::add);
            world.add("speech", lines);
        }
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
        String title = text(args, "title"), text = text(args, "text");
        if (args.has("skill_id")) return Reply.refuse("unknown_skill", "That skill is not defined.");
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
        int count = args.get("count").getAsInt();
        if (!mind.spend(config, 1)) return outcome(mind, "budget_exhausted", new JsonObject());
        String refusal = onServer(() -> commons.offer(mind, b, item, count, Goldar.day(b.level())));
        return outcome(mind, refusal, new JsonObject());
    }

    private Reply propose(Mind mind, Body b, JsonObject args) throws Exception {
        String rule = text(args, "rule"), reason = text(args, "reason");
        if (!mind.spend(config, 1)) return outcome(mind, "budget_exhausted", J.obj("proposal_id", "pr_none"));
        Object result = onServer(() -> {
            Object made = commons.propose(mind, b, rule, args.get("value"), reason, Goldar.day(b.level()));
            if (made instanceof Commons.Proposal) {
                // Only those near the stone hear of it; the rest find it on the board.
                var moot = commons.moot(b);
                long tick = tick();
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (p instanceof Body other && other != b && other.level() == b.level() && other.blockPosition().closerThan(moot, Commons.ANNOUNCE_RADIUS)) {
                        other.mind.simple(tick, "proposal");
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
        if (!mind.spend(config, 1)) return outcome(mind, "budget_exhausted", new JsonObject());
        return outcome(mind, onServer(() -> commons.vote(mind, id, vote)), new JsonObject());
    }
}
