package dev.goldar;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** The things a body can be told to do. Each task is ticked on the server thread until it reports an outcome. */
public final class Tasks {
    private Tasks() {}

    /** How a task ended. `reason` is one of the world's failure codes. */
    public record Outcome(boolean ok, String reason, String message) {
        public static final Outcome OK = new Outcome(true, null, null);
        public static Outcome fail(String reason, String message) { return new Outcome(false, reason, message); }
    }

    public interface Task {
        /** Advances one tick. Null while still running. */
        Outcome tick(Body b);

        /** Called when the task is dropped before it ended. */
        default void stop(Body b) { b.halt(); }
    }

    private static final double REACH = 4.5;
    private static final int SEARCH = 32;

    static ServerLevel level(Body b) { return b.level(); }

    static boolean lavaBeside(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) if (level.getFluidState(p.relative(d)).is(FluidTags.LAVA)) return true;
        return false;
    }

    // ---- digging one block ----

    /** Breaks one block over as many ticks as the held tool needs. */
    static final class Dig {
        final BlockPos pos;
        private int ticks;

        Dig(BlockPos pos) { this.pos = pos; }

        /** TRUE when the cell is open, FALSE when it can't be dug, null while digging. */
        Boolean tick(Body b) {
            ServerLevel level = level(b);
            BlockState s = level.getBlockState(pos);
            if (s.isAir() || s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty() && s.canBeReplaced()) return true;
            if (s.getDestroySpeed(level, pos) < 0 || !s.getFluidState().isEmpty()) return false;
            b.holdToolFor(s);
            b.lookAt(Vec3.atCenterOf(pos));
            if (ticks % 5 == 0) b.swing(InteractionHand.MAIN_HAND);
            float per = s.getDestroyProgress(b, level, pos);
            if (per <= 0) return false;
            ticks++;
            float done = ticks * per;
            level.destroyBlockProgress(b.getId(), pos, Math.min(9, (int) (done * 10)));
            if (done < 1) return null;
            level.destroyBlockProgress(b.getId(), pos, -1);
            b.gameMode.destroyBlock(pos);
            b.blockOperations++;
            return true;
        }
    }

    // ---- walking ----

    /** Walks to within `range` blocks of a target. It goes round what is in the way and never digs or builds. */
    public static final class Walk implements Task {
        private static final int MAX_PLANS = 6;
        private static final int STUCK_TICKS = 50;
        private final Function<Body, BlockPos> target;
        private final int range;
        private List<Pathfinder.Step> path;
        private boolean complete;
        private int index, plans, still;
        private Vec3 last;
        private Dig dig;

        public Walk(Function<Body, BlockPos> target, int range) { this.target = target; this.range = range; }

        public Walk(BlockPos target, int range) { this(b -> target, range); }

        private boolean arrived(BlockPos feet, BlockPos goal) {
            if (range == 0) return feet.equals(goal);
            if (range == 1) return Math.abs(feet.getX() - goal.getX()) <= 1 && Math.abs(feet.getY() - goal.getY()) <= 1 && Math.abs(feet.getZ() - goal.getZ()) <= 1;
            return feet.distSqr(goal) <= (double) range * range + 0.01;
        }

        @Override public Outcome tick(Body b) {
            BlockPos goal = target.apply(b);
            b.pathTick = b.tickCount;
            if (goal == null) { b.halt(); return Outcome.fail("not_found", "the target is gone"); }
            BlockPos feet = b.blockPosition();
            if (arrived(feet, goal)) { b.halt(); return Outcome.OK; }
            if (path == null || index >= path.size()) {
                if (path != null && complete) { b.halt(); return Outcome.OK; }
                if (++plans > MAX_PLANS) { b.halt(); return Outcome.fail("blocked", "no way there"); }
                var found = new Pathfinder(new LevelTerrain(level(b), b), Pathfinder.Build.NONE)
                    .find(feet, p -> arrived(p, goal), p -> Math.sqrt(p.distSqr(goal)));
                if (found.path().isEmpty()) { b.halt(); return Outcome.fail("blocked", "no way there"); }
                path = found.path();
                complete = found.complete();
                index = 0;
                still = 0;
                dig = null;
            }
            Pathfinder.Step step = path.get(index);
            for (BlockPos cell : step.dig()) {
                BlockState s = level(b).getBlockState(cell);
                if (s.isAir() || s.getCollisionShape(level(b), cell).isEmpty()) continue;
                b.halt();
                if (dig == null || !dig.pos.equals(cell)) dig = new Dig(cell);
                Boolean open = dig.tick(b);
                if (Boolean.FALSE.equals(open)) { path = null; }
                return null;
            }
            Vec3 to = new Vec3(step.pos().getX() + 0.5, step.pos().getY(), step.pos().getZ() + 0.5);
            double dx = to.x - b.getX(), dz = to.z - b.getZ(), dy = to.y - b.getY();
            if (dx * dx + dz * dz < 0.16 && Math.abs(dy) < 1.1) { index++; still = 0; return null; }
            boolean climb = dy > 0.4 || b.isInWater() || b.horizontalCollision;
            b.steer(to, climb);
            Vec3 now = b.position();
            if (last != null && now.distanceToSqr(last) < 0.0009) {
                if (++still > STUCK_TICKS) path = null;
            } else {
                still = 0;
            }
            last = now;
            return null;
        }
    }

    // ---- mining ----

    /** Breaks blocks of one kind, nearest first, and picks up what they drop. */
    public static final class Mine implements Task {
        private static final int PICKUP_TICKS = 80;
        private final String block;
        private final int radius;
        private final String item;
        private final int goal;
        private int left;
        private final Set<BlockPos> tried = new HashSet<>();
        private BlockPos at;
        private Walk walk;
        private Dig dig;
        private int pickup;
        private BlockPos broken, dropAt;
        private int had = -1;

        /** Mines `count` blocks. With `item` and `goal`, stops as soon as the inventory holds that many. */
        public Mine(String block, int count, int radius, String item, int goal) {
            this.block = block; this.left = count; this.radius = radius; this.item = item; this.goal = goal;
        }

        public Mine(String block, int count) { this(block, count, SEARCH, null, 0); }

        private boolean done(Body b) { return item != null ? b.count(item) >= goal : left <= 0; }

        @Override public Outcome tick(Body b) {
            ServerLevel level = level(b);
            if (pickup > 0) {
                // Walk onto what the block dropped, wherever it fell: into the hole, or down from a tree.
                pickup--;
                net.minecraft.world.entity.item.ItemEntity drop = null;
                for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(broken).inflate(3, 6, 3))) {
                    if (drop == null || e.distanceToSqr(b) < drop.distanceToSqr(b)) drop = e;
                }
                if (pickup == 0 || drop == null && pickup < PICKUP_TICKS - 5) {
                    pickup = 0;
                    walk = null;
                    b.halt();
                    // Gathering is for the item: a block that gave none ends it.
                    if (item != null && b.count(item) <= had) return Outcome.fail("not_possible", "mining " + block + " did not add " + item + " to the inventory");
                    return null;
                }
                if (drop == null) return null;
                if (walk == null || !drop.blockPosition().equals(dropAt)) {
                    dropAt = drop.blockPosition();
                    walk = new Walk(dropAt, 0);
                }
                if (walk.tick(b) != null) b.steer(drop.position(), false);
                return null;
            }
            if (done(b)) { b.halt(); return Outcome.OK; }
            if (at == null) {
                if (Compat.block(block).isEmpty()) {
                    return Outcome.fail("invalid_args", "unknown block " + block);
                }
                Optional<BlockPos> found = BlockPos.findClosestMatch(b.blockPosition(), radius, radius,
                    p -> !tried.contains(p) && Body.name(level.getBlockState(p)).equals(block));
                if (found.isEmpty()) return Outcome.fail("not_found", "no " + block + " within " + radius);
                at = found.get().immutable();
                tried.add(at);
                // Breaking the block underfoot would drop the body.
                if (at.equals(b.blockPosition().below())) return Outcome.fail("not_possible", "would drop the body");
                if (item != null) had = b.count(item);
                BlockState s = level.getBlockState(at);
                if (!b.holdToolFor(s)) return Outcome.fail("missing_materials", block + " needs a better tool");
                walk = b.getEyePosition().distanceTo(Vec3.atCenterOf(at)) <= REACH ? null : new Walk(at, 3);
                dig = new Dig(at);
            }
            if (walk != null) {
                Outcome o = walk.tick(b);
                if (o == null) return null;
                walk = null;
                if (!o.ok()) return o;
                if (b.getEyePosition().distanceTo(Vec3.atCenterOf(at)) > REACH + 1) return Outcome.fail("out_of_reach", "could not get near the " + block);
                if (at.equals(b.blockPosition().below())) return Outcome.fail("not_possible", "would drop the body");
            }
            Boolean open = dig.tick(b);
            if (open == null) return null;
            broken = at;
            at = null;
            if (!open) return null;
            left--;
            pickup = PICKUP_TICKS;
            walk = null;
            return null;
        }
    }

    // ---- placing ----

    /** Places a block from the inventory at a position. One that would stand in the body goes in the first free cell beside it. */
    public static final class Place implements Task {
        private final String block;
        private final BlockPos target;
        private Walk walk;
        private BlockPos at;
        private Task hut;
        private static final Set<String> REPLACEABLE = Set.of("air", "cave_air", "void_air", "water", "short_grass", "tall_grass", "fern", "snow");

        public Place(String block, BlockPos target) { this.block = block; this.target = target; }

        private static boolean replaceable(ServerLevel level, BlockPos p) { return REPLACEABLE.contains(Body.name(level.getBlockState(p))); }

        /** The first open cell beside the body, starting the way it faces, that has something solid to place against. */
        private static BlockPos freeBeside(Body b) {
            ServerLevel level = level(b);
            Direction facing = b.getDirection();
            for (Direction d : new Direction[] {facing, facing.getClockWise(), facing.getOpposite(), facing.getCounterClockWise()}) {
                BlockPos cell = b.blockPosition().relative(d);
                if (!replaceable(level, cell)) continue;
                for (Direction n : Direction.values()) {
                    BlockPos against = cell.relative(n);
                    if (!level.getBlockState(against).getCollisionShape(level, against).isEmpty()) return cell;
                }
            }
            return null;
        }

        @Override public Outcome tick(Body b) {
            // A hut that is down is out of the inventory, and still has its colony to settle.
            if (hut != null) return hut.tick(b);
            ServerLevel level = level(b);
            int slot = b.slotOf(block);
            if (slot < 0) return Outcome.fail("missing_materials", "no " + block + " in the inventory");
            if (at == null) {
                var placed = Compat.block(block);
                if (placed.isEmpty() || !(Compat.items(b.getInventory()).get(slot).getItem() instanceof net.minecraft.world.item.BlockItem)) {
                    return Outcome.fail("not_possible", block + " is not a block");
                }
                at = target != null ? target : b.blockPosition();
                if (!replaceable(level, at)) return Outcome.fail("blocked", "target is occupied: name an empty position or mine the block first");
                boolean solid = !placed.get().defaultBlockState().getCollisionShape(level, at).isEmpty();
                if (solid && b.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(at))) {
                    at = freeBeside(b);
                    if (at == null) return Outcome.fail("blocked", "no free space next to you: step to open ground or name a position");
                }
                if (b.getEyePosition().distanceTo(Vec3.atCenterOf(at)) > REACH) walk = new Walk(at, 3);
            }
            if (walk != null) {
                Outcome o = walk.tick(b);
                if (o == null) return null;
                walk = null;
                if (!o.ok()) return o;
            }
            if (!replaceable(level, at)) return Outcome.fail("blocked", "target is occupied: name an empty position or mine the block first");
            // A colony's hut is not placed by a click: its mod opens a window instead, so the body does what the window would.
            if (Colonies.isHut(Compat.block(block).orElseThrow())) {
                hut = Colonies.hut(block, at);
                return hut.tick(b);
            }
            for (Direction d : Direction.values()) {
                BlockPos against = at.relative(d);
                if (level.getBlockState(against).getCollisionShape(level, against).isEmpty()) continue;
                b.hold(slot);
                Vec3 hit = Vec3.atCenterOf(against).add(Compat.unit(d.getOpposite()).scale(0.5));
                b.lookAt(hit);
                var result = b.gameMode.useItemOn(b, level, b.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, d.getOpposite(), against, false));
                b.halt();
                if (!result.consumesAction()) continue;
                b.swing(InteractionHand.MAIN_HAND);
                if (!Body.name(level.getBlockState(at)).equals(block)) {
                    return Outcome.fail("blocked", "the block did not appear at the target: stand on solid ground and choose a block position next to you");
                }
                b.blockOperations++;
                return Outcome.OK;
            }
            b.halt();
            return Outcome.fail("blocked", "no solid block to place against: name a position next to a solid block");
        }
    }

    // ---- fighting ----

    /** Hits the nearest creature of a kind, or a named agent, until it is dead. */
    public static final class Attack implements Task {
        private static final int MAX_TICKS = 600, MAX_SWINGS = 80;
        private final String target;
        private LivingEntity victim;
        private int ticks, swings;

        public Attack(String target) { this.target = target; }

        /** Fights one known creature. */
        public Attack(LivingEntity victim) { this.target = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).getPath(); this.victim = victim; }

        /** The slot of the best weapon carried: swords before axes, better tiers first. -1 when unarmed. */
        static int weaponSlot(Body b) {
            for (String kind : new String[] {"sword", "axe"}) {
                for (String tier : new String[] {"netherite", "diamond", "iron", "stone", "golden", "wooden"}) {
                    int slot = b.slotOf(tier + "_" + kind);
                    if (slot >= 0) return slot;
                }
            }
            return -1;
        }

        @Override public Outcome tick(Body b) {
            if (victim == null) {
                double best = Double.MAX_VALUE;
                for (Entity e : level(b).getEntities(b, b.getBoundingBox().inflate(24), x -> x instanceof LivingEntity l && l.isAlive())) {
                    String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
                    boolean named = e instanceof Player p && Compat.name(p.getGameProfile()).equals(target);
                    if (!named && !type.equals(target)) continue;
                    double d = e.distanceToSqr(b);
                    if (d < best) { best = d; victim = (LivingEntity) e; }
                }
                if (victim == null) return Outcome.fail("not_found", "no " + target + " in view");
            }
            if (ticks == 0) b.hold(weaponSlot(b));
            if (!victim.isAlive() || victim.isRemoved()) { b.halt(); return Outcome.OK; }
            if (++ticks > MAX_TICKS) { b.halt(); return Outcome.fail("timeout", "the fight went on too long"); }
            b.lookAt(victim.getEyePosition());
            if (b.distanceTo(victim) > 2.8) {
                b.steer(victim.position(), b.horizontalCollision || b.isInWater());
            } else {
                b.halt();
                if (b.getAttackStrengthScale(0) >= 1f) {
                    if (++swings > MAX_SWINGS) return Outcome.fail("timeout", "the fight went on too long");
                    b.attack(victim);
                    b.swing(InteractionHand.MAIN_HAND);
                    b.resetAttackStrengthTicker();
                }
            }
            return null;
        }
    }

    // ---- eating ----

    /** Eats one item of food from the inventory. */
    public static final class Eat implements Task {
        private static final Set<String> BANNED_FOODS = Set.of("rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "suspicious_stew", "chorus_fruit");
        private boolean started;
        private int ticks, before, waited = -1;

        static int foodSlot(Body b) {
            var items = Compat.items(b.getInventory());
            int best = -1, bestFood = 0;
            for (int i = 0; i < items.size(); i++) {
                var food = items.get(i).get(DataComponents.FOOD);
                if (food == null || BANNED_FOODS.contains(Body.name(items.get(i)))) continue;
                if (food.nutrition() > bestFood) { best = i; bestFood = food.nutrition(); }
            }
            return best;
        }

        @Override public Outcome tick(Body b) {
            if (!started) {
                int slot = foodSlot(b);
                if (slot < 0) return Outcome.fail("missing_materials", "no food");
                if (!b.getFoodData().needsFood()) return Outcome.fail("not_possible", "not hungry");
                b.hold(slot);
                b.halt();
                before = b.getFoodData().getFoodLevel();
                b.gameMode.useItem(b, level(b), b.getMainHandItem(), InteractionHand.MAIN_HAND);
                started = true;
                return null;
            }
            if (b.getFoodData().getFoodLevel() > before) return Outcome.OK;
            if (b.isUsingItem() && ++ticks < 80) return null;
            // The meal is over: hunger must show it within three seconds.
            return ++waited < 60 ? null : Outcome.fail("timeout", "food did not rise");
        }
    }

    // ---- sleeping ----

    /** Lies down in the nearest bed. Done as soon as the body is asleep. */
    public static final class Sleep implements Task {
        private Walk walk;
        private BlockPos bed;
        private boolean asleep;

        @Override public Outcome tick(Body b) {
            ServerLevel level = level(b);
            if (asleep) return Outcome.OK;
            if (bed == null) {
                Optional<BlockPos> found = BlockPos.findClosestMatch(b.blockPosition(), 32, 32, p -> level.getBlockState(p).is(BlockTags.BEDS));
                if (found.isEmpty()) return Outcome.fail("not_found", "no bed");
                if (!Reflexes.isNight(level)) return Outcome.fail("not_possible", "you can only sleep at night or in a thunderstorm");
                bed = found.get().immutable();
                walk = new Walk(bed, 2);
            }
            if (walk != null) {
                Outcome o = walk.tick(b);
                if (o == null) return null;
                walk = null;
                if (!o.ok()) return o;
            }
            var result = b.startSleepInBed(bed);
            if (result.left().isPresent()) return Outcome.fail("not_possible", "could not sleep: " + result.left().get().toString().toLowerCase());
            asleep = true;
            return Outcome.OK;
        }

        @Override public void stop(Body b) { if (b.isSleeping() && !asleep) b.stopSleepInBed(true, true); b.halt(); }
    }

    // ---- several things in a row ----

    /** Runs tasks one after another and stops at the first that fails. Ends with the last task's outcome. */
    public static Task seq(List<Task> steps) {
        return new Task() {
            int at;
            Outcome last = Outcome.OK;

            @Override public Outcome tick(Body b) {
                while (at < steps.size()) {
                    Outcome o = steps.get(at).tick(b);
                    if (o == null) return null;
                    if (!o.ok()) return o;
                    last = o;
                    at++;
                }
                return last;
            }

            @Override public void stop(Body b) { if (at < steps.size()) steps.get(at).stop(b); }
        };
    }

    /** Steps to the middle of a cell the body is in or beside. */
    public static Task centre(BlockPos cell) {
        return new Task() {
            int t;

            @Override public Outcome tick(Body b) {
                Vec3 to = Vec3.atBottomCenterOf(cell);
                double dx = to.x - b.getX(), dz = to.z - b.getZ();
                if (dx * dx + dz * dz < 0.04 || ++t > 30) { b.halt(); return Outcome.OK; }
                b.steer(to, false);
                return null;
            }
        };
    }

    /** Gives a task a deadline in ticks. */
    public static Task within(Task task, int ticks, String reason, String message) {
        return new Task() {
            int t;

            @Override public Outcome tick(Body b) {
                if (++t > ticks) { task.stop(b); return Outcome.fail(reason, message); }
                return task.tick(b);
            }

            @Override public void stop(Body b) { task.stop(b); }
        };
    }

    // ---- building ----

    /** Places a blueprint's blocks, lowest first, relative to where the body stood when it began. */
    public static final class Build implements Task {
        public record Part(String block, int dx, int dy, int dz) {}

        private final String name;
        private final java.util.ArrayDeque<Part> parts;
        private final int total;
        private int asides;
        private BlockPos origin;
        private Part part;
        private Task step;
        private int placed;

        public Build(String name, List<Part> blocks) {
            this.name = name;
            List<Part> sorted = new java.util.ArrayList<>(blocks);
            sorted.sort(java.util.Comparator.comparingInt(Part::dy));
            this.parts = new java.util.ArrayDeque<>(sorted);
            this.total = blocks.size();
        }

        private BlockPos at(Part p) { return origin.offset(p.dx(), p.dy(), p.dz()); }

        /** Somewhere to stand that the blueprint leaves empty, as near as possible. */
        private BlockPos standAside(Body b) {
            Set<BlockPos> taken = new HashSet<>();
            for (Part p : parts) taken.add(at(p));
            if (part != null) taken.add(at(part));
            ServerLevel level = level(b);
            return BlockPos.findClosestMatch(b.blockPosition(), 5, 3, c -> !taken.contains(c) && !taken.contains(c.above())
                && level.getBlockState(c).getCollisionShape(level, c).isEmpty() && level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()
                && !level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty() && level.getFluidState(c).isEmpty()).map(BlockPos::immutable).orElse(null);
        }

        @Override public Outcome tick(Body b) {
            if (origin == null) origin = b.blockPosition();
            while (true) {
                if (step == null) {
                    part = parts.poll();
                    if (part == null) return new Outcome(true, null, "Built " + name + ": " + placed + " of " + total + " blocks placed.");
                    BlockPos cell = at(part);
                    if (Body.name(level(b).getBlockState(cell)).equals(part.block())) { placed++; continue; }
                    if (b.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(cell))) {
                        // The body is in the way of its own work: step aside once, then come back to this block.
                        BlockPos aside = standAside(b);
                        if (aside == null || ++asides > 8) return Outcome.fail("blocked", "no room to stand clear of " + name + " after " + placed + " of " + total);
                        parts.addFirst(part);
                        step = seq(List.of(new Walk(aside, 0), centre(aside)));
                    } else {
                        step = new Place(part.block(), cell);
                    }
                }
                Outcome o = step.tick(b);
                if (o == null) return null;
                boolean walked = !(step instanceof Place);
                step = null;
                if (!o.ok()) return Outcome.fail(o.reason(), o.message() + " (" + name + ": " + placed + " of " + total + " placed)");
                if (!walked) placed++;
            }
        }

        @Override public void stop(Body b) { if (step != null) step.stop(b); b.halt(); }
    }

    /** Sets a sign from the inventory on the ground beside the body and writes on it. */
    public static final class WriteSign implements Task {
        private static final int LINE = 15;
        private final String text;
        private Place place;
        private BlockPos at;

        public WriteSign(String text) { this.text = text; }

        /** Breaks the text into the sign's four lines at spaces where it can. */
        static List<String> lines(String text) {
            List<String> out = new java.util.ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.strip().split("\\s+")) {
                while (word.length() > LINE) {
                    if (!line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
                    out.add(word.substring(0, LINE));
                    word = word.substring(LINE);
                }
                if (line.length() + (line.isEmpty() ? 0 : 1) + word.length() > LINE) { out.add(line.toString()); line.setLength(0); }
                if (!word.isEmpty()) line.append(line.isEmpty() ? "" : " ").append(word);
            }
            if (!line.isEmpty()) out.add(line.toString());
            if (out.size() > 4) {
                // Breaking at spaces wasted too much room: fill the lines to the brim instead.
                out.clear();
                String flat = text.strip().replaceAll("\\s+", " ");
                for (int i = 0; i < flat.length(); i += LINE) out.add(flat.substring(i, Math.min(flat.length(), i + LINE)).strip());
            }
            while (out.size() < 4) out.add("");
            return out;
        }

        @Override public Outcome tick(Body b) {
            ServerLevel level = level(b);
            if (place == null) {
                if (lines(text).size() > 4) return Outcome.fail("invalid_args", "the text does not fit on a sign");
                String sign = null;
                for (ItemStack s : Compat.items(b.getInventory())) if (!s.isEmpty() && s.is(net.minecraft.tags.ItemTags.SIGNS)) { sign = Body.name(s); break; }
                if (sign == null) return Outcome.fail("missing_materials", "no sign in the inventory");
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos c = b.blockPosition().relative(d);
                    if (level.getBlockState(c).canBeReplaced() && level.getFluidState(c).isEmpty() && level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) { at = c; break; }
                }
                if (at == null) return Outcome.fail("blocked", "no free space next to you: step to open ground or name a position");
                place = new Place(sign, at);
            }
            Outcome o = place.tick(b);
            if (o == null || !o.ok()) return o;
            if (!(level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.SignBlockEntity entity)) {
                return Outcome.fail("blocked", "the block did not appear at the target: stand on solid ground and choose a block position next to you");
            }
            // Placing a sign leaves it open for its placer to write, as it would be for any player.
            if (!b.getUUID().equals(entity.getPlayerWhoMayEdit())) entity.setAllowedPlayerEditor(b.getUUID());
            List<net.minecraft.server.network.FilteredText> filtered = new java.util.ArrayList<>();
            for (String line : lines(text)) filtered.add(net.minecraft.server.network.FilteredText.passThrough(line));
            entity.updateSignText(b, true, filtered);
            return new Outcome(true, null, "The sign stands at " + at.getX() + " " + at.getY() + " " + at.getZ() + ".");
        }

        @Override public void stop(Body b) { if (place != null) place.stop(b); }
    }

    // ---- small things ----

    public static final class Wait implements Task {
        private int ticks;

        public Wait(int seconds) { this.ticks = seconds * 20; }

        @Override public Outcome tick(Body b) { b.halt(); return --ticks <= 0 ? Outcome.OK : null; }
    }

    /** Walks up to an agent and drops items at their feet. */
    public static final class Give implements Task {
        private final String to, item;
        private final int count;
        private Walk walk;

        public Give(String to, String item, int count) { this.to = to; this.item = item; this.count = count; }

        @Override public Outcome tick(Body b) {
            Player other = level(b).getServer().getPlayerList().getPlayerByName(to);
            if (other == null || other.level() != b.level()) return Outcome.fail("not_found", "no one called " + to + " is here");
            if (b.count(item) < count) return Outcome.fail("missing_materials", "not enough " + item);
            if (walk == null) walk = new Walk(x -> other.isRemoved() ? null : other.blockPosition(), 2);
            Outcome o = walk.tick(b);
            if (o == null) return null;
            if (!o.ok()) return o;
            b.lookAt(other.getEyePosition());
            int left = count;
            var items = Compat.items(b.getInventory());
            for (int i = 0; i < items.size() && left > 0; i++) {
                ItemStack s = items.get(i);
                if (s.isEmpty() || !Body.name(s).equals(item)) continue;
                int n = Math.min(left, s.getCount());
                b.drop(s.split(n), false, true);
                left -= n;
            }
            return Outcome.OK;
        }
    }

    /** Moves items between the inventory and a chest or barrel, through its screen. */
    public static final class Transfer implements Task {
        public enum Kind { OPEN, DEPOSIT, WITHDRAW }

        private final Kind kind;
        private final BlockPos at;
        private final String item;
        private final int count;
        private Walk walk;
        private Task hut;

        public Transfer(Kind kind, BlockPos at, String item, int count) { this.kind = kind; this.at = at; this.item = item; this.count = count; }

        @Override public Outcome tick(Body b) {
            if (hut != null) return hut.tick(b);
            // A colony's hut opens a window, not a chest: what a player would do in it at first is done for the body.
            if (kind == Kind.OPEN && Colonies.isHut(level(b).getBlockState(at).getBlock())) {
                if (walk == null) walk = new Walk(at, 3);
                Outcome o = walk.tick(b);
                if (o == null) return null;
                if (!o.ok()) return o;
                hut = Colonies.settle(at);
                return hut.tick(b);
            }
            // A hut and a colony's rack keep a store too, behind a button of their window.
            boolean store = Colonies.isStore(b, at);
            if (!store && (!Set.of("chest", "trapped_chest", "barrel").contains(Body.name(level(b).getBlockState(at))) || !(level(b).getBlockEntity(at) instanceof Container))) {
                return Outcome.fail("not_found", "no container at that position");
            }
            if (walk == null) walk = new Walk(at, 3);
            Outcome o = walk.tick(b);
            if (o == null) return null;
            if (!o.ok()) return o;
            var menu = store ? Colonies.openStore(b, at) : Menus.open(b, at);
            if (menu == null) return Outcome.fail("blocked", "the container would not open");
            try {
                java.util.function.Predicate<Slot> mine = s -> Menus.own(b, s), box = s -> s.container != b.getInventory();
                java.util.function.Predicate<ItemStack> wanted = s -> Body.name(s).equals(item);
                switch (kind) {
                    case OPEN -> {
                        return Outcome.OK;
                    }
                    case DEPOSIT -> {
                        if (b.count(item) < count) return Outcome.fail("missing_materials", "inventory has only " + b.count(item) + " " + item);
                        int moved = Menus.move(b, mine, box, wanted, count);
                        return moved < count ? Outcome.fail("not_possible", "container full") : Outcome.OK;
                    }
                    default -> {
                        // All or nothing: a container that holds too few gives none.
                        int there = 0;
                        for (Slot s : menu.slots) if (box.test(s) && s.hasItem() && wanted.test(s.getItem())) there += s.getItem().getCount();
                        if (there < count) return Outcome.fail("not_found", "the container holds only " + there + " " + item);
                        int moved = Menus.move(b, box, mine, wanted, count);
                        return moved < count ? Outcome.fail(moved == 0 ? "not_found" : "not_possible", "moved " + moved + " of " + count + " " + item) : Outcome.OK;
                    }
                }
            } finally {
                Menus.close(b);
            }
        }
    }
}
