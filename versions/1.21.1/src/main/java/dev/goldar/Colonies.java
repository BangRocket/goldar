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

    /** True for a hut or a colony rack, whose store opens from a window button and not by a click. */
    static boolean isStore(Body b, BlockPos at) { return present() && ColoniesImpl.isStore(b, at); }

    /** Where a hut keeps things: its own block first, then the racks of its building. A lone rack is just itself. */
    static java.util.List<BlockPos> storesOf(Body b, BlockPos at) { return present() ? ColoniesImpl.storesOf(b, at) : java.util.List.of(at); }

    /**
     * How many of an item a hut takes: what its builder still lacks of it, none when the item is not on the builder's
     * list, and no limit when the hut has no builder's list.
     */
    static int takes(Body b, BlockPos at, String item) { return present() ? ColoniesImpl.takes(b, at, item) : Integer.MAX_VALUE; }

    /** Opens a hut's or rack's store as that button does. Null when the colony does not allow it. */
    static net.minecraft.world.inventory.AbstractContainerMenu openStore(Body b, BlockPos at) { return ColoniesImpl.openStore(b, at); }

    private static final java.util.Set<String> VERBS = java.util.Set.of("hire", "dismiss", "set");

    /** The verbs a colony adds to what a thought may do. */
    static java.util.Set<String> verbs() { return present() ? VERBS : java.util.Set.of(); }

    private static final String NAME = "{\"type\":\"string\",\"minLength\":1,\"maxLength\":64}";
    private static final String ACTIONS = "["
        + "{\"type\":\"object\",\"properties\":{\"do\":{\"const\":\"hire\"},\"at\":{\"$ref\":\"#/$defs/Position\"},\"citizen\":" + NAME + "},\"required\":[\"do\",\"at\",\"citizen\"],\"additionalProperties\":false},"
        + "{\"type\":\"object\",\"properties\":{\"do\":{\"const\":\"dismiss\"},\"at\":{\"$ref\":\"#/$defs/Position\"},\"citizen\":" + NAME + "},\"required\":[\"do\",\"at\",\"citizen\"],\"additionalProperties\":false},"
        + "{\"type\":\"object\",\"properties\":{\"do\":{\"const\":\"set\"},\"at\":{\"$ref\":\"#/$defs/Position\"},\"setting\":" + NAME + ",\"value\":" + NAME + "},\"required\":[\"do\",\"at\",\"setting\",\"value\"],\"additionalProperties\":false}]";

    /** The schemas of those verbs, as a JSON array to join the contract's actions. Null without the mod. */
    static String actions() { return present() ? ACTIONS : null; }

    /** What the contract's description of `act` gains when those verbs are there. */
    static final String ACTIONS_NOTE = " In a colony: open a hut to hear its state; deposit and withdraw work on huts and racks; "
        + "{do:hire|dismiss, at:<hut>, citizen:<name>} assigns a worker; {do:set, at:<hut>, setting:<name>, value:<text>} changes a hut setting, "
        + "and the setting hiring takes auto, manual or default.";

    /** The task for one of those verbs at a hut. `value` is null for hire and dismiss. */
    static Tasks.Task manage(String verb, BlockPos at, String what, String value) { return new ColoniesImpl.Manage(verb, at, what, value); }

    /** A body woke: every mind is made an officer of the colonies minds own, when the config shares them. */
    static void woke(Body b) {
        if (!present()) return;
        // A body must wake whatever state the colonies are in.
        try {
            ColoniesImpl.shareAll();
        } catch (RuntimeException e) {
            Goldar.LOGGER.warn("Could not share the colonies with {}", b.mind.seat.id, e);
        }
    }

    /** A system message reached the body. What the colony says is passed to the mind as something heard. */
    static void told(Body b, Component message) {
        if (present()) ColoniesImpl.told(b, message);
    }
}
