package dev.goldar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PathfinderTest {
    /** A flat floor at y=0 (standing height y=1), with cells carved out or added. */
    private static final class World implements Terrain {
        final Set<BlockPos> solid = new HashSet<>(), holes = new HashSet<>(), lava = new HashSet<>(), wet = new HashSet<>();

        boolean isSolid(BlockPos p) { return solid.contains(p) || (p.getY() <= 0 && !holes.contains(p)); }
        @Override public boolean clear(BlockPos p) { return !isSolid(p) && !lava.contains(p); }
        @Override public boolean water(BlockPos p) { return wet.contains(p); }
        @Override public boolean climb(BlockPos p) { return false; }
        @Override public boolean support(BlockPos p) { return isSolid(p); }
        @Override public boolean replaceable(BlockPos p) { return !isSolid(p); }
        @Override public double danger(BlockPos p) {
            for (BlockPos n : new BlockPos[] {p.north(), p.south(), p.east(), p.west()}) if (lava.contains(n)) return 1;
            return 0;
        }
        @Override public double digCost(BlockPos p) {
            for (BlockPos n : new BlockPos[] {p.north(), p.south(), p.east(), p.west(), p.above(), p.below()})
                if (lava.contains(n)) return Double.POSITIVE_INFINITY;
            return 3;
        }

        /** Digs a square shaft around the origin column, floor at y=1-depth. */
        World pit(int depth, int radius) {
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) for (int y = 0; y > -depth; y--) holes.add(new BlockPos(x, y, z));
            return this;
        }
    }

    private static Pathfinder.Result go(World w, Pathfinder.Build b, BlockPos from, BlockPos to) {
        return new Pathfinder(w, b).find(from, to::equals, n -> Math.sqrt(n.distSqr(to)));
    }

    private static long count(Pathfinder.Result r, Pathfinder.Action a) { return r.path().stream().filter(s -> s.action() == a).count(); }

    private static long digs(Pathfinder.Result r) { return r.path().stream().mapToLong(s -> s.dig().size()).sum(); }

    @Test void walksAcrossFlatGround() {
        Pathfinder.Result r = go(new World(), Pathfinder.Build.NONE, new BlockPos(0, 1, 0), new BlockPos(5, 1, 0));
        assertTrue(r.complete());
        assertEquals(6, r.path().size());
        assertEquals(0, digs(r));
    }

    @Test void walkingAloneCannotLeaveAPit() {
        BlockPos floor = new BlockPos(0, -3, 0);
        assertFalse(go(new World().pit(4, 0), Pathfinder.Build.NONE, floor, new BlockPos(3, 1, 0)).complete());
    }

    @Test void pillarsOutOfAPit() {
        BlockPos floor = new BlockPos(0, -3, 0);
        Pathfinder.Result r = go(new World().pit(4, 0), new Pathfinder.Build(8, false), floor, new BlockPos(3, 1, 0));
        assertTrue(r.complete());
        assertEquals(3, count(r, Pathfinder.Action.PILLAR)); // the fourth level is an ordinary step up onto the rim
    }

    @Test void pillarRespectsTheBlockBudget() {
        BlockPos floor = new BlockPos(0, -3, 0);
        assertFalse(go(new World().pit(4, 0), new Pathfinder.Build(2, false), floor, new BlockPos(3, 1, 0)).complete());
    }

    @Test void digsAStaircaseWithoutBlocks() {
        BlockPos floor = new BlockPos(0, -3, 0);
        Pathfinder.Result r = go(new World().pit(4, 0), new Pathfinder.Build(0, true), floor, new BlockPos(6, 1, 0));
        assertTrue(r.complete());
        assertEquals(0, count(r, Pathfinder.Action.PILLAR));
        assertTrue(digs(r) > 0);
    }

    @Test void bridgesAGap() {
        World w = new World();
        for (int x = 1; x <= 6; x++) for (int z = -30; z <= 30; z++) for (int y = 0; y >= -80; y--) w.holes.add(new BlockPos(x, y, z));
        Pathfinder.Result r = go(w, new Pathfinder.Build(10, false), new BlockPos(0, 1, 0), new BlockPos(7, 1, 0));
        assertTrue(r.complete());
        assertEquals(6, count(r, Pathfinder.Action.BRIDGE));
    }

    @Test void prefersWalkingOverBuilding() {
        World w = new World();
        for (int z = -2; z <= 2; z++) { w.solid.add(new BlockPos(2, 1, z)); w.solid.add(new BlockPos(2, 2, z)); }
        Pathfinder.Result r = go(w, new Pathfinder.Build(10, true), new BlockPos(0, 1, 0), new BlockPos(4, 1, 0));
        assertTrue(r.complete());
    }

    @Test void swimsAtTheSurfaceInsteadOfAlongTheBottom() {
        World w = new World();
        for (int x = -2; x <= 20; x++) for (int z = -5; z <= 5; z++) for (int y = 1; y <= 3; y++) w.wet.add(new BlockPos(x, y, z));
        BlockPos to = new BlockPos(15, 3, 0);
        Pathfinder.Result r = go(w, Pathfinder.Build.NONE, new BlockPos(0, 1, 0), to);
        assertTrue(r.complete());
        assertTrue(r.path().stream().filter(s -> s.pos().getY() < 3).count() <= 3, "stayed under for " + r.path());
    }

    @Test void digsDownOffItsOwnPillar() {
        World w = new World();
        for (int y = 1; y <= 8; y++) w.solid.add(new BlockPos(0, y, 0));
        Pathfinder.Result r = go(w, new Pathfinder.Build(0, true), new BlockPos(0, 9, 0), new BlockPos(3, 1, 0));
        assertTrue(r.complete());
        assertTrue(digs(r) > 0);
    }

    @Test void walksAroundLavaRatherThanBesideIt() {
        World w = new World();
        w.lava.add(new BlockPos(3, 1, 1));
        w.lava.add(new BlockPos(3, 1, -1));
        Pathfinder.Result r = go(w, Pathfinder.Build.NONE, new BlockPos(0, 1, 0), new BlockPos(6, 1, 0));
        assertTrue(r.complete());
        assertFalse(r.path().stream().anyMatch(s -> w.danger(s.pos()) > 0), "stepped beside lava: " + r.path());
    }

    @Test void neverDigsNextToLava() {
        World w = new World();
        for (int z = -40; z <= 40; z++) for (int y = 1; y <= 12; y++) { w.solid.add(new BlockPos(1, y, z)); w.lava.add(new BlockPos(2, y, z)); w.solid.add(new BlockPos(3, y, z)); }
        Pathfinder.Result r = go(w, new Pathfinder.Build(0, true), new BlockPos(0, 1, 0), new BlockPos(5, 1, 0));
        assertFalse(r.path().stream().flatMap(s -> s.dig().stream()).anyMatch(c -> c.getX() == 1 && c.getY() >= 1));
    }
}
