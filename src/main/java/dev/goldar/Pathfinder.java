package dev.goldar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;

/**
 * A* over standable block positions: walking, one-block jumps, drops up to three, swimming and ladders. With a {@link Build}
 * budget it can also pillar up, bridge gaps and dig tunnels or staircases.
 */
public final class Pathfinder {
    public enum Action { WALK, PILLAR, BRIDGE }

    /** Arrive at pos by doing action, after breaking the dig cells (in order). */
    public record Step(BlockPos pos, Action action, List<BlockPos> dig) {}

    public record Result(List<Step> path, boolean complete) {}

    /**
     * blocks: scaffold blocks available. dig: whether blocks may be broken. wet: digging may also start in water and
     * open cells beside it, for a body that must get out of a flooded pocket.
     */
    public record Build(int blocks, boolean dig, boolean wet) {
        public static final Build NONE = new Build(0, false, false);

        public Build(int blocks, boolean dig) { this(blocks, dig, false); }
    }

    private static final int MAX_NODES = 40_000;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    /** What a step up or down through water costs when the walk may dig instead. */
    private static final double DIG_SWIM_COST = 15;
    private static final double PILLAR_COST = 6, BRIDGE_COST = 5, SWIM_COST = 2.5, SUBMERGED_COST = 30, DIG_DOWN_COST = 8, DANGER_COST = 10;
    private static final List<BlockPos> NO_DIG = List.of();
    /** Building moves multiply the options at every node, so that search is steered harder at the goal to finish within MAX_NODES. */
    private static final double BUILD_HEURISTIC_WEIGHT = 3;

    private final Terrain terrain;
    private final Build build;

    public Pathfinder(Terrain terrain, Build build) { this.terrain = terrain; this.build = build; }

    public Pathfinder(Terrain terrain) { this(terrain, Build.NONE); }

    private static final class Node implements Comparable<Node> {
        final BlockPos pos; final Node parent; final double g, f;
        final Action action; final List<BlockPos> dig; final int used;

        Node(Move m, Node parent, double g, double h) {
            this.pos = m.to; this.parent = parent; this.g = g; this.f = g + h; this.action = m.action; this.dig = m.dig;
            this.used = (parent == null ? 0 : parent.used) + (m.action == Action.WALK ? 0 : 1);
        }

        @Override public int compareTo(Node o) { return Double.compare(f, o.f); }
    }

    private boolean body(BlockPos p) { return terrain.clear(p) && terrain.clear(p.above()); }

    public boolean stand(BlockPos p) { return body(p) && (terrain.support(p.below()) || terrain.water(p) || terrain.climb(p)); }

    public Result find(BlockPos start, Predicate<BlockPos> goal, ToDoubleFunction<BlockPos> heuristic) {
        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<BlockPos, Double> best = new HashMap<>();
        double weight = build.blocks > 0 || build.dig ? BUILD_HEURISTIC_WEIGHT : 1;
        Node first = new Node(new Move(start, 0, Action.WALK, NO_DIG), null, 0, heuristic.applyAsDouble(start) * weight);
        open.add(first);
        best.put(start, 0.0);
        Node closest = first;
        double closestH = heuristic.applyAsDouble(start);
        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node cur = open.poll();
            if (cur.g > best.getOrDefault(cur.pos, Double.MAX_VALUE)) continue;
            expanded++;
            if (goal.test(cur.pos)) return new Result(trace(cur), true);
            double h = heuristic.applyAsDouble(cur.pos);
            if (h < closestH) { closestH = h; closest = cur; }
            List<Move> moves = moves(cur.pos);
            buildMoves(cur, moves);
            for (Move m : moves) {
                double g = cur.g + m.cost + terrain.danger(m.to) * DANGER_COST;
                if (g < best.getOrDefault(m.to, Double.MAX_VALUE)) {
                    best.put(m.to, g);
                    open.add(new Node(m, cur, g, heuristic.applyAsDouble(m.to) * weight));
                }
            }
        }
        return new Result(trace(closest), false);
    }

    private record Move(BlockPos to, double cost, Action action, List<BlockPos> dig) {
        Move(BlockPos to, double cost) { this(to, cost, Action.WALK, NO_DIG); }
    }

    private List<Move> moves(BlockPos p) {
        List<Move> out = new ArrayList<>();
        int x = p.getX(), y = p.getY(), z = p.getZ();
        for (int[] d : DIRS) {
            boolean diag = d[0] != 0 && d[1] != 0;
            BlockPos side = new BlockPos(x + d[0], y, z + d[1]);
            if (diag && !(body(new BlockPos(x + d[0], y, z)) && body(new BlockPos(x, y, z + d[1])))) continue;
            double step = diag ? 1.414 : 1;
            if (stand(side)) {
                out.add(new Move(side, step * walkCost(side)));
            } else if (!diag && stand(side.above()) && terrain.clear(p.above(2))) {
                out.add(new Move(side.above(), step + 1.5));
            } else if (body(side)) {
                for (int drop = 1; drop <= 3; drop++) {
                    BlockPos down = side.below(drop);
                    if (stand(down)) { out.add(new Move(down, step + drop * 0.6)); break; }
                    if (!terrain.clear(down)) break;
                }
            }
        }
        if (terrain.water(p) || terrain.climb(p)) {
            // A body swims up a column slowly and often not at all; where it may dig, a staircase is the surer way.
            double swim = build.dig ? DIG_SWIM_COST : 2;
            BlockPos up = p.above();
            if (stand(up) && terrain.clear(up.above())) out.add(new Move(up, terrain.water(p) ? swim : 1.5));
            BlockPos down = p.below();
            if (stand(down) && (terrain.water(down) || terrain.climb(down))) out.add(new Move(down, terrain.water(p) && build.dig ? swim : 1.5));
        }
        return out;
    }

    /** Swimming with the head under uses up air, so a route along the surface should always win. */
    private double walkCost(BlockPos p) {
        if (!terrain.water(p)) return 1;
        double cost = terrain.water(p.above()) ? SUBMERGED_COST : SWIM_COST;
        return build.dig ? cost * 4 : cost;
    }

    private void buildMoves(Node cur, List<Move> out) {
        BlockPos p = cur.pos;
        // A block this path places isn't in the world yet, so the node that placed it counts as supported.
        boolean firm = (build.wet || !terrain.water(p)) && !terrain.climb(p) && (cur.action != Action.WALK || terrain.support(p.below()));
        if (!firm) return;
        boolean canPlace = cur.used < build.blocks;
        if (canPlace && terrain.clear(p.above(2))) out.add(new Move(p.above(), PILLAR_COST, Action.PILLAR, NO_DIG));
        for (int i = 0; i < 4; i++) {
            BlockPos side = p.offset(DIRS[i][0], 0, DIRS[i][1]);
            if (canPlace && body(side) && !stand(side) && terrain.replaceable(side.below()))
                out.add(new Move(side, BRIDGE_COST, Action.BRIDGE, NO_DIG));
            if (build.dig) digMoves(p, side, out);
        }
        // Straight down is the last resort (it is how a player gets off its own pillar), so it costs more than a staircase.
        if (build.dig && terrain.support(p.below(2))) addDig(p.below(), DIG_DOWN_COST, out, p.below());
    }

    private void digMoves(BlockPos p, BlockPos side, List<Move> out) {
        if (terrain.support(side.below())) addDig(side, 1, out, side.above(), side);
        if (terrain.support(side)) addDig(side.above(), 2.5, out, p.above(2), side.above(2), side.above());
        if (terrain.support(side.below(2))) addDig(side.below(), 1.6, out, side.above(), side, side.below());
    }

    /** Adds a move reaching 'to' once the listed cells are open, if at least one needs digging and all of those may be dug. */
    private void addDig(BlockPos to, double base, List<Move> out, BlockPos... cells) {
        List<BlockPos> dig = new ArrayList<>();
        double cost = base;
        for (BlockPos c : cells) {
            if (terrain.clear(c)) continue;
            cost += terrain.digCost(c);
            dig.add(c);
        }
        if (!dig.isEmpty() && cost < Double.POSITIVE_INFINITY) out.add(new Move(to, cost, Action.WALK, dig));
    }

    private static List<Step> trace(Node n) {
        List<Step> path = new ArrayList<>();
        for (; n != null; n = n.parent) path.add(new Step(n.pos, n.action, n.dig));
        Collections.reverse(path);
        return path;
    }
}
