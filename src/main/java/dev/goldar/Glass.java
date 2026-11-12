package dev.goldar;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Glass: a read-only window for people onto what the minds are doing. It serves a page and a JSON and event-stream
 * API of each thought (a tool call and the reasoning sent with it), each reflex and each shrine receipt. Nothing here
 * writes to the world or is ever shown to a mind, and what a living mind does appears only after a delay.
 */
public final class Glass {
    private static final int KEEP = 5000;
    private static final int MAX_STREAMS = 8;
    private static final long STREAM_MILLIS = 10 * 60_000L;
    private static final long HEARTBEAT_MILLIS = 15_000;

    /** One thing a mind or its body did. */
    private record Row(long id, long at, String lifeId, String name, long day, long tick, String kind, String tool, String reasoning, String outcome, int credits) {
        JsonObject json(boolean full) {
            JsonObject o = J.obj("thought_id", "th_" + id, "life_id", lifeId, "name", name, "at", Instant.ofEpochMilli(at).toString(), "world_day", day,
                "world_tick", tick, "kind", kind, "tool", tool, "outcome", outcome, "credits", credits);
            if (full && reasoning != null) o.addProperty("reasoning", reasoning);
            return o;
        }
    }

    private final World world;
    private final HttpServer http;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "goldar-glass");
        t.setDaemon(true);
        return t;
    });
    private final Deque<Row> rows = new ArrayDeque<>();
    private final AtomicLong next = new AtomicLong();
    private final AtomicInteger streams = new AtomicInteger();
    private final byte[] page;
    /** Who lives here and where they stand, refreshed by the server each second. */
    public volatile JsonArray agents = new JsonArray();
    private volatile boolean open = true;

    public Glass(World world) throws IOException {
        this.world = world;
        try (InputStream in = Glass.class.getResourceAsStream("/goldar/glass.html")) {
            if (in == null) throw new IOException("The Glass page is missing from the mod.");
            page = in.readAllBytes();
        }
        http = HttpServer.create(new InetSocketAddress(world.config.glass_bind, world.config.glass_port), 32);
        http.createContext("/", this::handle);
        http.setExecutor(pool);
        http.start();
    }

    public void stop() {
        open = false;
        http.stop(0);
        pool.shutdownNow();
    }

    /** Notes a tool call, a reflex or a receipt. Any thread. */
    public void record(Mind mind, long day, long tick, String kind, String tool, String reasoning, String outcome, int credits) {
        synchronized (rows) {
            rows.addLast(new Row(next.incrementAndGet(), System.currentTimeMillis(), mind.lifeId(), mind.seat.name, day, tick, kind, tool, reasoning, outcome, credits));
            if (rows.size() > KEEP) rows.removeFirst();
        }
    }

    /** A living mind's doings are held back; a life that has ended is open at once. */
    private boolean visible(Row r, long now) {
        GoldarConfig.Player seat = world.config.player(r.lifeId().replace('_', '-'));
        return seat != null && seat.ended || r.at() <= now - world.config.glass_delay_seconds * 1000L;
    }

    private List<Row> visibleRows() {
        long now = System.currentTimeMillis();
        List<Row> out = new ArrayList<>();
        synchronized (rows) { for (Row r : rows) if (visible(r, now)) out.add(r); }
        return out;
    }

    private static Map<String, String> query(HttpExchange x) {
        Map<String, String> q = new HashMap<>();
        String raw = x.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static void send(HttpExchange x, int status, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        boolean head = x.getRequestMethod().equals("HEAD");
        x.sendResponseHeaders(status, head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) x.getResponseBody().write(body);
        x.close();
    }

    private static void json(HttpExchange x, int status, Object body) throws IOException { send(x, status, "application/json", body.toString().getBytes(StandardCharsets.UTF_8)); }

    private static int number(String s, int fallback) {
        try { return s == null ? fallback : Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            String method = x.getRequestMethod(), path = x.getRequestURI().getPath();
            if (!method.equals("GET") && !method.equals("HEAD")) { json(x, 405, J.obj("error", "method_not_allowed")); return; }
            if (path.equals("/") || path.equals("/index.html")) { send(x, 200, "text/html; charset=utf-8", page); return; }
            if (path.equals("/api/agents")) { json(x, 200, agents); return; }
            if (path.equals("/api/thoughts")) {
                Map<String, String> q = query(x);
                int limit = Math.max(1, Math.min(100, number(q.get("limit"), 50)));
                long cursor = number(q.get("cursor"), Integer.MAX_VALUE);
                String agent = q.get("agent");
                List<Row> all = visibleRows();
                JsonArray out = new JsonArray();
                long last = 0;
                // Newest first; `next` carries on from the oldest row sent.
                for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
                    Row r = all.get(i);
                    if (r.id() >= cursor || agent != null && !agent.equals(r.lifeId())) continue;
                    out.add(r.json(true));
                    last = r.id();
                }
                JsonObject body = new JsonObject();
                body.add("thoughts", out);
                if (out.size() == limit) body.addProperty("next", last);
                json(x, 200, body);
                return;
            }
            if (path.startsWith("/api/thoughts/")) {
                String id = path.substring("/api/thoughts/".length());
                for (Row r : visibleRows()) if (("th_" + r.id()).equals(id)) { json(x, 200, r.json(true)); return; }
                json(x, 404, J.obj("error", "not_found"));
                return;
            }
            if (path.equals("/api/receipts")) { json(x, 200, world.receipts()); return; }
            if (path.equals("/api/stream")) { stream(x); return; }
            json(x, 404, J.obj("error", "not_found"));
        } catch (RuntimeException e) {
            Goldar.LOGGER.error("The Glass failed a request", e);
            json(x, 500, J.obj("error", "internal"));
        }
    }

    /** Server-sent events: each thought as it passes the delay, with a heartbeat between. */
    private void stream(HttpExchange x) throws IOException {
        if (streams.incrementAndGet() > MAX_STREAMS) {
            streams.decrementAndGet();
            json(x, 503, J.obj("error", "too_many_streams"));
            return;
        }
        try (OutputStream out = x.getResponseBody()) {
            x.getResponseHeaders().set("Content-Type", "text/event-stream");
            x.getResponseHeaders().set("Cache-Control", "no-store");
            x.sendResponseHeaders(200, 0);
            long started = System.currentTimeMillis(), beat = started;
            List<Row> first = visibleRows();
            long seen = first.isEmpty() ? 0 : first.get(first.size() - 1).id();
            while (open && System.currentTimeMillis() - started < STREAM_MILLIS) {
                for (Row r : visibleRows()) {
                    if (r.id() <= seen) continue;
                    seen = r.id();
                    out.write(("event: thought\nid: th_" + r.id() + "\ndata: " + r.json(true) + "\n\n").getBytes(StandardCharsets.UTF_8));
                    beat = 0;
                }
                long now = System.currentTimeMillis();
                if (now - beat >= HEARTBEAT_MILLIS) {
                    out.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                    beat = now;
                }
                out.flush();
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The viewer went away.
        } finally {
            streams.decrementAndGet();
            x.close();
        }
    }
}
