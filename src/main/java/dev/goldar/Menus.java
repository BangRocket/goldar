package dev.goldar;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Inventory work done the way a player does it: open the block's screen and click its slots. The game's own rules for
 * each slot (what may go in, what a result costs, what a furnace pays out) then apply to a body as to anyone.
 */
final class Menus {
    private Menus() {}

    /** Right-clicks a block and returns the screen it opened, or null when it opened none. */
    static AbstractContainerMenu open(Body b, BlockPos at) {
        close(b);
        Vec3 centre = Vec3.atCenterOf(at);
        b.lookAt(centre);
        b.gameMode.useItemOn(b, b.level(), b.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(centre, Direction.UP, at, false));
        return b.containerMenu == b.inventoryMenu ? null : b.containerMenu;
    }

    /** Closes whatever screen is open; anything still on the cursor goes back to the inventory. */
    static void close(Body b) {
        if (b.containerMenu != b.inventoryMenu) {
            b.closeContainer();
        } else if (!b.inventoryMenu.getCarried().isEmpty()) {
            ItemStack carried = b.inventoryMenu.getCarried();
            b.inventoryMenu.setCarried(ItemStack.EMPTY);
            b.getInventory().placeItemBackInInventory(carried);
        }
    }

    /** One click on a slot of the open screen: a plain click, or a shift-click when `quick`. */
    static void click(Body b, int slot, int button, boolean quick) {
        Compat.click(b.containerMenu, slot, button, quick, b);
        b.containerMenu.broadcastChanges();
    }

    /** True for the 36 slots of the body's own inventory, in any screen. */
    static boolean own(Body b, Slot s) { return s.container == b.getInventory() && s.getContainerSlot() < 36; }

    /**
     * Moves up to `count` items matching `what` from the slots matching `from` into the slots matching `to`: pick the
     * stack up, put it down whole or one at a time, and return the rest. Returns how many moved.
     */
    static int move(Body b, Predicate<Slot> from, Predicate<Slot> to, Predicate<ItemStack> what, int count) {
        AbstractContainerMenu menu = b.containerMenu;
        int moved = 0;
        for (int s = 0; s < menu.slots.size() && moved < count; s++) {
            Slot src = menu.slots.get(s);
            if (!from.test(src) || !src.hasItem() || !what.test(src.getItem()) || !src.mayPickup(b)) continue;
            click(b, s, 0, false);
            for (int d = 0; d < menu.slots.size() && moved < count && !menu.getCarried().isEmpty(); d++) {
                Slot dst = menu.slots.get(d);
                ItemStack carried = menu.getCarried();
                if (d == s || !to.test(dst) || !dst.mayPlace(carried)) continue;
                ItemStack there = dst.getItem();
                if (!there.isEmpty() && (!ItemStack.isSameItemSameComponents(there, carried) || there.getCount() >= dst.getMaxStackSize(there))) continue;
                int before = carried.getCount();
                if (before <= count - moved) {
                    click(b, d, 0, false);
                } else {
                    for (int n = count - moved; n > 0; n--) {
                        int held = menu.getCarried().getCount();
                        click(b, d, 1, false);
                        if (menu.getCarried().getCount() == held) break;
                    }
                }
                moved += before - menu.getCarried().getCount();
            }
            if (!menu.getCarried().isEmpty()) click(b, s, 0, false);
        }
        return moved;
    }
}
