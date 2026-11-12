package dev.goldar;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One connected mind: its body, what has happened to it, and what it may still spend. World state is touched only on
 * the server thread; the event list is the one part HTTP threads read, under its own lock.
 */
public final class Mind {
    private static final int MAX_EVENTS = 400;
    private static final int JOURNAL_LINE_LIMIT = 4000;
    private static final AtomicLong SEQ = new AtomicLong();

    /**
     * Something that happened, in the order it happened across the whole world. `wake` is the reason it may end a
     * `wait`, or null for an event that never does.
     */
    public record Event(long seq, long tick, String type, String text, String wake) {
        JsonObject json() { return J.obj("seq", seq, "tick", tick, "type", type, "text", text); }
    }

    /** What someone nearby said. */
    public record Heard(String from, String text, long tick) {}

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
    public final List<Heard> heard = new ArrayList<>();

    private final Deque<Event> events = new ArrayDeque<>();
    private long dropped;
    private double credits;
    private long creditsAt = System.currentTimeMillis();
    private final Path journal;
    private final Map<String, Long> lastUse = new HashMap<>();

    public Mind(GoldarConfig.Player seat, int startCredits, Path journalDir) {
        this.seat = seat;
        this.credits = startCredits;
        this.journal = journalDir.resolve(seat.id + ".md");
    }

    /** The life id as the world writes it: the player id with underscores. */
    public String lifeId() { return seat.id.replace('-', '_'); }

    // ---- events ----

    public void emit(long tick, String type, String text, String wake) {
        synchronized (events) {
            events.addLast(new Event(SEQ.incrementAndGet(), tick, type, text, wake));
            if (events.size() > MAX_EVENTS) { events.removeFirst(); dropped++; }
            events.notifyAll();
        }
    }

    /** Counts a line overheard: a run of them is one event, placed at the latest line. */
    public void overheard(long tick) {
        synchronized (events) {
            int n = 1;
            Event last = events.peekLast();
            if (last != null && last.type().equals("conversation_digest")) {
                events.removeLast();
                n += Integer.parseInt(last.text().substring(0, last.text().indexOf(' ')));
            }
            events.addLast(new Event(SEQ.incrementAndGet(), tick, "conversation_digest", n + " lines overheard.", null));
            events.notifyAll();
        }
    }

    /** The newest sequence number given to any mind: where a `wait` with no cursor starts. */
    public long head() { return SEQ.get(); }

    /** Events after `cursor`, oldest first. */
    public List<Event> since(long cursor) {
        List<Event> out = new ArrayList<>();
        synchronized (events) { for (Event e : events) if (e.seq() > cursor) out.add(e); }
        return out;
    }

    /** Blocks until an event after `cursor` may end a wait with one of the wanted reasons, or the time runs out. */
    public Event await(long cursor, Set<String> wakes, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        synchronized (events) {
            while (true) {
                for (Event e : events) if (e.seq() > cursor && e.wake() != null && wakes.contains(e.wake())) return e;
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return null;
                events.wait(left);
            }
        }
    }

    public long dropped() { synchronized (events) { return dropped; } }

    public static JsonArray eventsJson(List<Event> list) {
        JsonArray a = new JsonArray();
        for (Event e : list) a.add(e.json());
        return a;
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

    /** Appends a line and returns how many characters of it were kept. */
    public synchronized int journal(String text) throws IOException {
        String line = text.replace("\u0000", "").replace('\r', ' ').replace('\n', ' ');
        if (line.length() > JOURNAL_LINE_LIMIT) line = line.substring(0, JOURNAL_LINE_LIMIT);
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return line.length();
    }

    /** The newest `n` journal lines, oldest first. */
    public synchronized List<String> journalRecent(int n) throws IOException {
        if (!Files.exists(journal)) return List.of();
        List<String> lines = Files.readAllLines(journal);
        return new ArrayList<>(lines.subList(Math.max(0, lines.size() - n), lines.size()));
    }
}
