package dev.goldar;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/** MineColonies has no build for this Minecraft version, so there is nothing for a body to do with it here. */
final class Colonies {
    private Colonies() {}

    static boolean isHut(Block block) { return false; }

    static Tasks.Task hut(String block, BlockPos at) { throw new UnsupportedOperationException(); }

    static Tasks.Task settle(BlockPos at) { throw new UnsupportedOperationException(); }

    static boolean isStore(Body b, BlockPos at) { return false; }

    static java.util.List<BlockPos> storesOf(Body b, BlockPos at) { return java.util.List.of(at); }

    static int takes(Body b, BlockPos at, String item) { return Integer.MAX_VALUE; }

    static net.minecraft.world.inventory.AbstractContainerMenu openStore(Body b, BlockPos at) { throw new UnsupportedOperationException(); }

    static java.util.Set<String> verbs() { return java.util.Set.of(); }

    static String actions() { return null; }

    static final String ACTIONS_NOTE = "";

    static Tasks.Task manage(String verb, BlockPos at, String what, String value) { throw new UnsupportedOperationException(); }

    static void woke(Body b) {}
}
