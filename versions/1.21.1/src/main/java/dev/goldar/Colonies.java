package dev.goldar;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

/**
 * What a body needs to live in a MineColonies world. That mod does its work through screens a body has no client to
 * show: a hut is set down, a colony founded and a building ordered from windows. Here the body does the same things on
 * the server. Nothing in this class names the mod's own types, so it loads whether or not the mod is installed.
 */
final class Colonies {
    private Colonies() {}

    private static Boolean present;

    static boolean present() {
        if (present == null) present = ModList.get().isLoaded("minecolonies");
        return present;
    }

    /** True for a hut block, which the mod will not let a plain right-click place. */
    static boolean isHut(Block block) { return present() && ColoniesImpl.isHut(block); }

    /** Sets a hut down at a free cell within reach, founds a colony round a first town hall, and orders the hut built. */
    static Tasks.Task hut(String block, BlockPos at) { return new ColoniesImpl.Hut(block, at); }

    /** Finishes for a hut that already stands what placing it would have done: the founding, and the order to build. */
    static Tasks.Task settle(BlockPos at) { return new ColoniesImpl.Hut(at); }

    /** A system message reached the body. What the colony says is passed to the mind as something heard. */
    static void told(Body b, Component message) {
        if (present()) ColoniesImpl.told(b, message);
    }
}
