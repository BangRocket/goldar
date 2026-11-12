package dev.goldar;

import net.minecraft.core.BlockPos;

/** What the pathfinder needs to know about the world. Keeps the search independent of a running game. */
public interface Terrain {
    /** The player's body can occupy this cell without harm. */
    boolean clear(BlockPos p);

    boolean water(BlockPos p);

    boolean climb(BlockPos p);

    /** This cell can be stood on top of. */
    boolean support(BlockPos p);

    /** A scaffold block could be placed in this cell. */
    boolean replaceable(BlockPos p);

    /** Extra path cost for standing here because of what's next to it. */
    default double danger(BlockPos p) { return 0; }

    /** Path cost of breaking this block, or infinity if it must not be broken. */
    double digCost(BlockPos p);
}
