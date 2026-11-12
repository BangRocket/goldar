package dev.goldar;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The endpoint minds connect to: MCP's streamable HTTP at /p/&lt;player-id&gt;, with that player's bearer token. It
 * behaves as Geldar's does on the wire: one session per player, begun by `initialize`; each request answered as a
 * server-sent event stream that carries keepalives while a long call runs.
 */
public final class Mcp {
    private static final int MAX_BODY = 65536;
    private static final int MAX_OPEN = 4;
    private static final long SSE_KEEPALIVE_SECONDS = 15, CALL_KEEPALIVE_SECONDS = 25;
    private static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05", "2024-10-07");
    private static final Pattern PATH = Pattern.compile("^/p/([a-z][a-z0-9-]{1,38})$");
    private static final Pattern BEARER = Pattern.compile("^Bearer ([A-Za-z0-9._~+/=-]{16,200})$");
    private static final String SELF = "geldar://player/self", JOURNAL = "geldar://player/journal/recent", RULES = "geldar://shard/rules";
    private static final String KEEPALIVE = "keepalive: the call is still running.";
    private static final String[][] PROMPTS = {
        {"geldar:play", "Play the loop.", "Live your day in a loop: look at the world, wait for something to happen, act on it, and sleep when the day ends. Keep each step short."},
        {"geldar:reflect", "Reflect before sleeping.", "Before you sleep, look at the world once more, wait for what is left to happen, and act on it. Then sleep."},
        {"geldar:newborn", "Start a new life.", "You are newly born: look around first, then wait and act as the world asks of you. Sleep when you are tired."},
    };
    private static final Gson STRICT = new Gson();

    /** A protocol fault answered as a JSON-RPC error. */
    private static final class RpcFault extends Exception {
        final int code;

        RpcFault(int code, String message) { super(message); this.code = code; }
    }

    /** One open event stream. Writes from the handler and from the keepalive timer take turns. */
    private static final class Stream {
        private final HttpExchange x;
        private final OutputStream out;
        private boolean closed;

        Stream(HttpExchange x) { this.x = x; this.out = x.getResponseBody(); }

        private synchronized void write(String text) {
            if (closed) return;
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        void message(JsonObject message) { write("event: message\ndata: " + message + "\n\n"); }

        void comment() { write(": keepalive\n\n"); }

        synchronized boolean closed() { return closed; }

        synchronized void close() {
            if (closed) return;
            closed = true;
            x.close();
        }
    }

    /** One player's session: there is at most one, and a new `initialize` ends the old. */
    private static final class Session {
        final String id = UUID.randomUUID().toString();
        final String player;
        /** The threads answering this session's requests, by request id, so a cancel can reach them. */
        final Map<String, Thread> inflight = new ConcurrentHashMap<>();
        /** The answers still being written, so a revoked token can end them. */
        final java.util.Set<Stream> streams = ConcurrentHashMap.newKeySet();
        volatile Stream standalone;
        volatile boolean ended;

        Session(String player) { this.player = player; }
    }

    /** A token bucket that refills evenly through the minute. */
    private static final class Buckets {
        private final Map<String, double[]> buckets = new HashMap<>();

        private double[] read(String key, int perMinute, long now) {
            double[] b = buckets.get(key);
            if (b == null) return new double[] {perMinute, now};
            return new double[] {Math.min(perMinute, b[0] + (now - b[1]) * perMinute / 60_000.0), now};
        }

        private static int retry(double tokens, int perMinute) {
            return (int) Math.max(1, Math.ceil(Math.ceil((1 - tokens) * 60_000 / perMinute) / 1000.0));
        }

        /** Seconds to wait, or 0 when a token was taken. */
        synchronized int take(String key, int perMinute) {
            long now = System.currentTimeMillis();
            if (buckets.size() > 4096) buckets.entrySet().removeIf(e -> now - e.getValue()[1] > 120_000);
            double[] b = read(key, perMinute, now);
            boolean ok = b[0] >= 1;
            if (ok) b[0] -= 1;
            buckets.put(key, b);
            return ok ? 0 : retry(b[0], perMinute);
        }

        /** Seconds to wait while the bucket is empty, else 0. Takes nothing. */
        synchronized int blocked(String key, int perMinute) {
            double[] b = read(key, perMinute, System.currentTimeMillis());
            return b[0] >= 1 ? 0 : retry(b[0], perMinute);
        }
    }

    private final World world;
    private final HttpServer http;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "goldar-mcp");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "goldar-mcp-keepalive");
        t.setDaemon(true);
        return t;
    });
    private final JsonArray tools;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Session> byPlayer = new ConcurrentHashMap<>();
    private final Map<String, String> superseded = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> openRequests = new ConcurrentHashMap<>();
    private final Buckets buckets = new Buckets();

    public Mcp(World world) throws IOException {
        this.world = world;
        try (InputStream in = Mcp.class.getResourceAsStream("/goldar/tools.json")) {
            if (in == null) throw new IOException("The tool contract is missing from the mod.");
            tools = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
        }
        Map<String, JsonObject> inputs = new HashMap<>();
        for (JsonElement t : tools) inputs.put(t.getAsJsonObject().get("name").getAsString(), t.getAsJsonObject().getAsJsonObject("inputSchema"));
        world.inputSchemas = inputs;
        http = HttpServer.create(new InetSocketAddress(world.config.bind, world.config.port), 64);
        http.createContext("/", this::handle);
        http.setExecutor(pool);
        http.start();
    }

    /** The player's token was replaced: its session ends and every open request is told so and cut. */
    public void revoke(String playerId) {
        Session session = byPlayer.remove(playerId);
        if (session == null) return;
        JsonObject frame = J.obj("jsonrpc", "2.0", "error", J.obj("code", -32002, "message", "session_revoked"));
        frame.add("id", com.google.gson.JsonNull.INSTANCE);
        for (Stream s : session.streams) { s.message(frame); s.close(); }
        Stream alone = session.standalone;
        if (alone != null) alone.message(frame);
        end(session);
    }

    public void stop() {
        http.stop(0);
        timer.shutdownNow();
        pool.shutdownNow();
    }

    // ---- plain answers ----

    private static void empty(HttpExchange x, int status, String... headers) throws IOException {
        for (int i = 0; i < headers.length; i += 2) x.getResponseHeaders().set(headers[i], headers[i + 1]);
        x.sendResponseHeaders(status, -1);
        x.close();
    }

    private static void json(HttpExchange x, int status, String body, String... headers) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) x.getResponseHeaders().set(headers[i], headers[i + 1]);
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    private static JsonObject rpcError(JsonElement id, int code, String message) {
        JsonObject o = J.obj("jsonrpc", "2.0");
        if (id != null) o.add("id", id);
        o.add("error", J.obj("code", code, "message", message));
        return o;
    }

    /** A refusal made before any request was read: a JSON-RPC error with no id. */
    private static void refuse(HttpExchange x, int status, int code, String message) throws IOException {
        json(x, status, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + code + ",\"message\":" + new JsonPrimitive(message) + "},\"id\":null}");
    }

    private static void tooMany(HttpExchange x, int retryAfter, boolean close) throws IOException {
        String body = "{\"error\":{\"code\":\"rate_limited\",\"message\":\"Too many requests\"},\"retryAfter\":" + retryAfter + "}";
        if (close) json(x, 429, body, "Retry-After", String.valueOf(retryAfter), "Connection", "close");
        else json(x, 429, body, "Retry-After", String.valueOf(retryAfter));
    }

    // ---- the front door ----

    private void handle(HttpExchange x) throws IOException {
        try {
            serve(x);
        } catch (IOException e) {
            x.close();
        } catch (RuntimeException e) {
            Goldar.LOGGER.error("The MCP endpoint failed a request", e);
            try { empty(x, 500); } catch (IOException | RuntimeException ignored) { x.close(); }
        }
    }

    private static String header(HttpExchange x, String name) { return x.getRequestHeaders().getFirst(name); }

    /** With hosts listed in the config, a request must name one of them, on the port minds connect to. */
    private boolean hostAllowed(String host) {
        List<String> allowed = world.config.allowed_hosts;
        if (allowed == null || allowed.isEmpty()) return true;
        if (host == null) return false;
        int colon = host.lastIndexOf(':');
        boolean hasPort = colon > host.lastIndexOf(']') && colon >= 0;
        String name = hasPort ? host.substring(0, colon) : host;
        if (hasPort && !host.substring(colon + 1).equals(String.valueOf(world.config.port))) return false;
        for (String a : allowed) if (a.equalsIgnoreCase(name)) return true;
        return false;
    }

    private void serve(HttpExchange x) throws IOException {
        Matcher path = PATH.matcher(x.getRequestURI().getPath());
        if (!path.matches()) { empty(x, 404); return; }
        String playerId = path.group(1);
        if (!hostAllowed(header(x, "Host"))) { empty(x, 403); return; }
        // A browser sends an Origin; a mind's client does not.
        if (header(x, "Origin") != null) { empty(x, 403); return; }
        if (world.config.closed) { json(x, 503, "{\"error\":{\"code\":\"unavailable\",\"message\":\"Service unavailable\"}}", "Connection", "close"); return; }

        String address = x.getRemoteAddress().getAddress().getHostAddress();
        int wait = buckets.blocked("auth-failed:" + address, 30);
        if (wait > 0) { tooMany(x, wait, true); return; }
        String auth = header(x, "Authorization");
        Matcher bearer = auth == null ? null : BEARER.matcher(auth);
        String token = bearer != null && bearer.matches() ? bearer.group(1) : null;
        GoldarConfig.Player seat = world.config.player(playerId);
        if (!GoldarConfig.matches(seat, token)) {
            // A good token used at another player's address is refused differently from a bad one.
            if (token != null) for (GoldarConfig.Player other : List.copyOf(world.config.players)) {
                if (GoldarConfig.matches(other, token)) { empty(x, 403, "Connection", "close"); return; }
            }
            buckets.take("auth-failed:" + address, 30);
            json(x, 401, "{\"error\":{\"code\":\"unauthenticated\",\"message\":\"Unauthorized\"}}", "WWW-Authenticate", "Bearer", "Connection", "close");
            return;
        }

        String method = x.getRequestMethod();
        if (!method.equals("POST") && !method.equals("GET") && !method.equals("DELETE")) { empty(x, 405, "Allow", "POST, GET, DELETE"); return; }

        AtomicInteger open = openRequests.computeIfAbsent(playerId, k -> new AtomicInteger());
        boolean counted = !method.equals("DELETE");
        try {
            if (counted && open.incrementAndGet() > MAX_OPEN) { tooMany(x, 1, false); return; }
            route(x, playerId, method);
        } finally {
            if (counted) open.decrementAndGet();
        }
    }

    private void route(HttpExchange x, String playerId, String method) throws IOException {
        JsonElement parsed = null;
        if (method.equals("POST")) {
            long declared = -1;
            try { declared = Long.parseLong(String.valueOf(header(x, "Content-Length"))); } catch (NumberFormatException ignored) { }
            byte[] raw = declared > MAX_BODY ? null : x.getRequestBody().readNBytes(MAX_BODY + 1);
            if (raw == null || raw.length > MAX_BODY) { empty(x, 413, "Connection", "close"); return; }
            try {
                parsed = STRICT.getAdapter(JsonElement.class).fromJson(new String(raw, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                parsed = null;
            }
            if (parsed == null) { refuse(x, 400, -32700, "Parse error"); return; }
            if (parsed.isJsonArray()) { refuse(x, 400, -32003, "batch_not_allowed"); return; }
            boolean toolCall = parsed.isJsonObject() && parsed.getAsJsonObject().has("method") && parsed.getAsJsonObject().get("method").isJsonPrimitive()
                && parsed.getAsJsonObject().get("method").getAsString().equals("tools/call");
            int wait = toolCall ? buckets.take("player:" + playerId, world.config.calls_per_minute) : buckets.take("handshake:" + playerId, 300);
            if (wait > 0) { tooMany(x, wait, false); return; }
            if (isInitialize(parsed)) {
                Session session = new Session(playerId);
                Session old = byPlayer.put(playerId, session);
                if (old != null) { superseded.put(old.id, playerId); end(old); }
                sessions.put(session.id, session);
                dispatch(x, session, parsed.getAsJsonObject(), true);
                return;
            }
        } else if (method.equals("GET")) {
            int wait = buckets.take("handshake:" + playerId, 300);
            if (wait > 0) { tooMany(x, wait, false); return; }
        }

        String sessionId = header(x, "Mcp-Session-Id");
        if (sessionId == null) { refuse(x, 400, -32000, "missing_session"); return; }
        if (playerId.equals(superseded.get(sessionId))) { refuse(x, 404, -32001, "session_superseded"); return; }
        Session session = sessions.get(sessionId);
        if (session == null || !session.player.equals(playerId)) { refuse(x, 404, -32001, "session_not_found"); return; }
        if (method.equals("DELETE")) {
            byPlayer.remove(playerId, session);
            end(session);
            empty(x, 200);
            return;
        }
        if (method.equals("GET")) { listen(x, session); return; }
        dispatch(x, session, parsed, false);
    }

    private static boolean isInitialize(JsonElement parsed) {
        if (!parsed.isJsonObject()) return false;
        JsonObject o = parsed.getAsJsonObject();
        if (!validRequest(o) || !o.has("id") || !o.get("method").getAsString().equals("initialize")) return false;
        if (!o.has("params") || !o.get("params").isJsonObject()) return false;
        JsonObject p = o.getAsJsonObject("params");
        if (!isString(p.get("protocolVersion")) || !p.has("capabilities") || !p.get("capabilities").isJsonObject()) return false;
        if (!p.has("clientInfo") || !p.get("clientInfo").isJsonObject()) return false;
        JsonObject client = p.getAsJsonObject("clientInfo");
        return isString(client.get("name")) && isString(client.get("version"));
    }

    private static boolean isString(JsonElement e) { return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString(); }

    private static boolean validId(JsonElement id) {
        if (id == null || !id.isJsonPrimitive()) return false;
        JsonPrimitive p = id.getAsJsonPrimitive();
        if (p.isString()) return true;
        if (!p.isNumber()) return false;
        try {
            return p.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A request or a notification: version 2.0, a method, and an id that is a string or a whole number if present. */
    private static boolean validRequest(JsonObject o) {
        if (!isString(o.get("jsonrpc")) || !o.get("jsonrpc").getAsString().equals("2.0")) return false;
        if (!isString(o.get("method"))) return false;
        return !o.has("id") || validId(o.get("id"));
    }

    private void end(Session session) {
        session.ended = true;
        sessions.remove(session.id);
        for (Thread t : session.inflight.values()) t.interrupt();
        Stream s = session.standalone;
        if (s != null) s.close();
    }

    // ---- inside a session ----

    private static void sse(HttpExchange x, Session session) throws IOException {
        var h = x.getResponseHeaders();
        h.set("Content-Type", "text/event-stream");
        h.set("Cache-Control", "no-cache, no-transform");
        h.set("Connection", "keep-alive");
        h.set("X-Accel-Buffering", "no");
        h.set("mcp-session-id", session.id);
        x.sendResponseHeaders(200, 0);
    }

    /** The stream a client may hold open for messages the world starts. The world starts none, so it only stays alive. */
    private void listen(HttpExchange x, Session session) throws IOException {
        String accept = header(x, "Accept");
        if (accept == null || !accept.contains("text/event-stream")) { refuse(x, 406, -32000, "Not Acceptable: Client must accept text/event-stream"); return; }
        if (badVersion(x)) return;
        synchronized (session) {
            if (session.standalone != null && !session.standalone.closed()) {
                refuse(x, 409, -32000, "Conflict: Only one SSE stream is allowed per session");
                return;
            }
            sse(x, session);
            session.standalone = new Stream(x);
        }
        Stream stream = session.standalone;
        x.getResponseBody().flush();
        try {
            while (!stream.closed() && !session.ended) {
                Thread.sleep(SSE_KEEPALIVE_SECONDS * 1000);
                stream.comment();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stream.close();
        }
    }

    private static boolean badVersion(HttpExchange x) throws IOException {
        String version = header(x, "Mcp-Protocol-Version");
        if (version == null || VERSIONS.contains(version)) return false;
        refuse(x, 400, -32000, "Bad Request: Unsupported protocol version: " + version + " (supported versions: " + String.join(", ", VERSIONS) + ")");
        return true;
    }

    private void dispatch(HttpExchange x, Session session, JsonElement parsed, boolean initialize) throws IOException {
        String accept = header(x, "Accept");
        if (accept == null || !accept.contains("application/json") || !accept.contains("text/event-stream")) {
            refuse(x, 406, -32000, "Not Acceptable: Client must accept both application/json and text/event-stream");
            return;
        }
        String type = header(x, "Content-Type");
        if (type == null || !type.contains("application/json")) { refuse(x, 415, -32000, "Unsupported Media Type: Content-Type must be application/json"); return; }
        if (!initialize && badVersion(x)) return;

        JsonObject req = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        boolean response = req != null && !req.has("method") && isString(req.get("jsonrpc")) && validId(req.get("id")) && (req.has("result") || req.has("error"));
        if (req == null || !(response || validRequest(req))) { refuse(x, 400, -32700, "Parse error: Invalid JSON-RPC message"); return; }
        if (response || !req.has("id")) {
            if (!response && req.get("method").getAsString().equals("notifications/cancelled") && req.has("params") && req.get("params").isJsonObject()) {
                JsonElement target = req.getAsJsonObject("params").get("requestId");
                Thread t = target == null ? null : session.inflight.get(target.toString());
                if (t != null) t.interrupt();
            }
            empty(x, 202);
            return;
        }

        JsonElement id = req.get("id");
        String method = req.get("method").getAsString();
        JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
        sse(x, session);
        Stream stream = new Stream(x);
        x.getResponseBody().flush();
        ScheduledFuture<?> alive = timer.scheduleAtFixedRate(stream::comment, SSE_KEEPALIVE_SECONDS, SSE_KEEPALIVE_SECONDS, TimeUnit.SECONDS);
        ScheduledFuture<?> progress = null;
        if (method.equals("tools/call")) {
            JsonElement token = params.has("_meta") && params.get("_meta").isJsonObject() ? params.getAsJsonObject("_meta").get("progressToken") : null;
            AtomicInteger n = new AtomicInteger();
            progress = timer.scheduleAtFixedRate(() -> {
                JsonObject p = token == null || !token.isJsonPrimitive()
                    ? J.obj("method", "notifications/message", "params", J.obj("level", "debug", "logger", "geldar-mcp", "data", KEEPALIVE))
                    : J.obj("method", "notifications/progress", "params", J.obj("progressToken", token, "progress", n.incrementAndGet(), "message", KEEPALIVE));
                p.addProperty("jsonrpc", "2.0");
                stream.message(p);
            }, CALL_KEEPALIVE_SECONDS, CALL_KEEPALIVE_SECONDS, TimeUnit.SECONDS);
        }
        session.inflight.put(id.toString(), Thread.currentThread());
        session.streams.add(stream);
        try {
            JsonObject out;
            try {
                JsonObject result = answer(session.player, method, params);
                out = new JsonObject();
                out.add("result", result);
                out.addProperty("jsonrpc", "2.0");
                out.add("id", id);
            } catch (RpcFault f) {
                out = rpcError(id, f.code, f.getMessage());
            }
            // A call that was cancelled, or whose session ended, gets no answer.
            if (!Thread.interrupted() && !session.ended) stream.message(out);
        } catch (InterruptedException e) {
            // Cancelled: the stream just closes.
        } finally {
            session.inflight.remove(id.toString());
            session.streams.remove(stream);
            alive.cancel(false);
            if (progress != null) progress.cancel(false);
            stream.close();
        }
    }

    private static JsonObject resource(String uri, String mime, String text) {
        JsonArray contents = new JsonArray();
        contents.add(J.obj("uri", uri, "mimeType", mime, "text", text));
        JsonObject result = new JsonObject();
        result.add("contents", contents);
        return result;
    }

    private static String cut(String text, int max) { return text.length() > max ? text.substring(0, max) : text; }

    private JsonObject answer(String playerId, String method, JsonObject params) throws RpcFault, InterruptedException, IOException {
        switch (method) {
            case "initialize" -> {
                String asked = params.get("protocolVersion").getAsString();
                return J.obj("protocolVersion", VERSIONS.contains(asked) ? asked : VERSIONS.get(0),
                    "capabilities", J.obj("tools", new JsonObject(), "resources", new JsonObject(), "prompts", new JsonObject(), "logging", new JsonObject()),
                    "serverInfo", J.obj("name", "geldar-mcp", "version", "0.0.0"));
            }
            case "ping", "logging/setLevel" -> { return new JsonObject(); }
            case "tools/list" -> {
                JsonObject result = new JsonObject();
                result.add("tools", tools);
                return result;
            }
            case "prompts/list" -> {
                JsonArray list = new JsonArray();
                for (String[] p : PROMPTS) list.add(J.obj("name", p[0], "description", p[1]));
                JsonObject result = new JsonObject();
                result.add("prompts", list);
                return result;
            }
            case "prompts/get" -> {
                if (!isString(params.get("name"))) throw new RpcFault(-32603, "Invalid request parameters");
                String name = params.get("name").getAsString();
                for (String[] p : PROMPTS) {
                    if (!p[0].equals(name)) continue;
                    JsonArray messages = new JsonArray();
                    messages.add(J.obj("role", "user", "content", J.obj("type", "text", "text", p[2])));
                    JsonObject result = J.obj("description", p[1]);
                    result.add("messages", messages);
                    return result;
                }
                throw new RpcFault(-32602, "MCP error -32602: unknown prompt: " + cut(name, 80));
            }
            case "resources/list" -> {
                JsonArray list = new JsonArray();
                list.add(J.obj("uri", SELF, "name", "self", "description", "Your name, seed and birth day.", "mimeType", "application/json"));
                list.add(J.obj("uri", JOURNAL, "name", "journal_recent", "description", "The most recent lines of your journal.", "mimeType", "text/plain"));
                list.add(J.obj("uri", RULES, "name", "shard_rules", "description", "The rules of this shard.", "mimeType", "application/json"));
                JsonObject result = new JsonObject();
                result.add("resources", list);
                return result;
            }
            case "resources/read" -> {
                if (!isString(params.get("uri"))) throw new RpcFault(-32603, "Invalid request parameters");
                String uri = params.get("uri").getAsString();
                Mind mind = world.mind(playerId);
                switch (uri) {
                    case SELF -> { return resource(uri, "application/json", world.self(mind).toString()); }
                    case JOURNAL -> {
                        List<String> lines = mind.journalRecent(20);
                        return resource(uri, "text/plain", lines.isEmpty() ? "Nothing written yet." : String.join("\n", lines));
                    }
                    case RULES -> {
                        GoldarConfig c = world.config;
                        return resource(uri, "application/json", J.obj("chat_radius", c.chat_radius, "wait_cap_seconds", 240, "day_length_seconds", 1200,
                            "max_body_bytes", MAX_BODY, "max_result_bytes", World.MAX_RESULT_BYTES, "presence.lease_seconds", 60,
                            "presence.hibernate_after_seconds", c.hibernate_minutes * 60).toString());
                    }
                    default -> throw new RpcFault(-32602, "MCP error -32602: unknown resource: " + cut(uri, 80));
                }
            }
            case "tools/call" -> {
                if (!isString(params.get("name")) || (params.has("arguments") && !params.get("arguments").isJsonObject())) {
                    throw new RpcFault(-32602, "MCP error -32602: Invalid tools/call request: "
                        + (isString(params.get("name")) ? "arguments: expected an object" : "name: expected a string"));
                }
                String name = params.get("name").getAsString();
                JsonObject args = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
                World.Reply reply;
                try {
                    reply = world.call(world.mind(playerId), name, args);
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    if (e.getCause() instanceof InterruptedException || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    Goldar.LOGGER.error("Tool {} failed for {}", name, playerId, e);
                    reply = new World.Reply("internal_error: something went wrong; try again", true, 0);
                }
                JsonArray content = new JsonArray();
                content.add(J.obj("type", "text", "text", reply.text()));
                JsonObject result = new JsonObject();
                result.add("content", content);
                if (reply.error()) result.addProperty("isError", true);
                return result;
            }
            default -> throw new RpcFault(-32601, "Method not found");
        }
    }
}
