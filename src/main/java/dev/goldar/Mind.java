package dev.goldar;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One connected mind: its body, what has happened to it, and what it may still spend. World state is touched only on
 * the server thread; the event list is the one part HTTP threads read, under its own lock.
 */
public final class Mind {
    private static final int MAX_EVENTS = 1000;
    private static final int JOURNAL_LINE_LIMIT = 4000;
    private static final int HEARD_LINES = 50;
    /** After a wait returns, lesser news does not end another for this long. */
    private static final long MIN_WAKE_SPACING_MS = 30_000;
    /** A wait returns once an intent has run this long since it began or since the last return. */
    private static final long INTENT_TOO_LONG_MS = 300_000;
    /** Which reason a wait gives when several events could end it, most pressing first. */
    private static final List<String> PRIORITY = List.of("directly_addressed", "precondition_failed", "goal_completed", "unexpected_damage",
        "intent_run_too_long", "new_agent_nearby", "proposal", "day_night");
    private static final Map<String, String> INTEREST_OF = Map.of("speech", "directly_addressed", "damage", "damage", "agent_approached", "agent_approached",
        "proposal", "proposal", "day_night", "day_night");

    /**
     * Something that happened to this mind, numbered in the order it happened. `kind` is speech, damage,
     * agent_approached, proposal, day_night, intent_finished, reflex or wake. `wakeOn` is what the intent running
     * at the time asked to be woken by, or null when none ran.
     */
    public static final class Event {
        long seq, tick, atMs;
        String kind;
        /** speech */
        boolean addressed;
        String from, text;
        /** damage: the amount as it is worded. agent_approached: the distance. */
        String amount;
        int distance;
        /** day_night: dusk or dawn. */
        String name;
        /** intent_finished: completed, failed or limit_reached, and for a failure the plain reason if one is known. */
        String outcome, reason;
        Set<String> wakeOn;

        Event(String kind) { this.kind = kind; }
    }

    /** What someone nearby said. */
    public record Heard(String from, String text, long tick) {}

    /** Why a wait ended and which events ended it. */
    public record Woke(String reason, Set<Long> seqs) {}

    /** The events after a cursor, and how many more were lost before they could be read. */
    public record Since(List<Event> events, long dropped) {}

    public final GoldarConfig.Player seat;
    public volatile Body body;
    /** True from the call that asked for a body until it stands in the world. */
    public volatile boolean waking;
    public int revision;
    public long lastSleepDay = -1;
    public long bornDay = -1;
    /** The last event the last sleep took into its digest. */
    public long sleepCursor;
    public Plan plan = new Plan();
    /** The newest lines heard, oldest first. Server thread only. */
    public final Deque<Heard> heard = new ArrayDeque<>();
    /** When the body last spoke, for the body's own limit on how often it speaks. */
    public long lastSpokeMs;
    /** Notable blocks the body has seen, oldest first. Server thread only. */
    public final java.util.LinkedHashMap<net.minecraft.core.BlockPos, String> remembered = new java.util.LinkedHashMap<>();
    /** When the body last came into the world, and whether its mind has been told it woke with nothing to do. */
    public long wokeAtMs;
    public boolean wakeTold;

    private final Deque<Event> events = new ArrayDeque<>();
    private long seq;
    /** The highest sequence number that has left the ring. */
    private long evictedThrough;
    private long lastWakeMs = -1;
    private int waitGeneration;
    private double credits;
    private long creditsAt = System.currentTimeMillis();
    private final Path journal, state;
    private final Map<String, Long> lastUse = new HashMap<>();

    public Mind(GoldarConfig.Player seat, int startCredits, Path dir) {
        this.seat = seat;
        this.credits = startCredits;
        this.journal = dir.resolve("journal").resolve(seat.id + ".jsonl");
        this.state = dir.resolve("minds").resolve(seat.id + ".json");
        load();
    }

    /** The life id as the world writes it: the player id with underscores. */
    public String lifeId() { return seat.id.replace('-', '_'); }

    // ---- what outlives a restart ----

    private void load() {
        try {
            if (!Files.exists(state)) return;
            JsonObject o = JsonParser.parseString(Files.readString(state)).getAsJsonObject();
            credits = o.get("credits").getAsDouble();
            creditsAt = o.get("credits_at").getAsLong();
            bornDay = o.get("born_day").getAsLong();
            lastSleepDay = o.get("last_sleep_day").getAsLong();
            sleepCursor = o.get("sleep_cursor").getAsLong();
            seq = o.get("seq").getAsLong();
            evictedThrough = seq;
            revision = o.get("revision").getAsInt();
        } catch (IOException | RuntimeException e) {
            Goldar.LOGGER.error("Could not read the saved state of {}", seat.id, e);
        }
    }

    public void save() {
        JsonObject o;
        synchronized (this) {
            o = J.obj("credits", credits, "credits_at", creditsAt, "born_day", bornDay, "last_sleep_day", lastSleepDay, "sleep_cursor", sleepCursor,
                "revision", revision);
        }
        synchronized (events) { o.addProperty("seq", seq); }
        try {
            Files.createDirectories(state.getParent());
            Files.writeString(state, new Gson().toJson(o));
        } catch (IOException e) {
            Goldar.LOGGER.error("Could not save the state of {}", seat.id, e);
        }
    }

    // ---- events ----

    private void add(Event e, long tick) {
        Set<String> on = plan.wakeOn();
        e.wakeOn = on == null ? null : Set.copyOf(on);
        e.tick = tick;
        e.atMs = System.currentTimeMillis();
        synchronized (events) {
            e.seq = ++seq;
            events.addLast(e);
            if (events.size() > MAX_EVENTS) evictedThrough = events.removeFirst().seq;
            events.notifyAll();
        }
    }

    public void speech(long tick, String from, String text, boolean addressed) {
        Event e = new Event("speech");
        e.from = from;
        e.text = text;
        e.addressed = addressed;
        add(e, tick);
    }

    public void damage(long tick, String amount) {
        Event e = new Event("damage");
        e.amount = amount;
        add(e, tick);
    }

    public void approached(long tick, int distance) {
        Event e = new Event("agent_approached");
        e.distance = distance;
        add(e, tick);
    }

    public void dayNight(long tick, String name) {
        Event e = new Event("day_night");
        e.name = name;
        // The turn of the day is news whatever the intent asked for.
        add(e, tick);
        e.wakeOn = null;
    }

    public void intentFinished(long tick, String outcome, String reason) {
        Event e = new Event("intent_finished");
        e.outcome = outcome;
        e.reason = reason;
        add(e, tick);
    }

    public void simple(long tick, String kind) { add(new Event(kind), tick); }

    /** The newest sequence number this mind holds: where a `wait` with no cursor starts. */
    public long head() { synchronized (events) { return seq; } }

    public Since since(long cursor) {
        List<Event> out = new ArrayList<>();
        synchronized (events) {
            for (Event e : events) if (e.seq > cursor) out.add(e);
            return new Since(out, Math.max(0, evictedThrough - cursor));
        }
    }

    private static boolean passesWakeOn(Event e) {
        if (e.wakeOn == null) return true;
        return switch (e.kind) {
            case "speech" -> e.wakeOn.contains("directly_addressed");
            case "damage" -> e.wakeOn.contains("unexpected_damage");
            case "agent_approached" -> {
                for (String w : e.wakeOn) {
                    if (w.startsWith("new_agent_within_")) {
                        try {
                            if (e.distance <= Integer.parseInt(w.substring("new_agent_within_".length()))) yield true;
                        } catch (NumberFormatException ignored) { }
                    }
                }
                yield false;
            }
            default -> false;
        };
    }

    private static String reasonOf(Event e) {
        return switch (e.kind) {
            case "intent_finished" -> e.outcome.equals("limit_reached") ? "intent_run_too_long" : e.outcome.equals("failed") ? "precondition_failed" : "goal_completed";
            case "speech" -> "directly_addressed";
            case "damage" -> "unexpected_damage";
            case "agent_approached" -> "new_agent_nearby";
            case "proposal" -> "proposal";
            default -> "day_night";
        };
    }

    /**
     * Blocks until something after `cursor` ends the wait, or the time runs out (reason "timeout"). Returns null
     * when a newer wait took this one's place.
     */
    public Woke await(long cursor, Set<String> interests, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        synchronized (events) {
            int mine = ++waitGeneration;
            events.notifyAll();
            while (true) {
                if (waitGeneration != mine) return null;
                long now = System.currentTimeMillis();
                Set<Long> seqs = new HashSet<>();
                Set<String> reasons = new HashSet<>();
                boolean finished = false;
                for (Event e : events) {
                    if (e.seq <= cursor || e.kind.equals("reflex") || e.kind.equals("wake")) continue;
                    if (e.kind.equals("speech") && !e.addressed) continue;
                    if (e.kind.equals("intent_finished")) finished = true;
                    else if (!passesWakeOn(e) || !interests.contains(INTEREST_OF.getOrDefault(e.kind, ""))) continue;
                    seqs.add(e.seq);
                    reasons.add(reasonOf(e));
                }
                long recheck = Long.MAX_VALUE;
                if (!seqs.isEmpty()) {
                    if (lastWakeMs >= 0 && now - lastWakeMs < MIN_WAKE_SPACING_MS && !finished) {
                        recheck = lastWakeMs + MIN_WAKE_SPACING_MS;
                    } else {
                        for (String r : PRIORITY) if (reasons.contains(r)) { lastWakeMs = now; return new Woke(r, seqs); }
                    }
                }
                long started = plan.intentStartedMs();
                if (started >= 0) {
                    long due = Math.max(started, Math.max(lastWakeMs, 0)) + INTENT_TOO_LONG_MS;
                    if (now >= due) { lastWakeMs = now; return new Woke("intent_run_too_long", Set.of()); }
                    recheck = Math.min(recheck, due);
                }
                if (now >= deadline) { lastWakeMs = now; return new Woke("timeout", Set.of()); }
                events.wait(Math.max(1, Math.min(Math.min(deadline, recheck) - now, 1000)));
            }
        }
    }

    /** The events as a mind reads them: fixed wording only, with a run of overheard lines told as one count. */
    public static JsonArray present(List<Event> list) {
        JsonArray out = new JsonArray();
        int overheard = 0;
        Event lastOverheard = null;
        for (Event e : list) if (e.kind.equals("speech") && !e.addressed) { overheard++; lastOverheard = e; }
        for (Event e : list) {
            String type = e.kind, text;
            switch (e.kind) {
                case "speech" -> {
                    if (e.addressed) { type = "speech_to_me"; text = "Someone spoke to you."; }
                    else if (e == lastOverheard) { type = "conversation_digest"; text = overheard + " lines overheard."; }
                    else continue;
                }
                case "damage" -> text = "You took " + e.amount + " damage.";
                case "agent_approached" -> text = "Someone came within " + e.distance + " blocks.";
                case "proposal" -> text = "A proposal was put forward.";
                case "day_night" -> text = "dusk".equals(e.name) ? "Dusk." : "Dawn.";
                case "intent_finished" -> text = e.outcome.equals("failed") ? "Your intent failed: " + (e.reason == null ? "precondition_failed" : e.reason) + "."
                    : e.outcome.equals("limit_reached") ? "Your intent ran out of time." : "Your intent finished.";
                case "reflex" -> text = "Your body ate on its own.";
                case "wake" -> text = "You wake with nothing underway.";
                default -> { continue; }
            }
            out.add(J.obj("seq", e.seq, "tick", e.tick, "type", type, "text", text));
        }
        return out;
    }

    // ---- credits and spacing ----

    private void accrue(GoldarConfig c) {
        long now = System.currentTimeMillis();
        credits = Math.min(c.cap_credits, credits + (now - creditsAt) / 3_600_000.0 * c.credits_per_real_hour);
        creditsAt = now;
    }

    public synchronized int credits(GoldarConfig c) { accrue(c); return (int) Math.floor(credits); }

    /** Takes `n` credits, or none when there are too few. */
    public synchronized boolean spend(GoldarConfig c, int n) {
        accrue(c);
        if (credits < n) return false;
        credits -= n;
        return true;
    }

    public synchronized void refund(GoldarConfig c, int n) { credits = Math.min(c.cap_credits, credits + n); }

    /** True when `seconds` have passed since the last allowed use of `what`; that use is then now. */
    public synchronized boolean spaced(String what, double seconds) {
        long now = System.currentTimeMillis();
        Long last = lastUse.get(what);
        if (last != null && now - last < seconds * 1000) return false;
        lastUse.put(what, now);
        return true;
    }

    // ---- journal ----

    /** Appends an entry and returns how many characters of it were kept. */
    public synchronized int journal(String text) throws IOException {
        String kept = text.length() > JOURNAL_LINE_LIMIT ? text.substring(0, JOURNAL_LINE_LIMIT) : text;
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, new JsonPrimitive(kept.replace("\u0000", "")) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return kept.length();
    }

    /** The newest `n` journal entries, oldest first. */
    public synchronized List<String> journalRecent(int n) throws IOException {
        if (!Files.exists(journal)) return List.of();
        List<String> lines = Files.readAllLines(journal);
        List<String> out = new ArrayList<>();
        for (String line : lines.subList(Math.max(0, lines.size() - n), lines.size())) {
            try {
                out.add(JsonParser.parseString(line).getAsString());
            } catch (RuntimeException e) {
                out.add(line);
            }
        }
        return out;
    }

    /** Remembers a line heard, keeping the newest fifty. Server thread only. */
    public void hear(String from, String text, long tick) {
        heard.addLast(new Heard(from, text, tick));
        while (heard.size() > HEARD_LINES) heard.removeFirst();
    }
}
