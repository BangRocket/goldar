package dev.goldar;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** The game calls that differ between Minecraft versions, as Minecraft 26.1 spells them. */
final class Compat {
    private Compat() {}

    static List<ItemStack> items(Inventory inv) { return inv.getNonEquipmentItems(); }

    static int selected(Inventory inv) { return inv.getSelectedSlot(); }

    static void select(Inventory inv, int slot) { inv.setSelectedSlot(slot); }

    static String name(GameProfile profile) { return profile.name(); }

    /** The block a mind names. Names carry no namespace, so the game's own block comes first, then any mod's of that name. */
    static Optional<Block> block(String name) {
        Optional<Block> own = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(name));
        if (own.isPresent()) return own;
        for (var key : BuiltInRegistries.BLOCK.keySet()) if (key.getPath().equals(name)) return BuiltInRegistries.BLOCK.getOptional(key);
        return Optional.empty();
    }

    static Vec3 unit(Direction d) { return Vec3.atLowerCornerOf(d.getUnitVec3i()); }

    static String biome(ServerLevel level, BlockPos at) { return level.getBiome(at).unwrapKey().map(k -> k.identifier().getPath()).orElse(""); }

    static long dayTime(Level level) { return level.getDefaultClockTime(); }

    static BlockPos worldSpawn(ServerLevel level) { return level.getRespawnData().pos(); }

    /** The bed the player would come back to, when it is in this dimension and still stands. */
    static BlockPos respawnBed(ServerPlayer p) {
        var respawn = p.getRespawnConfig();
        if (respawn == null || respawn.respawnData().globalPos().dimension() != p.level().dimension()) return null;
        BlockPos at = respawn.respawnData().pos();
        return p.level().getBlockState(at).is(BlockTags.BEDS) ? at : null;
    }

    static Predicate<CommandSourceStack> owners() { return Commands.hasPermission(Commands.LEVEL_OWNERS); }

    /** One click on a slot of the open screen: a plain click, or a shift-click when `quick`. */
    static void click(AbstractContainerMenu menu, int slot, int button, boolean quick, Player player) {
        menu.clicked(slot, button, quick ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, player);
    }

    static boolean isCrafting(AbstractContainerMenu menu) { return menu instanceof AbstractCraftingMenu; }

    static List<Slot> grid(AbstractContainerMenu menu) { return ((AbstractCraftingMenu) menu).getInputGridSlots(); }

    static int gridWidth(AbstractContainerMenu menu) { return ((AbstractCraftingMenu) menu).getGridWidth(); }

    static Slot result(AbstractContainerMenu menu) { return ((AbstractCraftingMenu) menu).getResultSlot(); }

    /** A shaped recipe's cells, row by row; an empty cell is absent. */
    static List<Optional<Ingredient>> cells(ShapedRecipe recipe) { return recipe.getIngredients(); }

    static List<Optional<Ingredient>> cells(ShapelessRecipe recipe) {
        List<Optional<Ingredient>> cells = new ArrayList<>();
        for (Ingredient i : recipe.placementInfo().ingredients()) cells.add(Optional.of(i));
        return cells;
    }

    /** One item the ingredient accepts. */
    static Optional<ItemStack> first(Ingredient i) { return i.items().findFirst().map(h -> new ItemStack(h.value())); }

    static <I extends RecipeInput> ItemStack assemble(Recipe<I> recipe, I input, ServerLevel level) { return recipe.assemble(input); }

    static boolean knows(ServerPlayer p, RecipeHolder<?> holder) { return p.getRecipeBook().contains(holder.id()); }

    static boolean isFuel(ServerLevel level, ItemStack stack) { return level.fuelValues().isFuel(stack); }

    static int burnTicks(ServerLevel level, ItemStack stack) { return level.fuelValues().burnDuration(stack); }
}
