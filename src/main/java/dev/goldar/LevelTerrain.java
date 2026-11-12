package dev.goldar;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** {@link Terrain} backed by the server's world and the body's tools. */
public final class LevelTerrain implements Terrain {
    private final Level level;
    private final Body player;

    public LevelTerrain(Level level, Body player) { this.level = level; this.player = player; }

    public static boolean openable(BlockState s) {
        if (s.is(BlockTags.WOODEN_DOORS)) return !s.getValue(DoorBlock.OPEN);
        return s.is(BlockTags.FENCE_GATES) && !s.getValue(FenceGateBlock.OPEN);
    }

    private static boolean hazard(BlockState s) {
        return s.is(Blocks.LAVA) || s.is(BlockTags.FIRE) || s.is(Blocks.CACTUS) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CAMPFIRE)
            || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.POWDER_SNOW);
    }

    @Override public boolean clear(BlockPos p) {
        if (!level.hasChunkAt(p)) return false;
        BlockState s = level.getBlockState(p);
        if (hazard(s)) return false;
        if (openable(s) || s.is(BlockTags.CLIMBABLE)) return true; // a ladder's thin box doesn't stop a player sharing its cell
        VoxelShape shape = s.getCollisionShape(level, p);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.25; // carpets, snow layers
    }

    @Override public boolean water(BlockPos p) { return level.getFluidState(p).is(FluidTags.WATER); }

    @Override public boolean climb(BlockPos p) { return level.getBlockState(p).is(BlockTags.CLIMBABLE); }

    @Override public boolean support(BlockPos below) {
        BlockState s = level.getBlockState(below);
        if (hazard(s)) return false;
        VoxelShape shape = s.getCollisionShape(level, below);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0;
    }

    @Override public boolean replaceable(BlockPos p) { return level.hasChunkAt(p) && level.getBlockState(p).canBeReplaced(); }

    private boolean lava(BlockPos p) { return level.getFluidState(p).is(FluidTags.LAVA); }

    /** Lava in the surrounding cube or two blocks along an axis: opening a cell this close lets it flow in. */
    private boolean lavaWithin(BlockPos p) {
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            if (lava(p.offset(x, y, z))) return true;
        for (Direction d : Direction.values()) if (lava(p.relative(d, 2))) return true;
        return false;
    }

    /** A step beside lava is one knockback from burning, so routes should go around when they can. */
    @Override public double danger(BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (lava(p.relative(d)) || lava(p.relative(d).below())) return 1;
        }
        return lava(p.below()) ? 1 : 0;
    }

    @Override public double digCost(BlockPos p) {
        if (!level.hasChunkAt(p)) return Double.POSITIVE_INFINITY;
        BlockState s = level.getBlockState(p);
        float hardness = s.getDestroySpeed(level, p);
        // Containers and the like are the player's property; fluids would pour in; sand overhead would fall on the player.
        if (hardness < 0 || !s.getFluidState().isEmpty() || hazard(s) || level.getBlockEntity(p) != null || s.is(Blocks.CRAFTING_TABLE)) return Double.POSITIVE_INFINITY;
        if (level.getBlockState(p.above()).getBlock() instanceof FallingBlock) return Double.POSITIVE_INFINITY;
        for (Direction d : Direction.values()) {
            if (d != Direction.DOWN && !level.getFluidState(p.relative(d)).isEmpty()) return Double.POSITIVE_INFINITY;
            if (level.getFluidState(p.relative(d)).is(FluidTags.LAVA)) return Double.POSITIVE_INFINITY;
        }
        if (lavaWithin(p)) return Double.POSITIVE_INFINITY;
        float speed = 1f;
        boolean harvests = !s.requiresCorrectToolForDrops();
        for (var stack : Compat.items(player.getInventory())) {
            if (s.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(s)) continue;
            harvests = true;
            speed = Math.max(speed, stack.getDestroySpeed(s));
        }
        if (!harvests) return Double.POSITIVE_INFINITY;
        double seconds = hardness * 1.5 / speed;
        return 2 + seconds * 4; // walking covers about four blocks a second
    }
}
