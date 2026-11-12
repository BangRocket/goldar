package dev.goldar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** config/goldar.json: where minds connect and who may. Tokens are stored hashed; the plain token is shown once. */
public final class GoldarConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One mind's seat in the world. */
    public static final class Player {
        public String id;
        public String name;
        public String token_sha256;
        /** The character this player was born with, as `geldar://player/self` tells it. May be empty. */
        public String seed = "";
        /** Set when the life ended; the body is not woken again until an operator revives it. */
        public boolean ended;
    }

    public int port = 25580;
    public String bind = "0.0.0.0";
    public int credits_per_real_hour = 30;
    public int cap_credits = 240;
    /** A recipe must be in the body's recipe book before it can be crafted, as in a normal game. */
    public boolean require_unlocked_recipes = true;
    /** A body whose mind has been silent this long leaves the world until the next call. 0 keeps it in. */
    public int hibernate_minutes = 10;
    /** How far speech carries, in blocks. */
    public int chat_radius = 32;
    /** The least time between one mind's acts, says and journal lines, in seconds. */
    public double act_min_seconds = 2, say_min_seconds = 3, journal_min_seconds = 5;
    /** Host names a request may be addressed to. Empty lets any through. */
    public List<String> allowed_hosts = new ArrayList<>();
    /** The switch that shuts the endpoint: every request is answered 503 while it is set. */
    public boolean closed = false;
    /** How many calls a minute one player's endpoint takes before it answers 429. */
    public int calls_per_minute = 60;
    /** When true a death ends the life; otherwise the body comes back at the spawn as a player would. */
    public boolean permadeath = false;
    /**
     * Parts of the world Geldar describes but does not open yet. They are closed here too unless switched on: books a
     * mind can write, the Shrine, the Moot Stone, the `give`, `build`, `write_sign` and `note` actions with the
     * `build` and `follow` programs, and word of dusk and dawn.
     */
    public boolean books = false, shrine = false, governance = false, extra_actions = false, day_night_events = false;
    /** How far from the origin a plan may point, as Geldar's world border sets it. 0 for no limit. */
    public int world_border_radius = 1500;
    /** With MineColonies installed: the building style of a colony a body founds. Empty takes the first installed. */
    public String colony_style = "";
    /** Reflexes switched off, by name. */
    public java.util.Set<String> reflexes_off = new java.util.HashSet<>();
    /** The Glass: the page people watch from. It is never shown to minds. */
    public boolean glass = true;
    public int glass_port = 8787;
    public String glass_bind = "127.0.0.1";
    public int glass_delay_seconds = 60;
    public List<Player> players = new ArrayList<>();

    private transient Path file;

    public static GoldarConfig load(Path file) throws IOException {
        GoldarConfig c = Files.exists(file) ? GSON.fromJson(Files.readString(file), GoldarConfig.class) : new GoldarConfig();
        if (c == null) c = new GoldarConfig();
        if (c.players == null) c.players = new ArrayList<>();
        if (c.reflexes_off == null) c.reflexes_off = new java.util.HashSet<>();
        c.file = file;
        if (!Files.exists(file)) c.save();
        return c;
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this));
    }

    public synchronized Player player(String id) {
        for (Player p : players) if (p.id.equals(id)) return p;
        return null;
    }

    /** Adds a player and returns its token, which is not kept. Null if the id is taken. */
    public synchronized String add(String id, String name) throws IOException {
        if (player(id) != null) return null;
        Player p = new Player();
        p.id = id;
        p.name = name;
        String token = newToken();
        p.token_sha256 = sha256(token);
        players.add(p);
        save();
        return token;
    }

    /** Replaces a player's token and returns the new one. Null if there is no such player. */
    public synchronized String rotate(String id) throws IOException {
        Player p = player(id);
        if (p == null) return null;
        String token = newToken();
        p.token_sha256 = sha256(token);
        save();
        return token;
    }

    private static String newToken() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return "gmcp-" + HexFormat.of().formatHex(raw);
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when the token is this player's. Compares hashes in constant time. */
    public static boolean matches(Player p, String token) {
        if (p == null || token == null || p.token_sha256 == null) return false;
        return MessageDigest.isEqual(sha256(token).getBytes(StandardCharsets.UTF_8), p.token_sha256.getBytes(StandardCharsets.UTF_8));
    }
}
