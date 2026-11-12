package dev.goldar;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** The game calls that differ between Minecraft versions, as Minecraft 1.21.1 spells them. */
final class Compat {
    private Compat() {}

    static List<ItemStack> items(Inventory inv) { return inv.items; }

    static int selected(Inventory inv) { return inv.selected; }

    static void select(Inventory inv, int slot) { inv.selected = slot; }

    static String name(GameProfile profile) { return profile.getName(); }

    /** The block a mind names. Names carry no namespace, so the game's own block comes first, then any mod's of that name. */
    static Optional<Block> block(String name) {
        Optional<Block> own = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.withDefaultNamespace(name));
        if (own.isPresent()) return own;
        for (var key : BuiltInRegistries.BLOCK.keySet()) if (key.getPath().equals(name)) return BuiltInRegistries.BLOCK.getOptional(key);
        return Optional.empty();
    }

    static Vec3 unit(Direction d) { return Vec3.atLowerCornerOf(d.getNormal()); }

    static String biome(ServerLevel level, BlockPos at) { return level.getBiome(at).unwrapKey().map(k -> k.location().getPath()).orElse(""); }

    static long dayTime(Level level) { return level.getDayTime(); }

    static BlockPos worldSpawn(ServerLevel level) { return level.getSharedSpawnPos(); }

    /** The bed the player would come back to, when it is in this dimension and still stands. */
    static BlockPos respawnBed(ServerPlayer p) {
        BlockPos at = p.getRespawnPosition();
        if (at == null || p.getRespawnDimension() != p.level().dimension()) return null;
        return p.level().getBlockState(at).is(BlockTags.BEDS) ? at : null;
    }

    static Predicate<CommandSourceStack> owners() { return source -> source.hasPermission(4); }

    /** One click on a slot of the open screen: a plain click, or a shift-click when `quick`. */
    static void click(AbstractContainerMenu menu, int slot, int button, boolean quick, Player player) {
        menu.clicked(slot, button, quick ? ClickType.QUICK_MOVE : ClickType.PICKUP, player);
    }

    static boolean isCrafting(AbstractContainerMenu menu) { return menu instanceof CraftingMenu || menu instanceof InventoryMenu; }

    /** The grid's slots follow the result slot in both crafting screens. */
    static List<Slot> grid(AbstractContainerMenu menu) {
        RecipeBookMenu<?, ?> crafting = (RecipeBookMenu<?, ?>) menu;
        List<Slot> slots = new ArrayList<>();
        for (int i = 1; i <= crafting.getGridWidth() * crafting.getGridHeight(); i++) slots.add(menu.getSlot(i));
        return slots;
    }

    static int gridWidth(AbstractContainerMenu menu) { return ((RecipeBookMenu<?, ?>) menu).getGridWidth(); }

    static Slot result(AbstractContainerMenu menu) { return menu.getSlot(((RecipeBookMenu<?, ?>) menu).getResultSlotIndex()); }

    private static List<Optional<Ingredient>> cells(List<Ingredient> ingredients) {
        List<Optional<Ingredient>> cells = new ArrayList<>();
        for (Ingredient i : ingredients) cells.add(i.isEmpty() ? Optional.empty() : Optional.of(i));
        return cells;
    }

    /** A shaped recipe's cells, row by row; an empty cell is absent. */
    static List<Optional<Ingredient>> cells(ShapedRecipe recipe) { return cells(recipe.getIngredients()); }

    static List<Optional<Ingredient>> cells(ShapelessRecipe recipe) { return cells(recipe.getIngredients()); }

    /** One item the ingredient accepts. */
    static Optional<ItemStack> first(Ingredient i) {
        ItemStack[] items = i.getItems();
        return items.length == 0 ? Optional.empty() : Optional.of(items[0].copy());
    }

    static <I extends RecipeInput> ItemStack assemble(Recipe<I> recipe, I input, ServerLevel level) { return recipe.assemble(input, level.registryAccess()); }

    static boolean knows(ServerPlayer p, RecipeHolder<?> holder) { return p.getRecipeBook().contains(holder); }

    static boolean isFuel(ServerLevel level, ItemStack stack) { return stack.getBurnTime(RecipeType.SMELTING) > 0; }

    static int burnTicks(ServerLevel level, ItemStack stack) { return stack.getBurnTime(RecipeType.SMELTING); }
}
