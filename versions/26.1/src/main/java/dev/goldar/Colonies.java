package dev.goldar;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/** MineColonies has no build for this Minecraft version, so there is nothing for a body to do with it here. */
final class Colonies {
    private Colonies() {}

    static boolean isHut(Block block) { return false; }

    static Tasks.Task hut(String block, BlockPos at) { throw new UnsupportedOperationException(); }

    static Tasks.Task settle(BlockPos at) { throw new UnsupportedOperationException(); }
}
