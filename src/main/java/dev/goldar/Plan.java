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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * What a body is doing: the immediate actions of the last thought, then its one durable intent. Reflexes come first
 * and pre-empt both: actions they interrupt are dropped, an intent is paused and picked up again afterwards. The
 * intent also pauses while actions run.
 */
public final class Plan {
    /** Thrown while reading a thought: the item is refused with this code. */
    public static final class Rejected extends Exception {
        public final String reason;

        public Rejected(String reason) { super(reason); this.reason = reason; }
    }

    /** What a thought may do in Geldar today, and what this world can also do when its extras are switched on. */
    private static final Set<String> VERBS = Set.of("goto", "mine", "craft", "place", "eat", "sleep", "open", "deposit", "withdraw", "attack", "wait");
    private static final Set<String> EXTRA_VERBS = Set.of("give", "build", "write_sign", "note");
    private static final Set<String> PROGRAMS = Set.of("travel", "gather", "craft", "rest", "flee");
    private static final Set<String> EXTRA_PROGRAMS = Set.of("build", "follow");
    /** Each program's arguments: name, kind (int, name or target), least, most, and whether it must be given. */
    private static final Map<String, Object[][]> PROGRAM_ARGS = Map.of(
        "travel", new Object[][] {{"target", "target", 0, 0, true}},
        "gather", new Object[][] {{"block", "name", 0, 0, true}, {"count", "int", 1, 2304, true}, {"item", "name", 0, 0, false}, {"radius", "int", 1, 64, false}},
        "craft", new Object[][] {{"item", "name", 0, 0, true}, {"count", "int", 1, 576, true}},
        "rest", new Object[][] {{"until_health", "int", 1, 20, false}, {"wait_seconds", "int", 1, 60, false}, {"max_waits", "int", 1, 120, false}},
        "flee", new Object[][] {{"distance", "int", 8, 64, false}, {"max_legs", "int", 1, 10, false}});
    /** How long each immediate action may take, in seconds, before it fails. */
    private static final Map<String, Integer> ACTION_SECONDS = Map.of("goto", 120, "mine", 180, "craft", 60, "place", 30, "open", 30, "deposit", 30,
        "withdraw", 30, "attack", 30, "eat", 20, "sleep", 40);
    private static final Pattern LOWER_NAME = Pattern.compile("^[a-z0-9_]{1,64}$"), PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern MISSING = Pattern.compile("^missing ([a-z_]+(?:, [a-z_]+){0,7})$");
    private static final Set<String> SEEDS = Set.of("wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds");
    private static final int BORDER_MARGIN = 16;
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
        String goal, program;
        Task task;
        int maxTicks, maxOps, opsAtStart;
        long startTick, startedMs;
        Set<String> wakeOn = new HashSet<>();
        List<String> preconditions = new ArrayList<>();
        /** Where a travel is headed, when that is a fixed place: a blocked walk there gets one detour. */
        BlockPos travelGoal;
        boolean detourTried, awaitingDetour;
    }

    /** The immediate actions of one thought, with the intent and the words that follow them. */
    private static final class Batch {
        final Deque<Task> queue = new ArrayDeque<>();
        Task current;
        Intent then;
        String say;
    }

    private Batch batch;
    private Intent intent;
    private final Reflexes reflexes = new Reflexes();
    private boolean dangerWake;

    // ---- reading a thought ----

    private static String str(JsonObject o, String key) throws Rejected {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) throw new Rejected("schema");
        return o.get(key).getAsString();
    }

    private static boolean whole(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
        try {
            return e.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException x) {
            return false;
        }
    }

    private static int num(JsonObject o, String key, int min, int max) throws Rejected {
        if (!whole(o.get(key))) throw new Rejected("schema");
        long n = o.get(key).getAsBigDecimal().longValue();
        if (n < min || n > max) throw new Rejected("out_of_bounds");
        return (int) n;
    }

    private static int opt(JsonObject o, String key, int min, int max, int fallback) throws Rejected {
        return o.has(key) ? num(o, key, min, max) : fallback;
    }

    /** A position inside the world: within the border and a little past it, between the lowest and highest block. */
    private static BlockPos pos(JsonElement e, int border) throws Rejected {
        if (e == null || !e.isJsonObject()) throw new Rejected("schema");
        JsonObject o = e.getAsJsonObject();
        for (String key : o.keySet()) if (!key.equals("x") && !key.equals("y") && !key.equals("z")) throw new Rejected("schema");
        int reach = border > 0 ? border + BORDER_MARGIN : 30_000_000;
        return new BlockPos(num(o, "x", -reach, reach), num(o, "y", -64, 320), num(o, "z", -reach, reach));
    }

    /** A walk to a position, or to where the named agent stands now. */
    private static Task walkTo(JsonElement target, int border) throws Rejected {
        if (target != null && target.isJsonPrimitive()) {
            String name = target.getAsString();
            return new ToPlayer(name);
        }
        return new Tasks.Walk(pos(target, border), 1);
    }

    /** A walk up to where a named agent stood when it began. */
    private static final class ToPlayer implements Task {
        private final String name;
        private Tasks.Walk walk;
        BlockPos at;

        ToPlayer(String name) { this.name = name; }

        @Override public Outcome tick(Body b) {
            if (walk == null) {
                Player p = b.level().getServer().getPlayerList().getPlayerByName(name);
                if (p == null || p.level() != b.level() || p.distanceTo(b) > 96) return Outcome.fail("not_found", "no player " + name + " in view");
                at = p.blockPosition();
                walk = new Tasks.Walk(at, 2);
            }
            return walk.tick(b);
        }

        @Override public void stop(Body b) { b.halt(); }
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
    public static Task action(JsonObject a, Mind mind, GoldarConfig config) throws Rejected {
        String verb = str(a, "do");
        if (!VERBS.contains(verb) && !(config.extra_actions && EXTRA_VERBS.contains(verb)) && !Colonies.verbs().contains(verb)) throw new Rejected("not_allowed");
        int border = config.world_border_radius;
        Task task = switch (verb) {
            case "hire", "dismiss" -> Tasks.within(Colonies.manage(verb, pos(a.get("at"), border), str(a, "citizen"), null), 600, "timeout", verb + " took too long");
            case "set" -> Tasks.within(Colonies.manage(verb, pos(a.get("at"), border), str(a, "setting"), str(a, "value")), 600, "timeout", verb + " took too long");
            case "goto" -> walkTo(a.get("target"), border);
            case "mine" -> new Tasks.Mine(str(a, "block"), num(a, "count", 1, 256));
            case "craft" -> new Crafting.Craft(str(a, "item"), num(a, "count", 1, 64));
            case "place" -> new Tasks.Place(str(a, "block"), a.has("at") && a.get("at").isJsonPrimitive() ? null : pos(a.get("at"), border));
            case "attack" -> new Tasks.Attack(str(a, "target"));
            case "eat" -> new Tasks.Eat();
            case "sleep" -> new Tasks.Sleep();
            case "give" -> new Tasks.Give(str(a, "to"), str(a, "item"), num(a, "count", 1, 64));
            case "wait" -> new Tasks.Wait(num(a, "seconds", 1, 600));
            case "open" -> new Tasks.Transfer(Tasks.Transfer.Kind.OPEN, pos(a.get("at"), border), null, 0);
            case "deposit" -> new Tasks.Transfer(Tasks.Transfer.Kind.DEPOSIT, pos(a.get("at"), border), str(a, "item"), num(a, "count", 1, 64));
            case "withdraw" -> new Tasks.Transfer(Tasks.Transfer.Kind.WITHDRAW, pos(a.get("at"), border), str(a, "item"), num(a, "count", 1, 64));
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
            default -> throw new Rejected("not_allowed");
        };
        Integer seconds = ACTION_SECONDS.get(verb);
        return seconds == null ? task : Tasks.within(task, seconds * 20, "timeout", verb + " took too long");
    }

    /** Checks a program's arguments against its shape and caps, as Geldar does before a plan reaches the body. */
    private static void checkArguments(String program, JsonObject args, int border) throws Rejected {
        Object[][] spec = PROGRAM_ARGS.get(program);
        if (spec == null) return;
        String schema = null, bounds = null;
        for (String key : args.keySet()) {
            boolean known = false;
            for (Object[] rule : spec) known |= rule[0].equals(key);
            if (!known) schema = "schema";
        }
        for (Object[] rule : spec) {
            JsonElement v = args.get((String) rule[0]);
            if (v == null) { if ((Boolean) rule[4]) schema = "schema"; continue; }
            switch ((String) rule[1]) {
                case "int" -> {
                    if (!whole(v)) schema = "schema";
                    else {
                        long n = v.getAsBigDecimal().longValue();
                        if (n < (Integer) rule[2] || n > (Integer) rule[3]) bounds = "out_of_bounds";
                    }
                }
                case "name" -> { if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || !LOWER_NAME.matcher(v.getAsString()).matches()) schema = "schema"; }
                default -> {
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                        if (!PLAYER_NAME.matcher(v.getAsString()).matches()) schema = "schema";
                    } else {
                        try { pos(v, border); } catch (Rejected e) { if (e.reason.equals("schema")) schema = "schema"; else bounds = e.reason; }
                    }
                }
            }
        }
        if (schema != null) throw new Rejected(schema);
        if (bounds != null) throw new Rejected(bounds);
    }

    /** Checks an intent and builds its program. Throws when it is refused. */
    private static Intent intent(JsonObject o, GoldarConfig config) throws Rejected {
        Intent i = new Intent();
        i.goal = str(o, "goal");
        i.program = str(o, "program");
        boolean skill = config.skills && Skills.known(i.program) && !PROGRAMS.contains(i.program);
        if (!PROGRAMS.contains(i.program) && !(config.extra_actions && EXTRA_PROGRAMS.contains(i.program)) && !skill) throw new Rejected("not_allowed");
        JsonObject args = o.getAsJsonObject("arguments"), limits = o.getAsJsonObject("limits");
        int border = config.world_border_radius;
        checkArguments(i.program, args, border);
        i.maxTicks = num(limits, "max_world_ticks", 1, 240_000);
        i.maxOps = num(limits, "max_block_operations", 1, 10_000);
        for (JsonElement w : o.getAsJsonArray("wake_on")) i.wakeOn.add(w.getAsString());
        if (o.has("preconditions") && o.get("preconditions").isJsonArray()) for (JsonElement p : o.getAsJsonArray("preconditions")) i.preconditions.add(p.getAsString());
        if (skill) {
            i.task = Skills.program(i.program, args, border);
            return i;
        }
        i.task = switch (i.program) {
            case "travel" -> {
                JsonElement target = args.get("target");
                if (target != null && target.isJsonObject()) i.travelGoal = pos(target, border);
                yield walkTo(target, border);
            }
            case "gather" -> {
                String block = str(args, "block");
                String item = args.has("item") ? str(args, "item") : DROPS.getOrDefault(block, block);
                int count = num(args, "count", 1, 2304);
                int radius = opt(args, "radius", 1, 64, 32);
                yield new Task() {
                    Tasks.Mine mine;

                    @Override public Outcome tick(Body b) {
                        if (mine == null) mine = new Tasks.Mine(block, count, radius, item, b.count(item) + count);
                        return mine.tick(b);
                    }

                    @Override public void stop(Body b) { b.halt(); }
                };
            }
            case "craft" -> new Crafting.Craft(str(args, "item"), num(args, "count", 1, 576));
            case "rest" -> new Rest(opt(args, "until_health", 1, 20, 20), opt(args, "wait_seconds", 1, 60, 5), opt(args, "max_waits", 1, 120, 30));
            case "flee" -> new Flee(opt(args, "distance", 8, 64, 24), opt(args, "max_legs", 1, 10, 4));
            case "build" -> build(args.get("blueprint"));
            default -> new Follow(str(args, "target"), opt(args, "distance", 1, 16, 3));
        };
        return i;
    }

    /** Builds an intent without installing it, so a bad one is refused before anything is charged. */
    public static Object readIntent(JsonObject o, GoldarConfig config) throws Rejected { return intent(o, config); }

    // ---- installing ----

    /**
     * Takes a thought the world accepted: its actions run first, then its intent takes the old one's place, then its
     * words are spoken. Any of the three may be absent. Server thread only.
     */
    public void submit(Mind mind, Body b, long tick, List<Task> tasks, Object built, String say) {
        Intent next = (Intent) built;
        if (tasks.isEmpty()) {
            if (next != null) startIntent(mind, b, tick, next);
            if (say != null) Goldar.world().voice(b, say);
            return;
        }
        if (batch != null) {
            if (intent == null) {
                // The body is busy with earlier actions and has no intent to set aside: the new ones fail at the first.
                if (next == null) mind.intentFinished(tick, "failed", null);
                if (say != null) Goldar.world().voice(b, say);
                return;
            }
            if (batch.current != null) batch.current.stop(b);
        }
        batch = new Batch();
        batch.queue.addAll(tasks);
        batch.then = next;
        batch.say = say;
    }

    private static boolean holds(String precondition, Body b) {
        return switch (precondition) {
            case "seeds_available" -> SEEDS.stream().anyMatch(s -> b.count(s) > 0);
            case "not_in_fluid" -> !b.isInWater() && !b.isInLava();
            case "not_on_fire" -> !b.isOnFire();
            case "no_hostiles_nearby" -> b.level().getEntities(b, b.getBoundingBox().inflate(16), e -> e instanceof Enemy && e.isAlive()).isEmpty();
            case "health_above_half" -> b.getHealth() > 10;
            case "not_hungry" -> b.getFoodData().getFoodLevel() > 14;
            default -> false;
        };
    }

    private void startIntent(Mind mind, Body b, long tick, Intent next) {
        // The intent it replaces is over, and its mind hears so.
        if (intent != null) failIntent(mind, b, tick, null);
        for (String p : next.preconditions) {
            if (!holds(p, b)) { mind.intentFinished(tick, "failed", null); return; }
        }
        intent = next;
        intent.opsAtStart = b.blockOperations;
        intent.startTick = tick;
        intent.startedMs = System.currentTimeMillis();
        reflexes.blocked = null;
        reflexes.detour = null;
    }

    /** Drops everything without a word: the body is leaving the world. */
    public void clear(Body b) {
        if (b != null) {
            if (batch != null && batch.current != null) batch.current.stop(b);
            if (intent != null && intent.task != null) intent.task.stop(b);
            reflexes.stop(b);
        }
        batch = null;
        intent = null;
    }

    /** True when nothing is underway. */
    public boolean idle() { return batch == null && intent == null; }

    /** What the running intent asked to be woken by, or null when no intent runs. */
    public Set<String> wakeOn() { Intent i = intent; return i == null ? null : i.wakeOn; }

    /** When the running intent began, in wall-clock milliseconds, or -1 when none runs. */
    public long intentStartedMs() { Intent i = intent; return i == null ? -1 : i.startedMs; }

    /** The snapshot's `current_plan`: the intent, and whether it runs or waits on actions or a reflex. Null with no intent. */
    public JsonObject current() {
        Intent i = intent;
        if (i == null) return null;
        return J.obj("goal", i.goal, "action", i.program, "status", batch != null || reflexes.busy() ? "paused" : "running");
    }

    /**
     * Something the intent asked to be woken by has happened: the intent ends, and its mind hears that it failed.
     * Server thread only.
     */
    public boolean interrupt(Mind mind, Body b, long tick, String condition) {
        if (intent == null || !intent.wakeOn.contains(condition)) return false;
        failIntent(mind, b, tick, null);
        return true;
    }

    // ---- running ----

    /** A failure as one of the plain facts the world tells a mind, or null when it has none for it. */
    private static String why(Outcome o) {
        String reason = o.reason() == null ? "" : o.reason();
        if (reason.equals("missing_materials")) {
            Matcher m = MISSING.matcher(o.message() == null ? "" : o.message());
            return m.matches() ? "you lack " + m.group(1).replace('_', ' ') : null;
        }
        if (reason.equals("missing_station")) return "no crafting table within reach";
        if (reason.equals("blocked")) return "the way was blocked";
        if (reason.equals("out_of_reach") || reason.equals("too_far")) return "too far from the block";
        return null;
    }

    private void endIntent(Mind mind, Body b, long tick, String outcome, String reason) {
        if (intent.task != null) intent.task.stop(b);
        intent = null;
        reflexes.blocked = null;
        mind.intentFinished(tick, outcome, reason);
    }

    private void failIntent(Mind mind, Body b, long tick, String reason) { endIntent(mind, b, tick, "failed", reason); }

    /** The actions of a thought are over. Alone, they are reported as a small intent that finished or failed. */
    private void endBatch(Mind mind, Body b, long tick, boolean ok) {
        Batch done = batch;
        batch = null;
        if (done.then == null) mind.intentFinished(tick, ok ? "completed" : "failed", null);
        else if (ok) startIntent(mind, b, tick, done.then);
        if (done.say != null) Goldar.world().voice(b, done.say);
    }

    public void tick(Mind mind, Body b, long tick) {
        // Reflexes first. One that takes over drops the actions in hand and pauses the intent.
        if (reflexes.begin(b, tick)) {
            if (batch != null) {
                if (batch.current != null) batch.current.stop(b);
                endBatch(mind, b, tick, false);
            }
            if (intent != null && intent.task != null) intent.task.stop(b);
        }
        if (reflexes.busy()) {
            Reflexes.Fired fired = reflexes.tick(b);
            if (fired == null) return;
            Goldar.world().onReflex(mind, fired);
            if (fired.name().equals("eat") && fired.result().equals(Reflexes.HANDLED)) mind.simple(tick, "reflex");
            if ("reflex_danger".equals(fired.wake())) {
                // The world reports a reflex's alarm to the mind as harm of no size.
                mind.damage(tick, "0");
                dangerWake = true;
            }
            if (reflexes.busy()) return;
        }
        if (dangerWake) {
            dangerWake = false;
            if (intent != null) { failIntent(mind, b, tick, null); return; }
        }
        if (batch != null) {
            if (batch.current == null) batch.current = batch.queue.poll();
            if (batch.current == null) { endBatch(mind, b, tick, true); return; }
            Outcome o = batch.current.tick(b);
            if (o == null) return;
            batch.current = null;
            if (!o.ok()) Goldar.LOGGER.info("{}: an action failed ({}): {}", mind.seat.id, o.reason(), o.message());
            // The steps after a failed one were planned on its success.
            if (!o.ok()) endBatch(mind, b, tick, false);
            else if (batch.queue.isEmpty()) endBatch(mind, b, tick, true);
            return;
        }
        if (intent == null || b.isSleeping()) return;
        if (intent.awaitingDetour) {
            if (reflexes.detour == null) return;
            boolean reached = reflexes.detour;
            reflexes.detour = null;
            intent.awaitingDetour = false;
            if (reached) endIntent(mind, b, tick, "completed", null);
            else failIntent(mind, b, tick, "the way was blocked");
            return;
        }
        if (tick - intent.startTick >= intent.maxTicks || b.blockOperations - intent.opsAtStart >= intent.maxOps) {
            endIntent(mind, b, tick, "limit_reached", null);
            return;
        }
        if (tick % 20 == 0 && intent.wakeOn.contains("precondition_failed")) {
            for (String p : intent.preconditions) if (!holds(p, b)) { failIntent(mind, b, tick, null); return; }
        }
        Outcome o = intent.task.tick(b);
        if (o == null) return;
        if (o.ok()) { endIntent(mind, b, tick, "completed", null); return; }
        if (intent.travelGoal == null && intent.task instanceof ToPlayer to) intent.travelGoal = to.at;
        if ("blocked".equals(o.reason()) && intent.travelGoal != null && !intent.detourTried) {
            // A blocked walk is handed to the body, which tries one way round before the mind hears of it.
            intent.detourTried = true;
            intent.awaitingDetour = true;
            reflexes.detour = null;
            reflexes.blocked = new Reflexes.Blocked(intent.travelGoal, 1, tick, o.message() == null ? "no way there" : o.message());
            return;
        }
        Goldar.LOGGER.info("{}: the intent {} failed ({}): {}", mind.seat.id, intent.program, o.reason(), o.message());
        failIntent(mind, b, tick, why(o));
    }

    // ---- programs with no action of their own ----

    /** Waits for health to come back, sleeping first if it can. Done at once when health is already there. */
    private static final class Rest implements Task {
        private final int untilHealth, waitTicks, maxWaits;
        private Tasks.Sleep sleep = new Tasks.Sleep();
        private int waits, ticks;

        Rest(int untilHealth, int waitSeconds, int maxWaits) { this.untilHealth = untilHealth; this.waitTicks = waitSeconds * 20; this.maxWaits = maxWaits; }

        @Override public Outcome tick(Body b) {
            if (b.getHealth() >= untilHealth) { if (sleep != null) sleep.stop(b); b.halt(); return Outcome.OK; }
            if (sleep != null) {
                Outcome o = sleep.tick(b);
                if (o == null) return null;
                sleep = null;
            }
            b.halt();
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
