package dev.goldar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The endpoint minds connect to: MCP over HTTP at /p/<player-id>, with that player's bearer token. Each call is
 * answered in one JSON response; a `wait` simply holds its request open.
 */
public final class Mcp {
    private static final int MAX_BODY = 256 * 1024;
    private static final String PROTOCOL = "2025-06-18";

    private final World world;
    private final HttpServer http;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "goldar-mcp");
        t.setDaemon(true);
        return t;
    });
    private final JsonArray tools;
    /** Per player: when its recent calls came in, and how many requests it has open now. */
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> openRequests = new ConcurrentHashMap<>();
    private static final int MAX_OPEN = 4;
    private static final String JOURNAL = "geldar://player/journal/recent", RULES = "geldar://shard/rules";

    public Mcp(World world) throws IOException {
        this.world = world;
        try (InputStream in = Mcp.class.getResourceAsStream("/goldar/tools.json")) {
            if (in == null) throw new IOException("The tool contract is missing from the mod.");
            tools = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
        }
        http = HttpServer.create(new InetSocketAddress(world.config.bind, world.config.port), 64);
        http.createContext("/p/", this::handle);
        http.setExecutor(pool);
        http.start();
    }

    public void stop() {
        http.stop(0);
        pool.shutdownNow();
    }

    private static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (bytes.length == 0) {
            x.sendResponseHeaders(status, -1);
        } else {
            x.sendResponseHeaders(status, bytes.length);
            x.getResponseBody().write(bytes);
        }
        x.close();
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject o = J.obj("jsonrpc", "2.0", "error", J.obj("code", code, "message", message));
        o.add("id", id);
        return o;
    }

    /** Seconds until the player may call again, or 0 when this call is within its minute's allowance. */
    private int throttled(String playerId) {
        Deque<Long> calls = recent.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (calls) {
            while (!calls.isEmpty() && now - calls.peekFirst() >= 60_000) calls.removeFirst();
            if (calls.size() >= world.config.calls_per_minute) return (int) Math.max(1, (60_000 - (now - calls.peekFirst()) + 999) / 1000);
            calls.addLast(now);
            return 0;
        }
    }

    private static void tooMany(HttpExchange x, int retryAfter) throws IOException {
        x.getResponseHeaders().set("Retry-After", String.valueOf(retryAfter));
        send(x, 429, "{\"error\":{\"code\":\"rate_limited\",\"message\":\"Too many requests\"},\"retryAfter\":" + retryAfter + "}");
    }

    private JsonObject resource(String uri, String mime, String text) {
        JsonArray contents = new JsonArray();
        contents.add(J.obj("uri", uri, "mimeType", mime, "text", text));
        JsonObject result = new JsonObject();
        result.add("contents", contents);
        return result;
    }

    private void handle(HttpExchange x) throws IOException {
        String playerId = x.getRequestURI().getPath().substring("/p/".length());
        // Requests for players that do not exist share one count, so strangers cannot grow the table.
        AtomicInteger open = openRequests.computeIfAbsent(world.config.player(playerId) == null ? "" : playerId, k -> new AtomicInteger());
        try {
            if (open.incrementAndGet() > MAX_OPEN) { tooMany(x, 1); return; }
            serve(x, playerId);
        } finally {
            open.decrementAndGet();
        }
    }

    private void serve(HttpExchange x, String playerId) throws IOException {
        try {
            GoldarConfig.Player seat = world.config.player(playerId);
            String auth = x.getRequestHeaders().getFirst("Authorization");
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
            // One answer for a wrong token and an unknown player, so the endpoint does not say which ids exist.
            if (!GoldarConfig.matches(seat, token)) { send(x, 401, "{\"error\":\"unauthorized\"}"); return; }
            if (!x.getRequestMethod().equals("POST")) { send(x, 405, "{\"error\":\"method_not_allowed\"}"); return; }
            int retryAfter = throttled(playerId);
            if (retryAfter > 0) { tooMany(x, retryAfter); return; }
            byte[] raw = x.getRequestBody().readNBytes(MAX_BODY + 1);
            if (raw.length > MAX_BODY) { send(x, 413, "{\"error\":\"too_large\"}"); return; }
            JsonObject req;
            try {
                req = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) {
                send(x, 400, error(null, -32700, "Parse error").toString());
                return;
            }
            JsonElement id = req.get("id");
            String method = req.has("method") ? req.get("method").getAsString() : "";
            if (id == null) { send(x, 202, ""); return; }
            JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
            JsonObject result;
            switch (method) {
                case "initialize" -> {
                    x.getResponseHeaders().set("Mcp-Session-Id", UUID.randomUUID().toString());
                    result = J.obj("protocolVersion", params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : PROTOCOL,
                        "capabilities", J.obj("tools", J.obj("listChanged", false), "resources", new JsonObject()),
                        "serverInfo", J.obj("name", "goldar-mcp", "version", Goldar.VERSION));
                }
                case "ping" -> result = new JsonObject();
                case "tools/list" -> { result = new JsonObject(); result.add("tools", tools); }
                case "resources/list" -> {
                    JsonArray list = new JsonArray();
                    list.add(J.obj("uri", JOURNAL, "name", "Recent journal", "mimeType", "text/plain"));
                    list.add(J.obj("uri", RULES, "name", "World rules", "mimeType", "application/json"));
                    result = new JsonObject();
                    result.add("resources", list);
                }
                case "resources/read" -> {
                    String uri = params.has("uri") ? params.get("uri").getAsString() : "";
                    if (uri.equals(JOURNAL)) {
                        var lines = world.mind(playerId).journalRecent(20);
                        result = resource(uri, "text/plain", lines.isEmpty() ? "Nothing written yet." : String.join("\n", lines));
                    } else if (uri.equals(RULES)) {
                        GoldarConfig c = world.config;
                        result = resource(uri, "application/json", J.obj("chat_radius", c.chat_radius, "chat_max_chars", 256, "credits_per_real_hour", c.credits_per_real_hour,
                            "cap_credits", c.cap_credits, "act_min_seconds", c.act_min_seconds, "say_min_seconds", c.say_min_seconds,
                            "journal_min_seconds", c.journal_min_seconds).toString());
                    } else {
                        send(x, 200, error(id, -32002, "Resource not found").toString());
                        return;
                    }
                }
                case "tools/call" -> {
                    String name = params.has("name") ? params.get("name").getAsString() : "";
                    JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
                    World.Reply reply;
                    try {
                        reply = world.call(world.mind(playerId), name, args);
                    } catch (Exception e) {
                        Goldar.LOGGER.error("Tool {} failed for {}", name, playerId, e);
                        reply = new World.Reply("internal_error: something went wrong; try again", true, 0);
                    }
                    JsonArray content = new JsonArray();
                    content.add(J.obj("type", "text", "text", reply.text()));
                    result = new JsonObject();
                    result.add("content", content);
                    if (reply.error()) result.addProperty("isError", true);
                }
                default -> { send(x, 200, error(id, -32601, "Method not found").toString()); return; }
            }
            JsonObject out = J.obj("jsonrpc", "2.0");
            out.add("id", id);
            out.add("result", result);
            send(x, 200, out.toString());
        } catch (RuntimeException e) {
            Goldar.LOGGER.error("The MCP endpoint failed a request", e);
            send(x, 500, "{\"error\":\"internal\"}");
        }
    }
}
