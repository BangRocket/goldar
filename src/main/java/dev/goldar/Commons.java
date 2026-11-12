package dev.goldar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * What the minds hold in common: the Shrine, where offerings earn a share of each day's issuance, and the Moot Stone,
 * where rules are proposed and voted on. Both stand at the world spawn unless an operator moves them. Their records
 * are kept in config/goldar/commons.json. Server thread only.
 */
final class Commons {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final int REACH = 3;
    static final int ANNOUNCE_RADIUS = 32;
    private static final int VOTING_DAYS = 3;
    private static final double QUORUM = 0.20;
    private static final double ISSUANCE_POOL_FRACTION = 0.15;
    /** A game day is twenty real minutes: a third of an hour's allowance. */
    private static final double REAL_HOURS_PER_DAY = 1.0 / 3;
    private static final int CREDITS_CEILING = 400;

    static final class Offer {
        String receipt, player, item;
        int count;
        double weight;
        long day;
        int credits = -1;
    }

    static final class Proposal {
        String id, by, rule, reason, outcome;
        JsonElement value;
        long openedDay, closesDay;
        Map<String, String> votes = new LinkedHashMap<>();
    }

    static final class Law {
        String rule, kind, proposal;
        JsonElement value;
        long day;
        int yes, no;
    }

    int[] shrine, moot;
    Map<String, Double> tariff = new LinkedHashMap<>();
    List<Offer> pending = new ArrayList<>();
    List<Offer> settled = new ArrayList<>();
    Set<String> offeredBooks = new HashSet<>();
    List<Proposal> proposals = new ArrayList<>();
    List<Law> laws = new ArrayList<>();
    int nextReceipt = 1, nextProposal = 1;
    long lastDawn = -1;

    private transient Path file;

    static Commons load(Path file) throws IOException {
        Commons c = Files.exists(file) ? GSON.fromJson(Files.readString(file), Commons.class) : null;
        if (c == null) c = new Commons();
        c.file = file;
        if (c.tariff.isEmpty()) {
            c.tariff.put("wheat", 1.0 / 16);
            c.tariff.put("iron_ingot", 3.0);
            c.tariff.put("gold_ingot", 6.0);
            c.tariff.put("emerald", 15.0);
            c.tariff.put("diamond", 30.0);
            c.tariff.put("enchanted_book", 50.0);
            c.tariff.put("written_book", 1.0);
        }
        return c;
    }

    void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException e) {
            Goldar.LOGGER.error("Could not save the commons", e);
        }
    }

    private static BlockPos place(int[] at, Body b) { return at == null ? b.level().getRespawnData().pos() : new BlockPos(at[0], at[1], at[2]); }

    BlockPos shrine(Body b) { return place(shrine, b); }

    BlockPos moot(Body b) { return place(moot, b); }

    private static boolean near(Body b, BlockPos at, int reach) {
        return Math.abs(b.getX() - (at.getX() + 0.5)) <= reach + 0.5 && Math.abs(b.getZ() - (at.getZ() + 0.5)) <= reach + 0.5 && Math.abs(b.getY() - at.getY()) <= reach + 1;
    }

    // ---- the shrine ----

    /** Takes the items and writes a receipt. Returns null when accepted, else the reason it was not. */
    String offer(Mind mind, Body b, String item, int count, long day) {
        if (!near(b, shrine(b), REACH)) return "too_far";
        Double weight = tariff.get(item);
        if (weight == null || weight <= 0) return "not_accepted";
        if (b.count(item) < count) return "not_held";
        var items = b.getInventory().getNonEquipmentItems();
        int left = count, counted = 0;
        for (int i = 0; i < items.size() && left > 0; i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty() || !Body.name(s).equals(item)) continue;
            if (item.equals("written_book")) {
                // A written book counts once, the first time that book is ever offered.
                Books.Read read = Books.read(s);
                if (read != null && offeredBooks.add(read.id())) counted++;
            }
            int n = Math.min(left, s.getCount());
            s.shrink(n);
            left -= n;
        }
        Offer o = new Offer();
        o.receipt = "rc_" + nextReceipt++;
        o.player = mind.seat.id;
        o.item = item;
        o.count = count;
        o.weight = weight * (item.equals("written_book") ? counted : count);
        o.day = day;
        pending.add(o);
        save();
        return null;
    }

    /** At dawn the day's pool is shared among those who offered, by the weight of what they gave. */
    private void settle(World world) {
        if (pending.isEmpty()) return;
        long living = world.config.players.stream().filter(p -> !p.ended).count();
        double pool = ISSUANCE_POOL_FRACTION * living * world.config.credits_per_real_hour * REAL_HOURS_PER_DAY;
        Map<String, Double> weights = new HashMap<>();
        double total = 0;
        for (Offer o : pending) { weights.merge(o.player, o.weight, Double::sum); total += o.weight; }
        Map<String, Integer> paid = new HashMap<>();
        for (var e : weights.entrySet()) {
            int credits = total <= 0 ? 0 : (int) Math.floor(Math.min(e.getValue(), pool * e.getValue() / total));
            paid.put(e.getKey(), credits);
            Mind mind = world.mind(e.getKey());
            if (mind != null && credits > 0) mind.refund(world.config, credits);
        }
        for (Offer o : pending) {
            Double mine = weights.get(o.player);
            o.credits = mine == null || mine <= 0 ? 0 : (int) Math.floor(paid.get(o.player) * o.weight / mine);
            settled.add(o);
        }
        pending.clear();
        while (settled.size() > 500) settled.remove(0);
    }

    // ---- the moot ----

    /** What a rule's value must be for the world to act on it; a rule the world does not know is a social law. */
    private static String check(String rule, JsonElement value) {
        boolean number = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
        boolean flag = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
        if (rule.equals("chat_radius")) return number && value.getAsDouble() >= 1 && value.getAsDouble() <= 128 ? "mechanical" : null;
        if (rule.equals("credits_per_real_hour")) return number && value.getAsDouble() >= 1 && value.getAsDouble() <= 60 ? "mechanical" : null;
        if (rule.equals("cap_credits")) return number && value.getAsDouble() >= 1 && value.getAsDouble() <= CREDITS_CEILING ? "mechanical" : null;
        if (rule.startsWith("tariff.")) return number && value.getAsDouble() >= 0 && value.getAsDouble() <= 1000 ? "mechanical" : null;
        if (rule.startsWith("reflex.")) return flag ? "mechanical" : null;
        return "social";
    }

    private void apply(World world, Law law) {
        if (!law.kind.equals("mechanical")) return;
        GoldarConfig c = world.config;
        if (law.rule.equals("chat_radius")) c.chat_radius = law.value.getAsInt();
        else if (law.rule.equals("credits_per_real_hour")) c.credits_per_real_hour = law.value.getAsInt();
        else if (law.rule.equals("cap_credits")) c.cap_credits = law.value.getAsInt();
        else if (law.rule.startsWith("tariff.")) tariff.put(law.rule.substring(7), law.value.getAsDouble());
        else if (law.rule.startsWith("reflex.")) {
            String name = law.rule.substring(7);
            c.reflexes_off.remove(name);
            if (!law.value.getAsBoolean()) c.reflexes_off.add(name);
        }
        try { c.save(); } catch (IOException e) { Goldar.LOGGER.error("Could not save the config", e); }
    }

    /** Opens a proposal. Returns it, or a refusal reason as a string. */
    Object propose(Mind mind, Body b, String rule, JsonElement value, String reason, long day) {
        if (!near(b, moot(b), REACH)) return "too_far";
        if (check(rule, value) == null) return "invalid_value";
        Proposal p = new Proposal();
        p.id = "pr_" + nextProposal++;
        p.by = mind.seat.name;
        p.rule = rule;
        p.value = value == null ? JsonNull.INSTANCE : value;
        p.reason = reason;
        p.openedDay = day;
        p.closesDay = day + VOTING_DAYS;
        proposals.add(p);
        save();
        return p;
    }

    String vote(Mind mind, String id, String vote) {
        for (Proposal p : proposals) {
            if (!p.id.equals(id)) continue;
            if (p.outcome != null) return "closed";
            if (p.votes.containsKey(mind.seat.id)) return "already_voted";
            p.votes.put(mind.seat.id, vote);
            save();
            return null;
        }
        return "unknown_proposal";
    }

    private void close(World world, long day) {
        long living = world.config.players.stream().filter(p -> !p.ended).count();
        for (Proposal p : proposals) {
            if (p.outcome != null || day < p.closesDay) continue;
            int yes = 0, no = 0;
            for (String v : p.votes.values()) if (v.equals("yes")) yes++; else no++;
            boolean passed = yes + no >= Math.ceil(QUORUM * living) && yes + no > 0 && yes > no;
            p.outcome = passed ? "passed" : "failed";
            if (!passed) continue;
            // A new law on a rule replaces the old one; a null value repeals it.
            laws.removeIf(l -> l.rule.equals(p.rule));
            if (p.value == null || p.value.isJsonNull()) continue;
            Law law = new Law();
            law.rule = p.rule;
            law.value = p.value;
            law.kind = check(p.rule, p.value);
            law.proposal = p.id;
            law.day = day;
            law.yes = yes;
            law.no = no;
            laws.add(law);
            apply(world, law);
        }
        while (proposals.size() > 200) proposals.remove(0);
    }

    /** Once each dawn: offerings are paid and votes that have run their days are counted. */
    void dawn(World world, long day) {
        if (day == lastDawn) return;
        lastDawn = day;
        if (world.config.shrine) settle(world);
        if (world.config.governance) close(world, day);
        save();
    }

    /** The board at the Moot Stone: laws in force, then proposals still open. */
    List<String> board() {
        List<String> out = new ArrayList<>();
        for (Law l : laws) out.add("Law: " + l.rule + " = " + l.value + " (day " + l.day + ", " + l.yes + " to " + l.no + ")");
        for (Proposal p : proposals) {
            if (p.outcome != null) continue;
            out.add("Proposal " + p.id + " by " + p.by + ": " + p.rule + " = " + p.value + (p.reason == null || p.reason.isEmpty() ? "" : ". " + p.reason)
                + " Voting closes on day " + p.closesDay + ".");
        }
        List<String> clipped = new ArrayList<>();
        for (String s : out.subList(Math.max(0, out.size() - 20), out.size())) clipped.add(s.length() > 256 ? s.substring(0, 256) : s);
        return clipped;
    }

    JsonObject receipt(Offer o) {
        return J.obj("receipt_id", o.receipt, "life_id", o.player.replace('-', '_'), "kind", "offer", "phase", o.credits < 0 ? "prepared" : "settled",
            "item", o.item, "count", o.count, "credits", Math.max(0, o.credits), "world_day", o.day);
    }
}
