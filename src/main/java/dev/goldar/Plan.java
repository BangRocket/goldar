package dev.goldar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.goldar.Tasks.Outcome;
import dev.goldar.Tasks.Task;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * What a body is doing: the immediate actions of the last thought, then its one durable intent. Reflexes come first
 * and pre-empt both: an action they interrupt is dropped, an intent is paused and picked up again afterwards. A new
 * thought replaces what the old one left unfinished.
 */
public final class Plan {
    /** Thrown while reading a thought: the item is refused with this code. */
    public static final class Rejected extends Exception {
        public final String reason;

        public Rejected(String reason) { super(reason); this.reason = reason; }
    }

    /** Verbs that belong to tools of their own, or to parts of the world that are not open. */
    private static final Set<String> NOT_ALLOWED = Set.of("write_book", "read_book", "use_skill", "define_skill", "offer", "propose", "vote");
    private static final Set<String> UNSUPPORTED_PROGRAMS = Set.of("trade_wait");
    /** What a block gives when its item has another name. */
    private static final Map<String, String> DROPS = Map.ofEntries(
        Map.entry("stone", "cobblestone"), Map.entry("deepslate", "cobbled_deepslate"), Map.entry("grass_block", "dirt"),
        Map.entry("coal_ore", "coal"), Map.entry("deepslate_coal_ore", "coal"), Map.entry("iron_ore", "raw_iron"),
        Map.entry("deepslate_iron_ore", "raw_iron"), Map.entry("copper_ore", "raw_copper"), Map.entry("deepslate_copper_ore", "raw_copper"),
        Map.entry("gold_ore", "raw_gold"), Map.entry("deepslate_gold_ore", "raw_gold"), Map.entry("diamond_ore", "diamond"),
        Map.entry("deepslate_diamond_ore", "diamond"), Map.entry("redstone_ore", "redstone"), Map.entry("lapis_ore", "lapis_lazuli"),
        Map.entry("emerald_ore", "emerald"));

    /** The durable goal of a thought. */
    private static final class Intent {
        String goal;
        Task task;
        String unsupported;
        int maxTicks, maxOps, ticks, opsAtStart;
        Set<String> wakeOn = new HashSet<>();
        /** Where a travel is headed, when that is a fixed place: a blocked walk there gets one detour. */
        BlockPos travelGoal;
        boolean detourTried, awaitingDetour;
    }

    private final Deque<Task> queue = new ArrayDeque<>();
    private final Deque<String> verbs = new ArrayDeque<>();
    private Task current;
    private String currentVerb;
    private Intent intent;
    private final Reflexes reflexes = new Reflexes();
    private boolean dangerWake;

    // ---- reading a thought ----

    private static String str(JsonObject o, String key) throws Rejected {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) throw new Rejected("schema");
        return o.get(key).getAsString();
    }

    private static int num(JsonObject o, String key, int min, int max) throws Rejected {
        try {
            int n = o.get(key).getAsInt();
            if (n < min || n > max) throw new Rejected("out_of_bounds");
            return n;
        } catch (RuntimeException e) {
            throw new Rejected("schema");
        }
    }

    private static int opt(JsonObject o, String key, int min, int max, int fallback) throws Rejected {
        return o.has(key) ? num(o, key, min, max) : fallback;
    }

    private static BlockPos pos(JsonElement e) throws Rejected {
        if (e == null || !e.isJsonObject()) throw new Rejected("schema");
        JsonObject o = e.getAsJsonObject();
        return new BlockPos(num(o, "x", -30_000_000, 30_000_000), num(o, "y", -64, 320), num(o, "z", -30_000_000, 30_000_000));
    }

    /** A walk to a position, or to wherever the named agent is. */
    private static Task walkTo(JsonElement target) throws Rejected {
        if (target != null && target.isJsonPrimitive()) {
            String name = target.getAsString();
            return new Tasks.Walk(b -> {
                Player p = b.level().getServer().getPlayerList().getPlayerByName(name);
                return p == null || p.level() != b.level() ? null : p.blockPosition();
            }, 2);
        }
        return new Tasks.Walk(pos(target), 1);
    }

    private static Tasks.Build build(JsonElement e) throws Rejected {
        if (e == null || !e.isJsonObject()) throw new Rejected("schema");
        JsonObject blueprint = e.getAsJsonObject();
        String name = str(blueprint, "name");
        if (!blueprint.has("blocks") || !blueprint.get("blocks").isJsonArray()) throw new Rejected("schema");
        var blocks = blueprint.getAsJsonArray("blocks");
        if (blocks.isEmpty() || blocks.size() > 512) throw new Rejected("out_of_bounds");
        List<Tasks.Build.Part> parts = new ArrayList<>();
        for (JsonElement b : blocks) {
            if (!b.isJsonObject()) throw new Rejected("schema");
            JsonObject o = b.getAsJsonObject();
            parts.add(new Tasks.Build.Part(str(o, "block"), num(o, "dx", -256, 256), num(o, "dy", -256, 256), num(o, "dz", -256, 256)));
        }
        return new Tasks.Build(name, parts);
    }

    /** The task for one immediate action. */
    public static Task action(JsonObject a, Mind mind) throws Rejected {
        String verb = str(a, "do");
        if (NOT_ALLOWED.contains(verb)) throw new Rejected("not_allowed");
        return switch (verb) {
            case "goto" -> walkTo(a.get("target"));
            case "mine" -> new Tasks.Mine(str(a, "block"), num(a, "count", 1, 256));
            case "craft" -> new Crafting.Craft(str(a, "item"), num(a, "count", 1, 64));
            case "place" -> new Tasks.Place(str(a, "block"), a.has("at") && a.get("at").isJsonPrimitive() ? null : pos(a.get("at")));
            case "attack" -> new Tasks.Attack(str(a, "target"));
            case "eat" -> new Tasks.Eat();
            case "sleep" -> new Tasks.Sleep();
            case "give" -> new Tasks.Give(str(a, "to"), str(a, "item"), num(a, "count", 1, 64));
            case "wait" -> new Tasks.Wait(num(a, "seconds", 1, 600));
            case "open" -> new Tasks.Transfer(Tasks.Transfer.Kind.OPEN, pos(a.get("at")), null, 0);
            case "deposit" -> new Tasks.Transfer(Tasks.Transfer.Kind.DEPOSIT, pos(a.get("at")), str(a, "item"), num(a, "count", 1, 64));
            case "withdraw" -> new Tasks.Transfer(Tasks.Transfer.Kind.WITHDRAW, pos(a.get("at")), str(a, "item"), num(a, "count", 1, 64));
            case "build" -> build(a.get("blueprint"));
            case "write_sign" -> {
                String text = str(a, "text");
                if (text.isBlank() || text.length() > 60) throw new Rejected("out_of_bounds");
                yield new Tasks.WriteSign(text);
            }
            case "note" -> {
                String text = str(a, "text");
                yield b -> {
                    try { mind.journal(text); } catch (java.io.IOException e) { return Outcome.fail("internal", "the note was not kept"); }
                    return Outcome.OK;
                };
            }
            default -> throw new Rejected("schema");
        };
    }

    /** Checks an intent and builds its program. Throws when its shape is wrong. */
    private static Intent intent(JsonObject o) throws Rejected {
        Intent i = new Intent();
        i.goal = str(o, "goal");
        String program = str(o, "program");
        if (!o.has("arguments") || !o.get("arguments").isJsonObject() || !o.has("limits") || !o.get("limits").isJsonObject()
            || !o.has("wake_on") || !o.get("wake_on").isJsonArray()) {
            throw new Rejected("schema");
        }
        JsonObject args = o.getAsJsonObject("arguments"), limits = o.getAsJsonObject("limits");
        i.maxTicks = num(limits, "max_world_ticks", 1, 240_000);
        i.maxOps = num(limits, "max_block_operations", 1, 10_000);
        for (JsonElement w : o.getAsJsonArray("wake_on")) i.wakeOn.add(w.getAsString());
        if (UNSUPPORTED_PROGRAMS.contains(program)) { i.unsupported = program; return i; }
        i.task = switch (program) {
            case "travel" -> {
                JsonElement target = args.get("target");
                if (target != null && target.isJsonObject()) i.travelGoal = pos(target);
                yield walkTo(target);
            }
            case "gather" -> {
                String block = str(args, "block");
                String item = args.has("item") ? str(args, "item") : DROPS.getOrDefault(block, block);
                int count = num(args, "count", 1, 4096);
                int radius = opt(args, "radius", 1, 64, 32);
                yield new Task() {
                    Tasks.Mine mine;

                    @Override public Outcome tick(Body b) {
                        if (mine == null) mine = new Tasks.Mine(block, count, radius, item, b.count(item) + count);
                        return mine.tick(b);
                    }
                };
            }
            case "craft" -> new Crafting.Craft(str(args, "item"), num(args, "count", 1, 576));
            case "rest" -> new Rest(opt(args, "until_health", 1, 20, 20), opt(args, "wait_seconds", 1, 60, 5), opt(args, "max_waits", 1, 120, 30));
            case "flee" -> new Flee(opt(args, "distance", 8, 64, 24), opt(args, "max_legs", 1, 10, 4));
            case "build" -> build(args.get("blueprint"));
            case "follow" -> new Follow(str(args, "target"), opt(args, "distance", 1, 16, 3));
            default -> null;
        };
        if (i.task == null) i.unsupported = program;
        return i;
    }

    /** Builds an intent without installing it, so a bad one is refused before anything is charged. */
    public static Object readIntent(JsonObject o) throws Rejected { return intent(o); }

    // ---- installing ----

    /** Replaces the unfinished actions with a new list. */
    public void setActions(Body b, List<Task> tasks, List<String> names) {
        if (current != null && b != null) current.stop(b);
        current = null;
        queue.clear();
        verbs.clear();
        queue.addAll(tasks);
        verbs.addAll(names);
    }

    /** Replaces the intent with one built by `readIntent`. */
    public void setIntent(Body b, Object built) {
        if (intent != null && intent.task != null && b != null) intent.task.stop(b);
        intent = (Intent) built;
        intent.opsAtStart = b == null ? 0 : b.blockOperations;
        reflexes.blocked = null;
        reflexes.detour = null;
    }

    public void clear(Body b) {
        if (b != null) {
            if (current != null) current.stop(b);
            if (intent != null && intent.task != null) intent.task.stop(b);
            reflexes.stop(b);
        }
        current = null;
        intent = null;
        queue.clear();
        verbs.clear();
    }

    /** The intent's goal, or null when there is none. */
    public String goal() { return intent == null ? null : intent.goal; }

    /** What the running intent asked to be woken by, or null when no intent runs. */
    public Set<String> wakeOn() { return intent == null ? null : intent.wakeOn; }

    // ---- running ----

    /** A failure in the words the world uses when it tells a mind why its intent ended. */
    private static String why(Outcome o) {
        return switch (o.reason()) {
            case "missing_materials" -> "you lack " + (o.message() == null ? "materials" : o.message().replaceFirst("^missing ", ""));
            case "missing_station" -> "no crafting table within reach";
            case "blocked" -> "the way was blocked";
            case "out_of_reach" -> "too far from the block";
            default -> o.reason() + (o.message() == null ? "" : " (" + o.message() + ")");
        };
    }

    private void endIntent(Mind mind, Body b, long tick, String text, String wake) {
        if (intent.task != null) intent.task.stop(b);
        intent = null;
        reflexes.blocked = null;
        mind.emit(tick, "intent_finished", text, wake);
    }

    private void failIntent(Mind mind, Body b, long tick, String reason) {
        endIntent(mind, b, tick, "Your intent failed: " + reason + ".", "precondition_failed");
    }

    private void idleWake(Mind mind, long tick) {
        if (current == null && queue.isEmpty() && intent == null) mind.emit(tick, "wake", "You wake with nothing underway.", null);
    }

    public void tick(Mind mind, Body b, long tick) {
        // Reflexes first. One that takes over drops the action in hand and pauses the intent.
        if (reflexes.begin(b, tick)) {
            if (current != null) {
                current.stop(b);
                mind.emit(tick, "action_failed", "Your " + currentVerb + " failed: interrupted (your body acted on its own).", null);
                current = null;
                queue.clear();
                verbs.clear();
            }
            if (intent != null && intent.task != null) intent.task.stop(b);
        }
        if (reflexes.busy()) {
            Reflexes.Fired fired = reflexes.tick(b);
            if (fired == null) return;
            Goldar.world().onReflex(mind, fired);
            if (fired.name().equals("eat") && fired.result().equals(Reflexes.HANDLED)) mind.emit(tick, "reflex", "Your body ate on its own.", null);
            if ("reflex_danger".equals(fired.wake())) {
                // The world reports a reflex's alarm to the mind as harm of no size.
                mind.emit(tick, "damage", "You took 0 damage.", "unexpected_damage");
                dangerWake = true;
            }
            if (reflexes.busy()) return;
        }
        if (dangerWake) {
            dangerWake = false;
            if (intent != null) { failIntent(mind, b, tick, "interrupted (a reflex woke the Mind)"); return; }
        }
        if (b.isSleeping() && current == null) return;
        if (current == null && !queue.isEmpty()) { current = queue.poll(); currentVerb = verbs.poll(); }
        if (current != null) {
            Outcome o = current.tick(b);
            if (o == null) return;
            if (!o.ok()) {
                mind.emit(tick, "action_failed", "Your " + currentVerb + " failed: " + o.reason() + (o.message() == null ? "" : " (" + o.message() + ")") + ".", null);
                // The steps after a failed one were planned on its success.
                queue.clear();
                verbs.clear();
            } else if (o.message() != null) {
                mind.emit(tick, "action", o.message(), null);
            }
            current = null;
            idleWake(mind, tick);
            return;
        }
        if (intent == null) return;
        if (intent.unsupported != null) { failIntent(mind, b, tick, "unsupported: " + intent.unsupported + " is not available in Phase 0"); return; }
        if (intent.awaitingDetour) {
            if (reflexes.detour == null) return;
            boolean reached = reflexes.detour;
            reflexes.detour = null;
            intent.awaitingDetour = false;
            if (reached) endIntent(mind, b, tick, "Your intent finished.", "goal_completed");
            else failIntent(mind, b, tick, "blocked: no way round the obstacle");
            return;
        }
        if (++intent.ticks > intent.maxTicks || b.blockOperations - intent.opsAtStart > intent.maxOps) {
            endIntent(mind, b, tick, "Your intent ran out of time.", "intent_run_too_long");
            return;
        }
        Outcome o = intent.task.tick(b);
        if (o == null) return;
        if (o.ok()) { endIntent(mind, b, tick, "Your intent finished.", "goal_completed"); return; }
        if (o.reason().equals("blocked") && intent.travelGoal != null && !intent.detourTried) {
            // A blocked walk is handed to the body, which tries one way round before the mind hears of it.
            intent.detourTried = true;
            intent.awaitingDetour = true;
            reflexes.detour = null;
            reflexes.blocked = new Reflexes.Blocked(intent.travelGoal, 1, tick, o.message() == null ? "no way there" : o.message());
            return;
        }
        failIntent(mind, b, tick, why(o));
    }

    // ---- programs with no action of their own ----

    /** Sleeps if it can, else waits for health to come back. */
    private static final class Rest implements Task {
        private final int untilHealth, waitTicks, maxWaits;
        private Tasks.Sleep sleep = new Tasks.Sleep();
        private int waits, ticks;

        Rest(int untilHealth, int waitSeconds, int maxWaits) { this.untilHealth = untilHealth; this.waitTicks = waitSeconds * 20; this.maxWaits = maxWaits; }

        @Override public Outcome tick(Body b) {
            if (sleep != null) {
                Outcome o = sleep.tick(b);
                if (o == null) return null;
                sleep = null;
            }
            b.halt();
            if (b.getHealth() >= untilHealth) return Outcome.OK;
            if (++ticks < waitTicks) return null;
            ticks = 0;
            return ++waits >= maxWaits ? Outcome.fail("timeout", "health " + (int) b.getHealth() + " below " + untilHealth) : null;
        }

        @Override public void stop(Body b) { if (sleep != null) sleep.stop(b); b.halt(); }
    }

    /** Runs from nearby hostiles until none is close. */
    private static final class Flee implements Task {
        private final int distance, maxLegs;
        private int legs;
        private Tasks.Walk walk;

        Flee(int distance, int maxLegs) { this.distance = distance; this.maxLegs = maxLegs; }

        @Override public Outcome tick(Body b) {
            if (walk != null) {
                if (walk.tick(b) == null) return null;
                walk = null;
            }
            List<Entity> hostiles = b.level().getEntities(b, b.getBoundingBox().inflate(distance), e -> e instanceof Enemy && e.isAlive());
            if (hostiles.isEmpty()) { b.halt(); return Outcome.OK; }
            if (legs++ >= maxLegs) { b.halt(); return Outcome.fail("blocked", hostiles.size() + " hostile(s) still near after " + maxLegs + " legs"); }
            Vec3 away = Vec3.ZERO;
            for (Entity e : hostiles) away = away.add(b.position().subtract(e.position()).normalize());
            if (away.lengthSqr() < 1e-6) away = new Vec3(1, 0, 0);
            Vec3 to = b.position().add(away.normalize().scale(distance));
            BlockPos ground = b.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(to));
            walk = new Tasks.Walk(ground, 3);
            return null;
        }
    }

    /** Stays near a named agent until the intent's limits end it. */
    private static final class Follow implements Task {
        private final String name;
        private final int distance;
        private Tasks.Walk walk;

        Follow(String name, int distance) { this.name = name; this.distance = distance; }

        @Override public Outcome tick(Body b) {
            Player p = b.level().getServer().getPlayerList().getPlayerByName(name);
            if (p == null || p.level() != b.level()) { b.halt(); return Outcome.fail("not_found", "no player " + name + " in view"); }
            if (walk == null) {
                if (p.distanceTo(b) <= distance + 1) { b.halt(); return null; }
                walk = new Tasks.Walk(x -> p.isRemoved() ? null : p.blockPosition(), distance);
            }
            Outcome o = walk.tick(b);
            if (o == null) return null;
            walk = null;
            return o.ok() ? null : o;
        }

        @Override public void stop(Body b) { b.halt(); }
    }
}
