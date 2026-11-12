package dev.goldar;

import dev.goldar.Tasks.Outcome;
import dev.goldar.Tasks.Task;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What a body does without being told, to stay alive between thoughts. One reflex runs at a time; each tick with none
 * running, the highest priority reflex whose cooldown has passed and whose trigger holds is started. The triggers,
 * numbers and outcome wording follow the world's own body.
 */
final class Reflexes {
    static final String HANDLED = "handled", FAILED = "failed", ABANDONED = "abandoned";

    /** A reflex that has finished: what it was, how it ended, and the wake it raised for the mind, if any. */
    record Fired(String name, String result, String detail, String wake, String wakeDetail) {}

    /** A sealed pocket the body dug for the night: where it stood before, and where it is now. */
    record Burrow(BlockPos mouth, BlockPos pocket) {}

    /** A walk the intent could not finish, waiting for a way round. */
    record Blocked(BlockPos goal, int range, long tick, String message) {}

    private static final Set<String> NEUTRAL = Set.of("enderman", "zombified_piglin", "piglin", "wolf", "iron_golem", "bee", "polar_bear", "llama", "dolphin");
    private static final Set<String> HAZARD_CELLS = Set.of("lava", "water", "fire", "soul_fire", "cobweb", "powder_snow", "sweet_berry_bush", "bubble_column",
        "seagrass", "tall_seagrass", "kelp", "kelp_plant");
    private static final Set<String> HAZARD_FLOORS = Set.of("magma_block", "cactus", "campfire", "soul_campfire");
    private static final Set<String> DIGGABLE = Set.of("dirt", "grass_block", "coarse_dirt", "podzol", "cobblestone", "cobbled_deepslate", "netherrack", "stone",
        "andesite", "diorite", "granite", "deepslate", "tuff");
    private static final String[] SEALS = {"dirt", "cobblestone", "cobbled_deepslate", "netherrack", "stone", "andesite", "diorite", "granite", "deepslate"};
    private static final Set<String> LOOSE = Set.of("sand", "red_sand", "gravel");
    private static final int LOW_HEALTH = 6;
    private static final int HUNGRY = 14;
    private static final int DROWNING_AIR_TICKS = 120;
    private static final int BLOCKED_TTL = 100;

    private abstract class Reflex {
        final String name;
        final int priority, cooldown;
        long lastFired = Long.MIN_VALUE / 2;

        Reflex(String name, int priority, int cooldown) { this.name = name; this.priority = priority; this.cooldown = cooldown; }

        /** The work to do now, or null when the trigger does not hold. */
        abstract Task check(Body b, long tick);
    }

    private final List<Reflex> all = new ArrayList<>();
    private Reflex running;
    private Task task;
    private boolean cancels;
    private String wake, wakeDetail;
    private Burrow burrow;
    private long bedCheckedAt = Long.MIN_VALUE / 2;
    private boolean bedNearby;
    /** Set by the plan when an intent's walk is blocked; cleared when the detour ends. */
    Blocked blocked;
    /** How the last detour ended: null while none has, else whether it reached the goal. */
    Boolean detour;

    Reflexes() {
        all.add(new FallStop());
        all.add(new HazardEscape());
        all.add(new Eat());
        all.add(new Retreat());
        all.add(new FightOrFlee());
        all.add(new PanicBurrow());
        all.add(new BlockedPath());
        all.sort(Comparator.comparingInt((Reflex r) -> -r.priority));
    }

    boolean busy() { return task != null; }

    /** Starts the reflex that is due, if any. Returns true when it takes the body from whatever it was doing. */
    boolean begin(Body b, long tick) {
        if (task != null) return false;
        for (Reflex r : all) {
            if (tick - r.lastFired < r.cooldown || Goldar.world().config.reflexes_off.contains(r.name)) continue;
            cancels = true;
            Task t = r.check(b, tick);
            if (t == null) continue;
            r.lastFired = tick;
            running = r;
            task = t;
            wake = null;
            wakeDetail = null;
            return cancels;
        }
        return false;
    }

    /** Advances the running reflex. Returns how it ended, or null while it is still going. */
    Fired tick(Body b) {
        Outcome o = task.tick(b);
        if (o == null) return null;
        Fired f = new Fired(running.name, o.ok() ? HANDLED : o.reason(), o.message(), wake, wakeDetail);
        task = null;
        running = null;
        return f;
    }

    void stop(Body b) {
        if (task != null) task.stop(b);
        task = null;
        running = null;
        blocked = null;
        detour = null;
    }

    // ---- what the reflexes read ----

    static boolean isNight(ServerLevel level) {
        long t = Math.floorMod(Goldar.time(level), 24000L);
        return t >= 12542 && t <= 23459 || level.isThundering();
    }

    private static boolean solid(ServerLevel level, BlockPos p) { return !level.getBlockState(p).getCollisionShape(level, p).isEmpty(); }

    /** Walled in on every side: the four sides at feet and head height, the roof and the floor. */
    static boolean enclosed(ServerLevel level, BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) if (!solid(level, feet.relative(d)) || !solid(level, feet.above().relative(d))) return false;
        return solid(level, feet.above(2)) && solid(level, feet.below());
    }

    private static String type(Entity e) { return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(); }

    /** The nearest creature within `radius` that means harm. Spiders count only at night. */
    private static LivingEntity danger(Body b, double radius) {
        boolean night = isNight(b.level());
        LivingEntity best = null;
        for (Entity e : b.level().getEntities(b, b.getBoundingBox().inflate(radius), x -> x instanceof Enemy && x instanceof LivingEntity l && l.isAlive())) {
            String type = type(e);
            if (NEUTRAL.contains(type) || !night && type.equals("spider")) continue;
            if (e.distanceTo(b) <= radius && (best == null || e.distanceToSqr(b) < best.distanceToSqr(b))) best = (LivingEntity) e;
        }
        return best;
    }

    private static Outcome done(String detail) { return new Outcome(true, null, detail); }

    /** Runs a task as one step of a reflex: its failure becomes the reflex's, in the reflex's words. */
    private static Task step(Task t, Function<Outcome, String> failure) {
        return new Task() {
            @Override public Outcome tick(Body b) {
                Outcome o = t.tick(b);
                return o == null || o.ok() ? o : Outcome.fail(FAILED, failure.apply(o));
            }

            @Override public void stop(Body b) { t.stop(b); }
        };
    }

    private static Task dig(BlockPos cell) {
        return new Task() {
            final Tasks.Dig dig = new Tasks.Dig(cell);

            @Override public Outcome tick(Body b) {
                b.halt();
                Boolean open = dig.tick(b);
                return open == null ? null : open ? Outcome.OK : Outcome.fail(FAILED, "it would not break");
            }
        };
    }

    /** Walks straight into the next cell. */
    private static Task stepInto(BlockPos cell, String failure) {
        return new Task() {
            int t;

            @Override public Outcome tick(Body b) {
                Vec3 to = new Vec3(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
                double dx = to.x - b.getX(), dz = to.z - b.getZ();
                if (dx * dx + dz * dz < 0.0625) { b.halt(); return Outcome.OK; }
                if (++t > 40) { b.halt(); return Outcome.fail(FAILED, failure); }
                b.steer(to, false);
                return null;
            }
        };
    }

    private static String at(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }

    // ---- the reflexes ----

    /** Stops a body that is walking, unguided, toward a long drop. */
    private final class FallStop extends Reflex {
        FallStop() { super("fall_stop", 100, 20); }

        private int dropAhead(Body b) {
            Vec3 v = b.getDeltaMovement();
            double speed = Math.sqrt(v.x * v.x + v.z * v.z);
            if (speed < 0.01) return 0;
            ServerLevel level = b.level();
            double x = b.getX() + v.x / speed, z = b.getZ() + v.z / speed;
            if (solid(level, BlockPos.containing(x, b.getY() + 0.5, z))) return 0;
            int drop = 0;
            for (BlockPos p = BlockPos.containing(x, Math.floor(b.getY()) - 1, z); drop < 32; p = p.below(), drop++) {
                if (!level.isLoaded(p) || level.getFluidState(p).is(FluidTags.WATER)) return 0;
                if (solid(level, p)) break;
            }
            return drop;
        }

        @Override Task check(Body b, long tick) {
            if (b.fallDistance > 1 || b.tickCount - b.pathTick <= 1) return null;
            int drop = dropAhead(b);
            if (drop <= 4) return null;
            return new Task() {
                int t;

                @Override public Outcome tick(Body body) {
                    body.halt();
                    if (++t < 3) return null;
                    Vec3 v = body.getDeltaMovement();
                    return Math.sqrt(v.x * v.x + v.z * v.z) > 0.03 ? Outcome.fail(FAILED, "still moving toward a " + drop + " block drop")
                        : done("stopped at the edge of a " + drop + " block drop");
                }
            };
        }
    }

    /** Gets a body out of lava, out of water before it drowns, and out of fire. */
    private final class HazardEscape extends Reflex {
        HazardEscape() { super("hazard_escape", 90, 40); }

        private static boolean hazardCell(ServerLevel level, BlockPos p) {
            return HAZARD_CELLS.contains(Body.name(level.getBlockState(p))) || !level.getFluidState(p).isEmpty();
        }

        private static String hazard(Body b) {
            if (b.isInLava()) return "lava";
            if (b.isInWater() && b.getAirSupply() <= DROWNING_AIR_TICKS) return "drowning";
            if (b.isOnFire() && !b.isInWater()) return "fire";
            return null;
        }

        private static boolean out(Body b, String hazard) {
            return switch (hazard) {
                case "lava" -> !b.isInLava();
                case "drowning" -> !b.isInWater() || b.getAirSupply() > DROWNING_AIR_TICKS;
                default -> !hazardCell(b.level(), b.blockPosition());
            };
        }

        private static BlockPos safeStand(Body b) {
            ServerLevel level = b.level();
            return BlockPos.findClosestMatch(b.blockPosition(), 8, 3, p -> level.isLoaded(p) && !solid(level, p) && !solid(level, p.above())
                && !hazardCell(level, p) && !hazardCell(level, p.above()) && solid(level, p.below())
                && !HAZARD_FLOORS.contains(Body.name(level.getBlockState(p.below())))).map(BlockPos::immutable).orElse(null);
        }

        @Override Task check(Body b, long tick) {
            String hazard = hazard(b);
            if (hazard == null) return null;
            if (hazard.equals("fire") && out(b, hazard)) {
                cancels = false;
                return body -> Outcome.fail(ABANDONED, "burning, but not standing in a hazard block");
            }
            BlockPos target = safeStand(b);
            if (target == null && !hazard.equals("drowning")) {
                cancels = false;
                return body -> {
                    wake = "reflex_danger";
                    wakeDetail = "no safe block within 8 blocks";
                    return Outcome.fail(ABANDONED, wakeDetail);
                };
            }
            return new Task() {
                int t;
                Tasks.Walk walk = target == null ? null : new Tasks.Walk(target, 1);

                @Override public Outcome tick(Body body) {
                    if (out(body, hazard)) { body.halt(); return done("left " + hazard); }
                    if (++t > 200) {
                        body.halt();
                        wake = "reflex_danger";
                        wakeDetail = "still in " + hazard + " after 200 ticks";
                        return Outcome.fail(ABANDONED, wakeDetail);
                    }
                    if (walk != null) {
                        if (walk.tick(body) != null) walk = null;
                        return null;
                    }
                    // No path, or the path is spent: swim or wade straight for it.
                    body.steer(target == null ? null : Vec3.atBottomCenterOf(target), true);
                    return null;
                }
            };
        }
    }

    /** Walks a badly hurt body away from a threat to the safest place it knows. */
    private final class Retreat extends Reflex {
        Retreat() { super("retreat", 80, 200); }

        @Override Task check(Body b, long tick) {
            if (b.getHealth() > LOW_HEALTH) return null;
            LivingEntity hostile = danger(b, 5);
            if (hostile == null) return null;
            int health = (int) Math.ceil(b.getHealth());
            String from = type(hostile);
            var respawn = b.getRespawnConfig();
            boolean bed = respawn != null && respawn.respawnData().globalPos().dimension() == b.level().dimension()
                && b.level().getBlockState(respawn.respawnData().pos()).is(BlockTags.BEDS);
            BlockPos spot = bed ? respawn.respawnData().pos() : b.level().getRespawnData().pos();
            String kind = bed ? "bed" : "spawn";
            Task walk = Tasks.within(new Tasks.Walk(spot, 2), 400, "timeout", "timeout");
            return new Task() {
                @Override public Outcome tick(Body body) {
                    Outcome o = walk.tick(body);
                    if (o == null) return null;
                    Outcome end = o.ok() ? done("reached the " + kind) : Outcome.fail(FAILED, "walk to the " + kind + " ended: " + (o.message() == null ? o.reason() : o.message()));
                    wake = "reflex_danger";
                    wakeDetail = "retreat at " + health + " health from " + from + ": " + end.message();
                    return end;
                }

                @Override public void stop(Body body) { walk.stop(body); }
            };
        }
    }

    /** Answers an attack: fights when armed and well, otherwise runs. */
    private final class FightOrFlee extends Reflex {
        FightOrFlee() { super("fight_or_flee", 60, 60); }

        private static boolean attacking(Body b, LivingEntity e) {
            Vec3 to = b.position().subtract(e.position());
            if (to.lengthSqr() < 1e-6) return true;
            return e.getLookAngle().dot(to.normalize()) >= 0.5;
        }

        private Task flee(LivingEntity from, String why) {
            String name = type(from);
            return new Task() {
                int leg, t;
                Tasks.Walk walk;

                private Outcome end(Outcome o) {
                    wake = "reflex_danger";
                    wakeDetail = name + " attacked: " + o.message();
                    return o;
                }

                @Override public Outcome tick(Body b) {
                    if (!from.isAlive() || from.isRemoved()) { b.halt(); return end(done(why + ": " + name + " already gone")); }
                    if (++t > 300) { b.halt(); return end(Outcome.fail(FAILED, why + ": flight ended timeout")); }
                    if (walk == null) {
                        if (leg >= 3) { b.halt(); return end(Outcome.fail(FAILED, why + ": no way to run")); }
                        Vec3 away = b.position().subtract(from.position()).multiply(1, 0, 1);
                        if (away.lengthSqr() < 1e-6) away = new Vec3(1, 0, 0);
                        Vec3 to = b.position().add(away.normalize().yRot((float) Math.toRadians(new int[] {0, 60, -60}[leg++])).scale(14));
                        walk = new Tasks.Walk(BlockPos.containing(to), 3);
                    }
                    Outcome o = walk.tick(b);
                    if (o == null) return null;
                    walk = null;
                    return o.ok() ? end(done(why + ": fled from " + name)) : null;
                }

                @Override public void stop(Body b) { b.halt(); }
            };
        }

        @Override Task check(Body b, long tick) {
            if (b.tickCount - b.lastHurtTick > 20) return null;
            LivingEntity mob = danger(b, 4);
            if (mob == null || !attacking(b, mob)) return null;
            boolean armed = Tasks.Attack.weaponSlot(b) >= 0 && !type(mob).equals("creeper");
            if (!armed || b.getHealth() <= LOW_HEALTH) return flee(mob, armed ? "health low" : "unarmed");
            String name = type(mob);
            Tasks.Attack fight = new Tasks.Attack(mob);
            return new Task() {
                int t;
                Task flight;

                @Override public Outcome tick(Body body) {
                    if (flight != null) return flight.tick(body);
                    if (body.getHealth() <= LOW_HEALTH) {
                        fight.stop(body);
                        flight = flee(mob, "health low mid-fight");
                        return null;
                    }
                    if (++t > 600) { fight.stop(body); return Outcome.fail(FAILED, "fight ended timeout: " + name + " still alive"); }
                    Outcome o = fight.tick(body);
                    if (o == null) return null;
                    return o.ok() ? done("killed " + name) : Outcome.fail(FAILED, "fight ended " + o.reason() + ": " + o.message());
                }

                @Override public void stop(Body body) { if (flight != null) flight.stop(body); else fight.stop(body); }
            };
        }
    }

    /** Eats when hungry and carrying food. */
    private final class Eat extends Reflex {
        Eat() { super("eat", 30, 100); }

        @Override Task check(Body b, long tick) {
            if (b.getFoodData().getFoodLevel() > HUNGRY || Tasks.Eat.foodSlot(b) < 0) return null;
            if (b.isUsingItem()) {
                cancels = false;
                return body -> Outcome.fail(ABANDONED, "already eating");
            }
            Tasks.Eat eat = new Tasks.Eat();
            return new Task() {
                @Override public Outcome tick(Body body) {
                    Outcome o = eat.tick(body);
                    return o == null ? null : o.ok() ? done("ate") : Outcome.fail(FAILED, o.message() == null ? o.reason() : o.message());
                }

                @Override public void stop(Body body) { eat.stop(body); }
            };
        }
    }

    /** Digs a body into a sealed pocket when night falls with no bed or shelter, and lets it out at dawn. */
    private final class PanicBurrow extends Reflex {
        PanicBurrow() { super("panic_burrow", 30, 200); }

        private static boolean wall(ServerLevel level, BlockPos p) {
            return solid(level, p) && level.getFluidState(p).isEmpty() && !LOOSE.contains(Body.name(level.getBlockState(p)));
        }

        private static boolean diggable(ServerLevel level, BlockPos p) { return wall(level, p) && DIGGABLE.contains(Body.name(level.getBlockState(p))); }

        /** A neighbouring cell that can become a pocket: diggable itself, walled on every other side. */
        private static BlockPos pocket(ServerLevel level, BlockPos feet) {
            for (Direction d : new Direction[] {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH}) {
                BlockPos f = feet.relative(d);
                if (!diggable(level, f) || !diggable(level, f.above()) || !wall(level, f.below()) || !wall(level, f.above(2))) continue;
                boolean walled = true;
                for (Direction side : new Direction[] {d, d.getClockWise(), d.getCounterClockWise()}) {
                    walled &= wall(level, f.relative(side)) && wall(level, f.above().relative(side));
                }
                if (walled) return f;
            }
            return null;
        }

        private Task dug(BlockPos feet, BlockPos pocket) {
            List<Task> steps = new ArrayList<>();
            for (BlockPos cell : new BlockPos[] {pocket.above(), pocket}) {
                steps.add(body -> wallsHold(body.level(), feet, pocket) ? Outcome.OK : Outcome.fail(FAILED, "pocket wall breached"));
                steps.add(step(dig(cell), o -> "could not dig the pocket: " + o.message()));
            }
            steps.add(stepInto(pocket, "could not step in"));
            for (BlockPos cell : new BlockPos[] {feet, feet.above()}) {
                steps.add(new Task() {
                    Tasks.Place place;

                    @Override public Outcome tick(Body body) {
                        if (place == null) {
                            String seal = null;
                            for (String s : SEALS) if (seal == null && body.count(s) > 0) seal = s;
                            if (seal == null) return Outcome.fail(FAILED, "nothing to seal with");
                            place = new Tasks.Place(seal, cell);
                        }
                        Outcome o = place.tick(body);
                        return o == null || o.ok() ? o : Outcome.fail(FAILED, "could not seal " + at(cell));
                    }
                });
            }
            steps.add(body -> { burrow = new Burrow(feet, pocket); return done("sealed in"); });
            return Tasks.seq(steps);
        }

        private static boolean wallsHold(ServerLevel level, BlockPos feet, BlockPos pocket) {
            if (!wall(level, pocket.below()) || !wall(level, pocket.above(2))) return false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side = pocket.relative(d);
                if (side.equals(feet)) continue;
                if (!wall(level, side) || !wall(level, side.above())) return false;
            }
            return true;
        }

        private Task leave(Burrow home) {
            return Tasks.seq(List.of(step(dig(home.mouth().above()), o -> "could not dig out: " + o.message()), step(dig(home.mouth()), o -> "could not dig out: " + o.message()),
                stepInto(home.mouth(), "could not step out"), body -> { burrow = null; return done("left at dawn"); }));
        }

        /** A way out of a pocket nobody remembers digging. */
        private Task breakOut(Body b) {
            ServerLevel level = b.level();
            BlockPos feet = b.blockPosition();
            Direction best = null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos f = feet.relative(d);
                boolean ok = solid(level, f.below());
                for (BlockPos cell : new BlockPos[] {f, f.above()}) {
                    BlockState s = level.getBlockState(cell);
                    ok &= s.getFluidState().isEmpty() && (!solid(level, cell) || DIGGABLE.contains(Body.name(s)));
                    for (Direction n : Direction.values()) ok &= level.getFluidState(cell.relative(n)).isEmpty();
                }
                if (!ok) continue;
                boolean open = !solid(level, f.relative(d)) && !solid(level, f.relative(d).above());
                if (best == null || open) best = d;
                if (open) break;
            }
            if (best == null) {
                cancels = false;
                return body -> Outcome.fail(ABANDONED, "no block to dig out through");
            }
            BlockPos out = feet.relative(best);
            return Tasks.seq(List.of(step(dig(out.above()), o -> "could not dig out: " + o.message()), step(dig(out), o -> "could not dig out: " + o.message()),
                stepInto(out, "could not step out"), body -> done("dug out at dawn")));
        }

        @Override Task check(Body b, long tick) {
            ServerLevel level = b.level();
            boolean night = isNight(level);
            BlockPos feet = b.blockPosition();
            if (!night) {
                if (burrow != null) {
                    if (!feet.equals(burrow.pocket())) { burrow = null; return null; }
                    return leave(burrow);
                }
                return enclosed(level, feet) ? breakOut(b) : null;
            }
            if (enclosed(level, feet) || b.isSleeping()) return null;
            if (tick - bedCheckedAt >= 20) {
                bedCheckedAt = tick;
                bedNearby = BlockPos.findClosestMatch(feet, 32, 32, p -> level.getBlockState(p).is(BlockTags.BEDS)).isPresent();
            }
            if (bedNearby) return null;
            BlockPos pocket = pocket(level, feet);
            if (pocket == null) {
                cancels = false;
                return body -> Outcome.fail(ABANDONED, "no suitable block");
            }
            return dug(feet, pocket);
        }
    }

    /** Tries a way round when an intent's walk finds none. */
    private final class BlockedPath extends Reflex {
        BlockedPath() { super("blocked_path", 20, 100); }

        @Override Task check(Body b, long tick) {
            Blocked report = blocked;
            if (report == null) return null;
            if (tick - report.tick() > BLOCKED_TTL) { blocked = null; detour = false; return null; }
            Vec3 from = b.position(), goal = Vec3.atBottomCenterOf(report.goal());
            Vec3 line = goal.subtract(from).multiply(1, 0, 1);
            Vec3 side = line.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : new Vec3(-line.z, 0, line.x).normalize();
            BlockPos waypoint = BlockPos.containing(from.add(goal).scale(0.5).add(side.scale(8)));
            Task route = Tasks.seq(List.of(new Tasks.Walk(waypoint, 2), new Tasks.Walk(report.goal(), report.range() + 2)));
            return new Task() {
                @Override public Outcome tick(Body body) {
                    Outcome o = route.tick(body);
                    if (o == null) return null;
                    blocked = null;
                    detour = o.ok();
                    if (o.ok()) return done("the alternate route reached the goal");
                    wake = "action_failed";
                    wakeDetail = "blocked: no alternate route (" + report.message() + ")";
                    return Outcome.fail(ABANDONED, wakeDetail);
                }

                @Override public void stop(Body body) { route.stop(body); }
            };
        }
    }
}
