package dev.goldar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.goldar.Tasks.Outcome;
import dev.goldar.Tasks.Task;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Skills a body knows from birth when the world's `skills` switch is on: the survival routines of minecraft-player,
 * written as intent programs. A mind names one as an intent's program, as the contract allows ("a built-in program,
 * or the name of a skill"). Each is made of the body's own tasks, so it mines, crafts and fights by the same rules as
 * any action, and the reflexes keep it fed, armed and sheltered while it runs.
 */
final class Skills {
    private Skills() {}

    /** Each skill's arguments, as the contract describes them to a mind. */
    static final Map<String, String> ARGS = Map.of(
        "chop", "count (logs, default 8), radius (default 48)",
        "bootstrap", "none: logs, planks, sticks, a crafting table, wooden pickaxe and sword, dirt, then a stone pickaxe",
        "strip_mine", "length (default 32), y (dig down to this level first), dir (x, -x, z or -z, default x), stay (true to stay underground); takes ores, places torches, then climbs back up",
        "smelt", "item (what to make, such as iron_ingot), count; places a furnace when none is near",
        "cook", "none: cooks all carried raw meat, fish and potatoes",
        "gather_food", "kills (default 4), radius (default 32): hunts farm animals",
        "shelter", "none: a bed if one is near, else walls of carried dirt or stone until morning",
        "store", "at (a chest position, default the nearest), items (list of names, default bulk blocks)",
        "plan", "rounds (default 30), mine_y (default 16): from nothing to an iron pickaxe and iron armor");

    static boolean known(String name) { return ARGS.containsKey(name); }

    /** What the contract's description of `act` gains when skills are on. */
    static String note() {
        StringBuilder sb = new StringBuilder(" Skills you know: start one with use_skill {name, args}, or as an intent's program with these arguments. A skill tells you how it ended, as speech from skill:");
        for (String name : List.of("plan", "bootstrap", "chop", "strip_mine", "smelt", "cook", "gather_food", "shelter", "store")) {
            sb.append(' ').append(name).append(" (").append(ARGS.get(name)).append(");");
        }
        return sb.toString();
    }

    // ---- reading arguments ----

    private static int num(JsonObject args, String key, int min, int max, int fallback) throws Plan.Rejected {
        JsonElement v = args.get(key);
        if (v == null) return fallback;
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new Plan.Rejected("schema");
        long n = v.getAsLong();
        if (n < min || n > max) throw new Plan.Rejected("out_of_bounds");
        return (int) n;
    }

    private static String name(JsonObject args, String key, String fallback) throws Plan.Rejected {
        JsonElement v = args.get(key);
        if (v == null) return fallback;
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || !v.getAsString().matches("^-?[a-z0-9_]{1,64}$")) throw new Plan.Rejected("schema");
        return v.getAsString();
    }

    private static void only(JsonObject args, String... keys) throws Plan.Rejected {
        Set<String> allowed = Set.of(keys);
        for (String k : args.keySet()) if (!allowed.contains(k)) throw new Plan.Rejected("schema");
    }

    /** The task for a skill, its arguments checked first. */
    static Task program(String skill, JsonObject args, int border) throws Plan.Rejected {
        return switch (skill) {
            case "chop" -> { only(args, "count", "radius"); yield new Chop(num(args, "count", 1, 256, 8), num(args, "radius", 8, 64, 48)); }
            case "bootstrap" -> { only(args); yield bootstrap(); }
            case "strip_mine" -> {
                only(args, "length", "y", "dir", "stay");
                String dir = name(args, "dir", "x");
                if (!Set.of("x", "-x", "z", "-z").contains(dir)) throw new Plan.Rejected("out_of_bounds");
                Task tunnel = new StripMine(num(args, "length", 1, 128, 32), args.has("y") ? num(args, "y", -60, 320, 0) : null, dir);
                boolean stay = args.has("stay") && args.get("stay").isJsonPrimitive() && args.get("stay").getAsBoolean();
                // Back up to the surface afterwards unless told to stay: a body left at the end of a tunnel is easily shut in.
                yield stay ? tunnel : thenSurface(tunnel);
            }
            case "smelt" -> { only(args, "item", "count"); String item = name(args, "item", null); if (item == null) throw new Plan.Rejected("schema"); yield smelt(item, num(args, "count", 1, 64, 8)); }
            case "cook" -> { only(args); yield cook(); }
            case "gather_food" -> { only(args, "kills", "radius"); yield new GatherFood(num(args, "kills", 1, 16, 4), num(args, "radius", 8, 64, 32)); }
            case "shelter" -> { only(args); yield new Shelter(); }
            case "store" -> {
                only(args, "at", "items");
                BlockPos at = null;
                if (args.has("at")) {
                    if (!args.get("at").isJsonObject()) throw new Plan.Rejected("schema");
                    JsonObject p = args.getAsJsonObject("at");
                    if (!p.has("x") || !p.has("y") || !p.has("z") || p.size() != 3) throw new Plan.Rejected("schema");
                    at = new BlockPos(num(p, "x", -30_000_000, 30_000_000, 0), num(p, "y", -64, 320, 0), num(p, "z", -30_000_000, 30_000_000, 0));
                }
                List<String> items = new ArrayList<>(BULK);
                if (args.has("items")) {
                    if (!args.get("items").isJsonArray() || args.getAsJsonArray("items").size() > 32) throw new Plan.Rejected("schema");
                    items.clear();
                    for (JsonElement e : args.getAsJsonArray("items")) {
                        if (!e.isJsonPrimitive() || !e.getAsString().matches("^[a-z0-9_*]{1,64}$")) throw new Plan.Rejected("schema");
                        items.add(e.getAsString());
                    }
                }
                yield store(at, items);
            }
            case "plan" -> { only(args, "rounds", "mine_y"); yield new Progress(num(args, "rounds", 1, 200, 30), args.has("mine_y") ? num(args, "mine_y", -60, 120, 16) : null); }
            default -> throw new Plan.Rejected("not_allowed");
        };
    }

    // ---- the inventory ----

    private static boolean matches(String glob, String item) {
        if (!glob.contains("*")) return glob.equals(item);
        return item.matches("^" + glob.replace("*", "[a-z0-9_]*") + "$");
    }

    /** How many of the items a pattern such as `*_log` names are carried. */
    static int count(Body b, String glob) {
        int n = 0;
        for (ItemStack s : Compat.items(b.getInventory())) if (!s.isEmpty() && matches(glob, Body.name(s))) n += s.getCount();
        return n;
    }

    static String first(Body b, String glob) {
        for (ItemStack s : Compat.items(b.getInventory())) if (!s.isEmpty() && matches(glob, Body.name(s))) return Body.name(s);
        return null;
    }

    static boolean has(Body b, String item, int n) { return count(b, item) >= n; }

    /** A tool the body will still use: one on its last point would break at the next block. */
    static boolean usable(Body b, String item) {
        for (ItemStack s : Compat.items(b.getInventory())) {
            if (!s.isEmpty() && Body.name(s).equals(item) && (!s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() > 1)) return true;
        }
        return false;
    }

    static boolean anyPickaxe(Body b) { return usable(b, "wooden_pickaxe") || usable(b, "stone_pickaxe") || usable(b, "iron_pickaxe") || usable(b, "diamond_pickaxe"); }

    static boolean betterPickaxe(Body b) { return usable(b, "stone_pickaxe") || usable(b, "iron_pickaxe") || usable(b, "diamond_pickaxe"); }

    private static final List<String> BULK = List.of("cobblestone", "cobbled_deepslate", "dirt", "gravel", "andesite", "diorite", "granite", "tuff");
    private static final List<String> FUELS = List.of("coal", "charcoal", "*_planks", "*_log");
    private static final Map<String, String> COOKED = Map.of("beef", "cooked_beef", "porkchop", "cooked_porkchop", "mutton", "cooked_mutton",
        "chicken", "cooked_chicken", "rabbit", "cooked_rabbit", "cod", "cooked_cod", "salmon", "cooked_salmon", "potato", "baked_potato");
    private static final List<String> FOODS = List.of("cooked_*", "baked_potato", "bread", "apple", "carrot", "beef", "porkchop", "mutton", "chicken", "rabbit");

    static boolean hasFuel(Body b) {
        for (String f : FUELS) if (count(b, f) > 0) return true;
        return false;
    }

    static int food(Body b) {
        int n = 0;
        for (String f : FOODS) n += count(b, f);
        return n;
    }

    static int raw(Body b) {
        int n = 0;
        for (String f : COOKED.keySet()) n += count(b, f);
        return n;
    }

    static boolean daytime(Body b) { return Math.floorMod(Compat.dayTime(b.level()), 24000L) < 11500; }

    // ---- composing tasks ----

    /** A task decided when it starts, from the world as it is then. Null from `make` means nothing to do. */
    static Task lazy(Function<Body, Task> make) {
        return new Task() {
            Task t;
            boolean made;

            @Override public Outcome tick(Body b) {
                if (!made) { made = true; t = make.apply(b); }
                return t == null ? Outcome.OK : t.tick(b);
            }

            @Override public void stop(Body b) { if (t != null) t.stop(b); else b.halt(); }
        };
    }

    /** Runs `make` only when `need` holds as it starts. */
    static Task when(Predicate<Body> need, Function<Body, Task> make) { return lazy(b -> need.test(b) ? make.apply(b) : null); }

    static Task seq(Task... steps) { return Tasks.seq(List.of(steps)); }

    static Task fail(String reason, String message) { return b -> Outcome.fail(reason, message); }

    // ---- places ----

    /** The nearest block within `radius` whose name passes `want`, or null. */
    static BlockPos nearest(Body b, int radius, Predicate<String> want) {
        ServerLevel level = b.level();
        return BlockPos.findClosestMatch(b.blockPosition(), radius, Math.min(radius, 16), p -> want.test(Body.name(level.getBlockState(p))))
            .map(BlockPos::immutable).orElse(null);
    }

    private static boolean empty(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
    }

    /** An empty cell near the body, on solid ground that is not a workstation, with room above: where to set a table or furnace. */
    static BlockPos freeSpot(Body b) {
        ServerLevel level = b.level();
        BlockPos feet = b.blockPosition();
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 1; dy >= -2; dy--) {
                        BlockPos p = feet.offset(dx, dy, dz);
                        BlockPos under = p.below();
                        String below = Body.name(level.getBlockState(under));
                        if (empty(level, p) && empty(level, p.above()) && !level.getBlockState(under).getCollisionShape(level, under).isEmpty()
                            && level.getFluidState(under).isEmpty() && !below.endsWith("crafting_table") && !below.endsWith("furnace") && !below.endsWith("chest")
                            && !below.endsWith("_bed") && Tasks.inSight(b, p)) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Places a carried block at a free spot beside the body. */
    static Task placeNear(String item) {
        return lazy(b -> {
            BlockPos at = freeSpot(b);
            return at == null ? fail("blocked", "no free spot for the " + item) : new Tasks.Place(item, at);
        });
    }

    // ---- crafting ----

    static Task craft(String item, int count) { return new Crafting.Craft(item, count); }

    static String plankOf(String log) { return log.replace("stripped_", "").replace("_log", "_planks").replace("_stem", "_planks"); }

    /** Turns logs into planks when fewer than `need` are carried. Quiet when there are no logs: the craft that needs them says so. */
    static Task ensurePlanks(int need) {
        return when(b -> count(b, "*_planks") < need && first(b, "*_log") != null,
            b -> craft(plankOf(first(b, "*_log")), Math.min(count(b, "*_log"), (int) Math.ceil(need / 4.0)) * 4));
    }

    static Task ensureSticks(int need) {
        return when(b -> count(b, "stick") < need, b -> seq(ensurePlanks(2), craft("stick", need)));
    }

    /** A crafting table within reach of a short walk: one already near, else one crafted and set down. */
    static Task ensureTable() {
        return when(b -> nearest(b, 6, n -> n.equals("crafting_table")) == null, b -> seq(
            when(x -> count(x, "crafting_table") == 0, x -> seq(ensurePlanks(4), craft("crafting_table", 1))),
            placeNear("crafting_table")));
    }

    static Task atTable(String item, int count) {
        boolean tool = item.endsWith("_pickaxe") || item.endsWith("_sword") || item.endsWith("_axe") || item.endsWith("_shovel") || item.endsWith("_hoe");
        return seq(ensureTable(), tool ? ensureSticks(2) : b -> Outcome.OK, craft(item, count));
    }

    /** A furnace within 24 blocks: one already there, one carried and set down, or one made from 8 cobblestone. */
    static Task ensureFurnace() {
        return when(b -> nearest(b, 24, n -> n.equals("furnace")) == null, b -> {
            if (count(b, "furnace") == 0 && count(b, "cobblestone") < 8) return fail("missing_materials", "missing cobblestone");
            return seq(when(x -> count(x, "furnace") == 0, x -> atTable("furnace", 1)), placeNear("furnace"));
        });
    }

    /** Smelts `count` of an item in batches a furnace takes, setting one down first if needed. */
    static Task smelt(String item, int count) {
        List<Task> steps = new ArrayList<>();
        steps.add(ensureFurnace());
        for (int left = count; left > 0; left -= 16) steps.add(new Crafting.Smelt(item, Math.min(16, left)));
        return Tasks.seq(steps);
    }

    static Task cook() {
        return lazy(b -> {
            List<Task> steps = new ArrayList<>();
            for (var e : COOKED.entrySet()) {
                int n = count(b, e.getKey());
                if (n > 0) steps.add(smelt(e.getValue(), n));
            }
            if (steps.isEmpty()) return fail("missing_materials", "missing raw food");
            steps.add(0, ensureFurnace());
            return Tasks.seq(steps);
        });
    }

    /** Puts on a carried piece of armour, as a player does by using it. */
    static Task wear(String item) {
        return b -> {
            int slot = b.slotOf(item);
            if (slot < 0) return Outcome.fail("missing_materials", "missing " + item);
            b.hold(slot);
            b.gameMode.useItem(b, b.level(), b.getMainHandItem(), InteractionHand.MAIN_HAND);
            return b.slotOf(item) < 0 ? Outcome.OK : Outcome.fail("not_possible", "could not put on the " + item);
        };
    }

    static Task store(BlockPos at, List<String> items) {
        return lazy(b -> {
            BlockPos chest = at != null ? at : nearest(b, 24, n -> n.equals("chest") || n.equals("barrel") || n.equals("trapped_chest"));
            if (chest == null) return fail("not_found", "no chest within 24 blocks");
            List<Task> steps = new ArrayList<>();
            for (String glob : items) {
                Map<String, Integer> carried = new HashMap<>();
                for (ItemStack s : Compat.items(b.getInventory())) if (!s.isEmpty() && matches(glob, Body.name(s))) carried.merge(Body.name(s), s.getCount(), Integer::sum);
                for (var e : carried.entrySet()) {
                    for (int left = e.getValue(); left > 0; left -= 64) steps.add(new Tasks.Transfer(Tasks.Transfer.Kind.DEPOSIT, chest, e.getKey(), Math.min(64, left)));
                }
            }
            return steps.isEmpty() ? null : Tasks.seq(steps);
        });
    }

    // ---- walking somewhere new ----

    private static final int SCOUT_STEP = 70;
    private static final int[][] SCOUT_LEGS = {{1, 0}, {0, 1}, {-1, 0}, {-1, 0}, {0, -1}, {0, -1}, {1, 0}, {1, 0}, {1, 0}, {0, 1}, {0, 1}, {0, 1}};

    /** One leg of a widening square, to the ground there: for when nothing useful is in sight. Null once the search is used up. */
    static Task scout(Body b, int leg) {
        if (leg >= SCOUT_LEGS.length) return null;
        BlockPos from = b.blockPosition();
        BlockPos column = from.offset(SCOUT_LEGS[leg][0] * SCOUT_STEP, 0, SCOUT_LEGS[leg][1] * SCOUT_STEP);
        BlockPos ground = b.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        // Walk if there is a way; dig one if the body is shut in.
        return orElse(Tasks.within(new Tasks.Walk(ground, 10), 20 * 90, "timeout", "the search walk took too long"),
            x -> Tasks.within(Tasks.Walk.digging(ground, 10), 20 * 180, "timeout", "the search walk took too long"));
    }

    /** Runs `first`; if it fails, runs what `then` makes instead. */
    static Task orElse(Task first, Function<Body, Task> then) {
        return new Task() {
            Task second;

            @Override public Outcome tick(Body b) {
                if (second != null) return second.tick(b);
                Outcome o = first.tick(b);
                if (o == null || o.ok()) return o;
                second = then.apply(b);
                return null;
            }

            @Override public void stop(Body b) { (second != null ? second : first).stop(b); }
        };
    }

    /** Climbs to open sky above the body, digging a staircase if it must, in stages a walk can plan. Done at once on the surface. */
    static Task surface() {
        return new Task() {
            int stages, turns;
            Task doing;

            private int top(Body b) { return b.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, b.blockPosition()).getY(); }

            boolean aside;

            @Override public Outcome tick(Body b) {
                int[][] ways = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
                if (doing != null) {
                    Outcome o = doing.tick(b);
                    if (o == null) return null;
                    doing = null;
                    if (!o.ok() && !aside) {
                        turns++;
                        if (turns >= 12) return Outcome.fail(o.reason(), "could not climb to the surface: " + o.message());
                        // Every way up from here met water or lava: move along underground, away from it, and climb from there.
                        if (turns % 4 == 0) {
                            int[] w = ways[(turns / 4) % 4];
                            aside = true;
                            doing = Tasks.within(Tasks.Walk.diggingWet(b.blockPosition().offset(w[0] * 10, 0, w[1] * 10), 2), 20 * 120, "timeout", "the climb took too long");
                            return null;
                        }
                    }
                    aside = false;
                }
                int rise = top(b) - b.getBlockY();
                if (rise <= 1) return Outcome.OK;
                if (stages++ >= 30) return Outcome.fail("blocked", "could not climb to the surface");
                int up = Math.min(rise, 8);
                int[] w = ways[turns % 4];
                BlockPos to = b.blockPosition().offset(w[0] * up, up, w[1] * up);
                // From water, or once the dry ways up have failed, the climb may dig beside water to get out.
                boolean wet = b.isInWater() || turns >= 4;
                doing = Tasks.within(wet ? Tasks.Walk.diggingWet(to, 1) : Tasks.Walk.digging(to, 1), 20 * 120, "timeout", "the climb took too long");
                return null;
            }

            @Override public void stop(Body b) { if (doing != null) doing.stop(b); else b.halt(); }
        };
    }

    /** Runs `task`, then climbs to the surface whatever its outcome, and reports the task's outcome. */
    static Task thenSurface(Task task) {
        return new Task() {
            Outcome result;
            Task climb;

            @Override public Outcome tick(Body b) {
                if (result == null) {
                    Outcome o = task.tick(b);
                    if (o == null) return null;
                    result = o;
                    climb = surface();
                }
                Outcome c = climb.tick(b);
                if (c == null) return null;
                return result.ok() && !c.ok() ? Outcome.fail(c.reason(), "the tunnel is done, but " + c.message()) : result;
            }

            @Override public void stop(Body b) { (result == null ? task : climb).stop(b); }
        };
    }

    /** Walks over dropped items near the body. Never digs to reach one. */
    static final class Collect implements Task {
        private final int radius;
        private int trips;
        private Tasks.Walk walk;
        private int ticks;

        Collect(int radius, int trips) { this.radius = radius; this.trips = trips; }

        @Override public Outcome tick(Body b) {
            if (walk != null) {
                Outcome o = walk.tick(b);
                if (o == null && ++ticks < 100) return null;
                walk = null;
            }
            if (trips-- <= 0) return Outcome.OK;
            ItemEntity near = null;
            for (ItemEntity e : b.level().getEntitiesOfClass(ItemEntity.class, b.getBoundingBox().inflate(radius))) {
                if (near == null || e.distanceToSqr(b) < near.distanceToSqr(b)) near = e;
            }
            if (near == null) return Outcome.OK;
            walk = new Tasks.Walk(near.blockPosition(), 0);
            ticks = 0;
            return null;
        }
    }

    // ---- chop ----

    /**
     * True when a body standing on the ground beside the block could reach it. A trunk whose lower logs were cut
     * hangs out of reach, and a body cannot build its way up to it.
     */
    static boolean withinReachOfGround(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.relative(d)).getY();
            if (p.getY() - ground <= 4 && p.getY() >= ground - 3) return true;
        }
        return false;
    }

    /** Mines the nearest logs until `want` more are carried, searching further out when no tree is in range. */
    static final class Chop implements Task {
        private final int want, radius;
        private int start = -1, failures, leg;
        private boolean searching;
        private Task doing;

        Chop(int want, int radius) { this.want = want; this.radius = radius; }

        @Override public Outcome tick(Body b) {
            if (start < 0) start = count(b, "*_log");
            if (doing != null) {
                Outcome o = doing.tick(b);
                if (o == null) return null;
                doing = null;
                if (!o.ok() && !searching) {
                    // The trees here could not be got at: look further out before trying again.
                    failures++;
                    if (count(b, "*_log") - start < want && failures < 4) {
                        searching = true;
                        doing = scout(b, leg++);
                        if (doing != null) return null;
                    }
                }
                searching = false;
            }
            if (count(b, "*_log") - start >= want) return Outcome.OK;
            if (failures >= 4) return Outcome.fail("not_possible", "could not get at the logs nearby");
            BlockPos log = BlockPos.findClosestMatch(b.blockPosition(), radius, Math.min(radius, 16),
                p -> Body.name(b.level().getBlockState(p)).endsWith("_log") && withinReachOfGround(b.level(), p)).orElse(null);
            if (log != null) {
                // One mining task for every kind of log, so a log it cannot get at is passed over rather than tried again.
                doing = new Tasks.Mine("log", n -> n.endsWith("_log"), want - (count(b, "*_log") - start), radius, null, 0).onlyWhere(Skills::withinReachOfGround);
                return null;
            }
            doing = scout(b, leg++);
            return doing == null ? Outcome.fail("not_found", "no trees found after searching") : null;
        }

        @Override public void stop(Body b) { if (doing != null) doing.stop(b); else b.halt(); }
    }

    // ---- bootstrap ----

    /** The first minutes: logs, planks, sticks, a table, wooden pickaxe and sword, dirt for a night, then a stone pickaxe. */
    static Task bootstrap() {
        return seq(
            when(b -> count(b, "*_log") + count(b, "*_planks") / 4 < 6 && !anyPickaxe(b), b -> new Chop(Math.max(1, 8 - count(b, "*_log")), 48)),
            when(b -> count(b, "*_planks") < 12 && first(b, "*_log") != null,
                b -> craft(plankOf(first(b, "*_log")), Math.min(count(b, "*_log"), 4) * 4)),
            ensureSticks(4),
            when(b -> !anyPickaxe(b), b -> atTable("wooden_pickaxe", 1)),
            when(b -> count(b, "*_sword") == 0, b -> atTable("wooden_sword", 1)),
            when(b -> count(b, "dirt") < 10, b -> new Tasks.Mine("grass_block", 10, 16, "dirt", 10)),
            when(b -> !betterPickaxe(b) && count(b, "cobblestone") < 3, b -> new Tasks.Mine("stone", 3, 32, "cobblestone", 3)),
            when(b -> !betterPickaxe(b), b -> atTable("stone_pickaxe", 1)));
    }

    // ---- strip_mine ----

    /** Digs a two-high tunnel, optionally from a lower level reached first, taking ores in reach and setting torches. */
    static final class StripMine implements Task {
        private static final int TORCH_EVERY = 8, ORE_RADIUS = 5, LAVA_RADIUS = 6;
        private final int length;
        private final Integer y;
        private final int dx, dz;
        private BlockPos origin;
        private int step, ores, stages, turns, retreats;
        private boolean descended;
        private static final int STAGE = 8, MAX_STAGES = 40;
        private Task doing;
        private Tasks.Dig dig;

        StripMine(int length, Integer y, String dir) {
            this.length = length;
            this.y = y;
            this.dx = dir.equals("x") ? 1 : dir.equals("-x") ? -1 : 0;
            this.dz = dir.equals("z") ? 1 : dir.equals("-z") ? -1 : 0;
        }

        private List<String> wanted(Body b) {
            if (usable(b, "iron_pickaxe") || usable(b, "diamond_pickaxe")) return List.of("_ore");
            return List.of("coal_ore", "copper_ore", "iron_ore", "lapis_ore");
        }

        private boolean lavaNear(Body b) { return nearest(b, LAVA_RADIUS, n -> n.equals("lava")) != null; }

        @Override public Outcome tick(Body b) {
            ServerLevel level = b.level();
            if (doing != null) {
                Outcome o = doing.tick(b);
                if (o == null) return null;
                doing = null;
                // A stage of the descent that found no way (water, lava, bedrock) is tried once more turned aside.
                if (!descended && !o.ok() && turns++ >= 3) return Outcome.fail(o.reason(), "could not dig down to y " + y + ": " + o.message());
            }
            if (!descended) {
                // Down in stages, as a staircase the way the tunnel will go: one long dig is more than a walk can plan.
                if (y != null && y < b.getBlockY() && stages++ < MAX_STAGES) {
                    int drop = Math.max(y - b.getBlockY(), -STAGE);
                    int sx = turns % 2 == 0 ? dx : dz, sz = turns % 2 == 0 ? dz : -dx;
                    if (turns >= 2) { sx = -sx; sz = -sz; }
                    BlockPos down = b.blockPosition().offset(sx * -drop, drop, sz * -drop);
                    doing = Tasks.within(Tasks.Walk.digging(down, 1), 20 * 120, "timeout", "digging down took too long");
                    return null;
                }
                if (y != null && y < b.getBlockY() - 2) return Outcome.fail("blocked", "could not dig down to y " + y);
                descended = true;
            }
            if (origin == null) origin = b.blockPosition();
            if (dig != null) {
                Boolean open = dig.tick(b);
                if (open == null) return null;
                dig = null;
                if (!open) return Outcome.OK;
                return null;
            }
            if (step >= length) return Outcome.OK;
            if (lavaNear(b)) {
                if (step > 0) return Outcome.OK;
                // Lava beside the start would stop the tunnel before its first block whichever way it heads: back away first.
                if (retreats++ >= 2) return Outcome.fail("blocked", "lava nearby");
                BlockPos lava = nearest(b, LAVA_RADIUS, n -> n.equals("lava"));
                BlockPos here = b.blockPosition();
                double ax = here.getX() - lava.getX(), az = here.getZ() - lava.getZ();
                if (ax == 0 && az == 0) { ax = -dx; az = -dz; }
                double len = Math.sqrt(ax * ax + az * az);
                BlockPos away = here.offset((int) Math.round(ax / len * 14), 0, (int) Math.round(az / len * 14));
                doing = quiet(Tasks.within(Tasks.Walk.digging(away, 2), 20 * 90, "timeout", "backing away from lava took too long"));
                origin = null;
                return null;
            }
            if (!anyPickaxe(b)) return step == 0 ? Outcome.fail("missing_materials", "missing a pickaxe") : Outcome.OK;
            BlockPos next = origin.offset(dx * (step + 1), 0, dz * (step + 1));
            for (BlockPos cell : List.of(next.above(), next)) {
                BlockState s = level.getBlockState(cell);
                if (!s.getFluidState().isEmpty()) return Outcome.OK;
                if (!s.getCollisionShape(level, cell).isEmpty()) {
                    for (Direction d : Direction.values()) if (level.getFluidState(cell.relative(d)).is(FluidTags.LAVA)) return Outcome.OK;
                    dig = new Tasks.Dig(cell);
                    return null;
                }
            }
            step++;
            // Into the cell just opened, then whatever ore is in reach, then light behind every few blocks.
            List<Task> after = new ArrayList<>();
            after.add(Tasks.within(new Tasks.Walk(next, 0), 200, "blocked", "could not step into the tunnel"));
            for (String ore : wanted(b)) {
                after.add(lazy(x -> {
                    // "coal_ore" also finds deepslate_coal_ore; "_ore" finds every ore.
                    BlockPos found = nearest(x, ORE_RADIUS, n -> n.endsWith(ore));
                    if (found == null) return null;
                    ores++;
                    String kind = Body.name(x.level().getBlockState(found));
                    return quiet(new Tasks.Mine(kind, 1, ORE_RADIUS, null, 0));
                }));
            }
            if (step % TORCH_EVERY == 0) after.add(when(x -> count(x, "torch") > 0, x -> quiet(new Tasks.Place("torch", next.offset(-dx, 0, -dz)))));
            doing = Tasks.seq(after);
            return null;
        }

        @Override public void stop(Body b) { if (doing != null) doing.stop(b); else b.halt(); }
    }

    /** A step whose failure does not end the skill. */
    static Task quiet(Task t) {
        return new Task() {
            @Override public Outcome tick(Body b) {
                Outcome o = t.tick(b);
                return o == null ? null : Outcome.OK;
            }

            @Override public void stop(Body b) { t.stop(b); }
        };
    }

    // ---- gather_food ----

    /** Hunts the nearest farm animals and picks up what they drop. */
    static final class GatherFood implements Task {
        private static final Set<String> ANIMALS = Set.of("cow", "pig", "chicken", "sheep", "rabbit");
        private final int wanted, radius;
        private int kills, misses, leg;
        private Task doing;
        private boolean hunting, climbed;
        private final Set<Integer> missed = new java.util.HashSet<>();
        private LivingEntity target;

        GatherFood(int wanted, int radius) { this.wanted = wanted; this.radius = radius; }

        @Override public Outcome tick(Body b) {
            if (doing != null) {
                Outcome o = doing.tick(b);
                if (o == null) return null;
                doing = null;
                if (hunting) {
                    hunting = false;
                    if (o.ok()) { kills++; doing = new Collect(8, 4); return null; }
                    misses++;
                    missed.add(target.getId());
                    // Most often the body is down a hole of its own, and no walk reaches the animal from there.
                    if (!climbed) { climbed = true; doing = surface(); return null; }
                }
            }
            if (kills >= wanted) return Outcome.OK;
            if (misses >= 4) return kills > 0 ? Outcome.OK : Outcome.fail("not_possible", "could not catch any animal");
            LivingEntity prey = null;
            for (LivingEntity e : b.level().getEntitiesOfClass(LivingEntity.class, b.getBoundingBox().inflate(radius), e -> e.isAlive() && !e.isBaby() && !missed.contains(e.getId())
                && ANIMALS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()))) {
                if (prey == null || e.distanceToSqr(b) < prey.distanceToSqr(b)) prey = e;
            }
            if (prey != null) {
                target = prey;
                LivingEntity prey2 = prey;
                hunting = true;
                doing = seq(Tasks.within(new Tasks.Walk(x -> prey2.isAlive() ? prey2.blockPosition() : null, 2), 20 * 30, "blocked", "could not reach the animal"),
                    new Tasks.Attack(prey2));
                return null;
            }
            // Animals live on the surface, and those near the start get eaten first: up, then further out.
            if (leg >= 4) return kills > 0 ? Outcome.OK : Outcome.fail("not_found", "no animals found");
            if (!climbed) { climbed = true; leg++; doing = surface(); return null; }
            doing = scout(b, leg++ - 1);
            if (doing == null) return kills > 0 ? Outcome.OK : Outcome.fail("not_found", "no animals found");
            return null;
        }

        @Override public void stop(Body b) { if (doing != null) doing.stop(b); else b.halt(); }
    }

    // ---- shelter ----

    /** Sleeps in a bed if one is near; otherwise walls the body in with carried blocks and waits there for morning. */
    static final class Shelter implements Task {
        private static final List<String> BLOCKS = List.of("dirt", "cobblestone", "cobbled_deepslate", "netherrack");
        private Task doing;
        private boolean walled, slept;
        private int waits;

        private static boolean holds(ServerLevel level, BlockPos p) {
            BlockState s = level.getBlockState(p);
            return !s.getCollisionShape(level, p).isEmpty() && !Body.name(s).endsWith("leaves");
        }

        private List<BlockPos> missing(Body b) {
            ServerLevel level = b.level();
            BlockPos feet = b.blockPosition();
            List<BlockPos> cells = new ArrayList<>();
            for (int dy = 0; dy <= 1; dy++) for (Direction d : Direction.Plane.HORIZONTAL) cells.add(feet.above(dy).relative(d));
            // The roof sits above the head, so one wall goes a block higher to give it something to set against.
            cells.add(feet.above(2).relative(Direction.EAST));
            cells.add(feet.above(2));
            List<BlockPos> out = new ArrayList<>();
            for (BlockPos c : cells) if (!holds(level, c)) out.add(c);
            return out;
        }

        @Override public Outcome tick(Body b) {
            if (doing != null) {
                Outcome o = doing.tick(b);
                if (o == null) return null;
                doing = null;
                if (slept) return o;
            }
            if (daytime(b) && !walled) return Outcome.OK;
            if (!walled) {
                BlockPos bed = nearest(b, 16, n -> n.endsWith("_bed"));
                if (bed != null && !slept) {
                    slept = true;
                    doing = new Tasks.Sleep();
                    return null;
                }
                if (!holds(b.level(), b.blockPosition().below())) return Outcome.fail("not_possible", "the floor here will not hold a shelter");
                List<BlockPos> missing = missing(b);
                String item = null;
                for (String name : BLOCKS) if (count(b, name) >= missing.size()) { item = name; break; }
                if (item == null && !missing.isEmpty()) return Outcome.fail("missing_materials", "missing " + missing.size() + " dirt or cobblestone");
                walled = true;
                List<Task> place = new ArrayList<>();
                // A wall needs something to set against, which on rough ground may be the one placed before it: go round twice.
                for (int round = 0; round < 2; round++) for (BlockPos c : missing) place.add(quiet(when(x -> !holds(x.level(), c), x -> new Tasks.Place(matchingBlock(x), c))));
                doing = Tasks.seq(place);
                return null;
            }
            // Inside until morning, then out through the wall the way the body faces.
            if (!daytime(b) && waits++ < 600) {
                doing = new Tasks.Wait(5);
                return null;
            }
            BlockPos out = b.blockPosition().relative(b.getDirection());
            doing = quiet(seq(digTask(out.above()), digTask(out)));
            walled = false;
            slept = true;
            return null;
        }

        private static String matchingBlock(Body b) {
            for (String name : BLOCKS) if (count(b, name) > 0) return name;
            return BLOCKS.get(0);
        }

        @Override public void stop(Body b) { if (doing != null) doing.stop(b); else b.halt(); }
    }

    /** Breaks one block, as a task. */
    static Task digTask(BlockPos at) {
        Tasks.Dig dig = new Tasks.Dig(at);
        return b -> {
            Boolean open = dig.tick(b);
            return open == null ? null : open ? Outcome.OK : Outcome.fail("not_possible", "could not dig there");
        };
    }

    // ---- plan ----

    /**
     * From nothing to an iron pickaxe and full iron armour. Each round does the first step still needed; a step that
     * keeps failing is passed over until something else changes what is possible.
     */
    static final class Progress implements Task {
        private record Step(String name, int retries, Predicate<Body> needed, Function<Body, Task> run) {}

        private static final int WOOD_RESERVE = 24, COBBLE_KEEP = 128;
        private static final String[][] HEADINGS = {{"x", "z", "x", "-z"}, {"z", "-x", "z", "x"}, {"-x", "-z", "-x", "z"}, {"-z", "x", "-z", "-x"}};
        private final int rounds;
        private final Integer mineY;
        private final List<Step> steps = new ArrayList<>();
        private final Map<String, Integer> failures = new HashMap<>();
        private int round, heading, leg;
        private Step current;
        private Task doing;
        private BlockPos legStart;

        Progress(int rounds, Integer mineY) {
            this.rounds = rounds;
            this.mineY = mineY;
            add("first tools", 2, b -> !betterPickaxe(b), b -> bootstrap());
            add("stone sword", 2, b -> has(b, "cobblestone", 2) && !has(b, "stone_sword", 1) && !has(b, "iron_sword", 1), b -> atTable("stone_sword", 1));
            add("iron pickaxe", 2, b -> !has(b, "iron_pickaxe", 1) && has(b, "iron_ingot", 3), b -> atTable("iron_pickaxe", 1));
            add("wood reserve", 2, b -> daytime(b) && count(b, "*_log") * 4 + count(b, "*_planks") < WOOD_RESERVE, b -> seq(surface(), new Chop(8, 48)));
            add("spare pickaxe", 2, b -> !betterPickaxe(b) && has(b, "cobblestone", 3), b -> atTable("stone_pickaxe", 1));
            add("food", 2, b -> food(b) < 4 && (daytime(b) || b.getFoodData().getFoodLevel() <= 8), b -> new GatherFood(4, 32));
            add("cook food", 2, b -> raw(b) > 0 && (nearest(b, 24, n -> n.equals("furnace")) != null || has(b, "furnace", 1) || has(b, "cobblestone", 8)), b -> cook());
            add("cobblestone", 2, b -> !has(b, "furnace", 1) && nearest(b, 24, n -> n.equals("furnace")) == null && !has(b, "cobblestone", 8), b -> tunnel(b, 8));
            add("furnace", 2, b -> has(b, "raw_iron", 1) && !has(b, "furnace", 1) && nearest(b, 24, n -> n.equals("furnace")) == null, b -> atTable("furnace", 1));
            add("place furnace", 2, b -> has(b, "furnace", 1) && nearest(b, 24, n -> n.equals("furnace")) == null, b -> placeNear("furnace"));
            add("iron ore", 8, b -> !has(b, "iron_pickaxe", 1) && !has(b, "iron_ingot", 3) && !has(b, "raw_iron", 3), b -> tunnel(b, 32));
            add("smelt iron", 2, b -> !has(b, "iron_pickaxe", 1) && !has(b, "iron_ingot", 3) && has(b, "raw_iron", 1) && hasFuel(b),
                b -> smelt("iron_ingot", Math.min(16, count(b, "raw_iron"))));
            // Iron gear in the order it is made: the helmet as the cheapest armour, the sword before the costly pieces.
            Object[][] gear = {{"iron_helmet", 5, true}, {"iron_sword", 2, false}, {"iron_chestplate", 8, true}, {"iron_leggings", 7, true}, {"iron_boots", 4, true}};
            for (Object[] g : gear) {
                String item = (String) g[0];
                int ingots = (Integer) g[1];
                boolean worn = (Boolean) g[2];
                String label = item.replace('_', ' ');
                Predicate<Body> wanted = b -> has(b, "iron_pickaxe", 1) && !has(b, item, 1) && !(worn && wearing(b, item));
                add("iron for " + label, 8, b -> wanted.test(b) && count(b, "iron_ingot") + count(b, "raw_iron") < ingots, b -> tunnel(b, 32));
                add("smelt for " + label, 2, b -> wanted.test(b) && !has(b, "iron_ingot", ingots) && has(b, "raw_iron", 1) && hasFuel(b),
                    b -> smelt("iron_ingot", Math.min(16, Math.min(count(b, "raw_iron"), ingots - count(b, "iron_ingot")))));
                add(label, 2, b -> wanted.test(b) && has(b, "iron_ingot", ingots), b -> atTable(item, 1));
                if (worn) add("wear " + label, 2, b -> has(b, item, 1), b -> wear(item));
            }
        }

        private void add(String name, int retries, Predicate<Body> needed, Function<Body, Task> run) { steps.add(new Step(name, retries, needed, run)); }

        private static boolean wearing(Body b, String item) {
            for (var slot : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                ItemStack s = b.getItemBySlot(slot);
                if (!s.isEmpty() && Body.name(s).equals(item)) return true;
            }
            return false;
        }

        /** A tunnel heading away from the last, so each cuts fresh rock; one that barely advanced turns the heading. */
        private Task tunnel(Body b, int length) {
            int y = mineY != null ? mineY : Math.min(16, b.getBlockY() - 10);
            String dir = HEADINGS[heading][leg % 4];
            leg++;
            legStart = b.blockPosition();
            return new StripMine(length, y < b.getBlockY() ? y : null, dir);
        }

        @Override public Outcome tick(Body b) {
            if (doing != null) {
                Outcome o = doing.tick(b);
                if (o == null) return null;
                doing = null;
                if (legStart != null && current != null && (current.name.startsWith("iron") || current.name.equals("cobblestone"))
                    && legStart.distManhattan(b.blockPosition()) < 8) {
                    heading = (heading + 1) % HEADINGS.length;
                    leg = 0;
                }
                legStart = null;
                if (!o.ok() || current.needed.test(b)) {
                    Goldar.LOGGER.info("{}: the plan step {} did not finish ({}): {}", b.mind.seat.id, current.name, o.reason(), o.message());
                    failures.merge(current.name, 1, Integer::sum);
                } else {
                    // Progress changes what is possible (fuel fetched, a new place to dig), so earlier failures get another try.
                    failures.clear();
                }
            }
            if (round >= rounds) return Outcome.OK;
            round++;
            // Cobblestone piles up while tunnelling: leave the excess so ore still fits.
            while (count(b, "cobblestone") - 64 >= COBBLE_KEEP) {
                int slot = b.slotOf("cobblestone");
                if (slot < 0) break;
                b.drop(Compat.items(b.getInventory()).get(slot).copy(), false);
                Compat.items(b.getInventory()).set(slot, ItemStack.EMPTY);
            }
            current = null;
            for (Step s : steps) {
                if (failures.getOrDefault(s.name, 0) < s.retries && s.needed.test(b)) { current = s; break; }
            }
            if (current == null) return Outcome.OK;
            Goldar.LOGGER.info("{}: plan round {}: {}", b.mind.seat.id, round, current.name);
            doing = current.run.apply(b);
            return null;
        }

        @Override public void stop(Body b) { if (doing != null) doing.stop(b); else b.halt(); }
    }
}
