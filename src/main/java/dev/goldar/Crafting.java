package dev.goldar;

import dev.goldar.Tasks.Outcome;
import dev.goldar.Tasks.Task;
import dev.goldar.Tasks.Walk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Making things: from the inventory, at a crafting table, or in a furnace. Recipes come from the server's own book, and
 * the work is done by clicking through the game's own screens.
 */
public final class Crafting {
    private Crafting() {}

    private static final int STATION_RANGE = 32;
    private static final double REACH = 4.5;
    private static final int SMELT_TICKS = 200;
    private static final int MAX_SMELT = 16;

    /** A crafting recipe laid out on a grid. */
    private record Grid(RecipeHolder<?> holder, CraftingRecipe recipe, int width, int height, List<Optional<Ingredient>> cells) {
        boolean needsTable() {
            if (recipe instanceof ShapedRecipe) return width > 2 || height > 2;
            return cells.stream().filter(Optional::isPresent).count() > 4;
        }

        CraftingInput input(List<ItemStack> stacks) { return CraftingInput.of(width, height, stacks); }
    }

    private static Map<String, List<Grid>> byResult;

    private static Grid grid(RecipeHolder<?> holder) {
        if (holder.value() instanceof ShapedRecipe shaped) return new Grid(holder, shaped, shaped.getWidth(), shaped.getHeight(), Compat.cells(shaped));
        if (holder.value() instanceof ShapelessRecipe shapeless) {
            List<Optional<Ingredient>> cells = new ArrayList<>(Compat.cells(shapeless));
            if (cells.size() > 9) return null;
            while (cells.size() < 9) cells.add(Optional.empty());
            return new Grid(holder, shapeless, 3, 3, cells);
        }
        return null;
    }

    /** Every grid recipe by the item it makes, worked out once by assembling each with its first listed ingredients. */
    private static Map<String, List<Grid>> recipes(ServerLevel level) {
        if (byResult != null) return byResult;
        Map<String, List<Grid>> map = new HashMap<>();
        for (RecipeHolder<?> holder : level.getServer().getRecipeManager().getRecipes()) {
            Grid g = grid(holder);
            if (g == null) continue;
            try {
                List<ItemStack> ideal = new ArrayList<>();
                for (Optional<Ingredient> cell : g.cells) {
                    ideal.add(cell.flatMap(Compat::first).orElse(ItemStack.EMPTY));
                }
                CraftingInput input = g.input(ideal);
                if (!g.recipe.matches(input, level)) continue;
                ItemStack made = Compat.assemble(g.recipe, input, level);
                if (!made.isEmpty()) map.computeIfAbsent(Body.name(made), k -> new ArrayList<>()).add(g);
            } catch (RuntimeException e) {
                // A recipe that can't be assembled outside a real grid is left out.
            }
        }
        byResult = map;
        return map;
    }

    /** The items a body has a crafting recipe unlocked for, sorted, at most 1500. */
    static List<String> unlocked(Body b) {
        List<String> out = new ArrayList<>();
        var book = b.getRecipeBook();
        recipes(Tasks.level(b)).forEach((item, grids) -> {
            for (Grid g : grids) if (Compat.knows(b, g.holder)) { out.add(item); break; }
        });
        java.util.Collections.sort(out);
        return out.size() > 1500 ? out.subList(0, 1500) : out;
    }

    /** Forgets the recipe index, for when the server reloads its data. */
    public static void reset() { byResult = null; }

    private static String firstName(Ingredient i) {
        return Compat.first(i).map(Body::name).orElse("an ingredient");
    }

    /** Crafts or smelts `count` of an item. */
    public static final class Craft implements Task {
        private final String item;
        private final int count;
        private int made;
        private Walk walk;
        private Smelt smelt;
        private Task strip;

        public Craft(String item, int count) { this.item = item; this.count = count; }

        /** Makes the recipe once by laying it out on the open grid. Returns null on success, else what is missing. */
        private String once(Body b, Grid g) {
            // Check first that the inventory covers every cell, so a grid is never left half laid.
            var items = Compat.items(b.getInventory());
            int[] left = new int[items.size()];
            for (int i = 0; i < left.length; i++) left[i] = items.get(i).getCount();
            for (Optional<Ingredient> cell : g.cells) {
                if (cell.isEmpty()) continue;
                int slot = -1;
                for (int i = 0; i < left.length && slot < 0; i++) if (left[i] > 0 && cell.get().test(items.get(i))) slot = i;
                if (slot < 0) return firstName(cell.get());
                left[slot]--;
            }
            AbstractContainerMenu menu = b.containerMenu;
            List<Slot> grid = Compat.grid(menu);
            boolean shaped = g.recipe instanceof ShapedRecipe;
            int placed = 0;
            String lack = null;
            for (int c = 0; c < g.cells.size() && lack == null; c++) {
                if (g.cells.get(c).isEmpty()) continue;
                Ingredient wanted = g.cells.get(c).get();
                Slot cell = grid.get(shaped ? (c / g.width) * Compat.gridWidth(menu) + c % g.width : placed);
                placed++;
                if (Menus.move(b, s -> Menus.own(b, s), s -> s == cell, wanted::test, 1) < 1) lack = firstName(wanted);
            }
            Slot result = Compat.result(menu);
            if (lack == null && (!result.hasItem() || !Body.name(result.getItem()).equals(item))) lack = "the right ingredients";
            if (lack == null) {
                int before = b.count(item);
                Menus.click(b, result.index, 0, true);
                if (b.count(item) > before) made += b.count(item) - before; else lack = "room in the inventory";
            }
            // What the recipe left on the grid (an empty bucket, or everything after a failure) goes back.
            for (Slot cell : grid) if (cell.hasItem()) Menus.click(b, cell.index, 0, true);
            return lack;
        }

        @Override public Outcome tick(Body b) {
            ServerLevel level = Tasks.level(b);
            if (smelt != null) return smelt.tick(b);
            if (strip != null) return strip.tick(b);
            if (walk != null) {
                Outcome o = walk.tick(b);
                if (o == null) return null;
                walk = null;
                if (!o.ok()) return o;
            }
            List<Grid> all = recipes(level).getOrDefault(item, List.of());
            List<Grid> known = new ArrayList<>();
            for (Grid g : all) if (!Goldar.world().config.require_unlocked_recipes || Compat.knows(b, g.holder)) known.add(g);
            String missing = null;
            boolean wantsTable = false;
            BlockPos table = nearest(b, "crafting_table");
            boolean inReach = table != null && b.getEyePosition().distanceTo(Vec3.atCenterOf(table)) <= REACH;
            boolean atTable = false;
            try {
                while (made < count) {
                    boolean progressed = false;
                    for (Grid g : known) {
                        if (g.needsTable() && !atTable) {
                            atTable = inReach && Compat.isCrafting(Menus.open(b, table));
                            if (!atTable) { wantsTable = true; continue; }
                        }
                        String lack = once(b, g);
                        if (lack == null) { progressed = true; break; }
                        if (missing == null) missing = lack;
                    }
                    if (!progressed) break;
                }
            } finally {
                Menus.close(b);
            }
            if (made >= count) return Outcome.OK;
            if (made == 0 && wantsTable && !inReach) {
                if (table == null) return Outcome.fail("missing_station", "needs a crafting table");
                walk = new Walk(table, 3);
                return null;
            }
            // A stripped log or wood is not crafted: an axe is used on the block itself.
            if (made == 0 && all.isEmpty() && item.startsWith("stripped_") && Compat.block(item.substring("stripped_".length())).isPresent()) {
                strip = new Strip(item, count);
                return strip.tick(b);
            }
            // Nothing on a grid makes it: a furnace might.
            if (made == 0 && Goldar.world().config.extra_actions && Smelt.recipeFor(b, item) != null) {
                smelt = new Smelt(item, count);
                return smelt.tick(b);
            }
            if (all.isEmpty()) return Outcome.fail("not_possible", "not craftable");
            if (known.isEmpty()) return Outcome.fail("not_possible", "the recipe for " + item + " is not unlocked");
            return Outcome.fail("missing_materials", "missing " + (missing == null ? "materials" : missing));
        }
    }

    /**
     * Strips logs as a player does: sets a log down beside the body, uses an axe on it, then breaks the stripped block and
     * picks it up. One at a time, until `count` are made.
     */
    static final class Strip implements Task {
        private final String item, source;
        private final int count;
        private int made, stage, misplaced;
        private BlockPos spot;
        private Task doing;

        Strip(String item, int count) { this.item = item; this.source = item.substring("stripped_".length()); this.count = count; }

        private static int axe(Body b) {
            var items = Compat.items(b.getInventory());
            for (int i = 0; i < items.size(); i++) if (!items.get(i).isEmpty() && Body.name(items.get(i)).endsWith("_axe")) return i;
            return -1;
        }

        @Override public Outcome tick(Body b) {
            ServerLevel level = Tasks.level(b);
            if (doing != null) {
                Outcome o = doing.tick(b);
                if (o == null) return null;
                doing = null;
                // A spot the log would not go on: try another, a few times.
                if (!o.ok() && stage == 1 && ++misplaced <= 3) { stage = 0; return null; }
                if (!o.ok()) return made > 0 ? Outcome.fail(o.reason(), "stripped " + made + " of " + count + ": " + o.message()) : o;
                if (stage == 2) { made++; stage = 0; misplaced = 0; }
            }
            if (made >= count) return Outcome.OK;
            if (axe(b) < 0) return Outcome.fail("missing_materials", "missing an axe");
            switch (stage) {
                case 0 -> {
                    if (b.count(source) < 1) return Outcome.fail("missing_materials", "missing " + source);
                    spot = Skills.freeSpot(b);
                    if (spot == null) return Outcome.fail("blocked", "no free spot to set the " + source + " down");
                    doing = new Tasks.Place(source, spot);
                    stage = 1;
                    return null;
                }
                case 1 -> {
                    if (!Body.name(level.getBlockState(spot)).equals(source)) return Outcome.fail("blocked", "the " + source + " was not set down");
                    b.hold(axe(b));
                    Vec3 top = Vec3.atCenterOf(spot).add(0, 0.5, 0);
                    b.lookAt(top);
                    b.gameMode.useItemOn(b, level, b.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(top, net.minecraft.core.Direction.UP, spot, false));
                    b.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    if (!Body.name(level.getBlockState(spot)).equals(item)) return Outcome.fail("not_possible", source + " does not strip into " + item);
                    // Break it and pick it up: the axe is the fastest tool for it.
                    doing = new Tasks.Mine(item, 1, 4, item, b.count(item) + 1);
                    stage = 2;
                    return null;
                }
                default -> { return Outcome.fail("not_possible", "stripping went wrong"); }
            }
        }

        @Override public void stop(Body b) { if (doing != null) doing.stop(b); else b.halt(); }
    }

    static BlockPos nearest(Body b, String block) {
        ServerLevel level = Tasks.level(b);
        return BlockPos.findClosestMatch(b.blockPosition(), STATION_RANGE, 8, p -> Body.name(level.getBlockState(p)).equals(block))
            .map(BlockPos::immutable).orElse(null);
    }

    /** One furnace load: put the input and fuel in, wait, take the product. */
    static final class Smelt implements Task {
        private final String item;
        private final int count;
        private Walk walk;
        private BlockPos at;
        private boolean loaded;
        private int ticks, before;

        Smelt(String item, int count) { this.item = item; this.count = count; }

        /** The inventory slot whose stack smelts into `item`, or null when nothing held does. */
        static Integer recipeFor(Body b, String item) {
            ServerLevel level = Tasks.level(b);
            var items = Compat.items(b.getInventory());
            for (int i = 0; i < items.size(); i++) {
                ItemStack s = items.get(i);
                if (s.isEmpty()) continue;
                SingleRecipeInput input = new SingleRecipeInput(s);
                for (RecipeHolder<?> h : level.getServer().getRecipeManager().getRecipes()) {
                    if (h.value() instanceof SmeltingRecipe r && r.matches(input, level) && Body.name(Compat.assemble(r, input, level)).equals(item)) return i;
                }
            }
            return null;
        }

        @Override public Outcome tick(Body b) {
            ServerLevel level = Tasks.level(b);
            if (count > MAX_SMELT) return Outcome.fail("invalid_args", "a furnace makes at most " + MAX_SMELT + " in one action");
            if (at == null) {
                at = nearest(b, "furnace");
                if (at == null) return Outcome.fail("missing_station", "needs a furnace");
                walk = new Walk(at, 3);
            }
            if (walk != null) {
                Outcome o = walk.tick(b);
                if (o == null) return null;
                walk = null;
                if (!o.ok()) return o;
            }
            if (!(level.getBlockEntity(at) instanceof AbstractFurnaceBlockEntity furnace)) return Outcome.fail("missing_station", "the furnace is gone");
            if (!loaded) {
                Integer slot = recipeFor(b, item);
                if (slot == null) return Outcome.fail("missing_materials", "nothing to smelt into " + item);
                var items = Compat.items(b.getInventory());
                ItemStack input = items.get(slot);
                if (input.getCount() < count) return Outcome.fail("missing_materials", "missing " + Body.name(input));
                if (!(Menus.open(b, at) instanceof AbstractFurnaceMenu menu)) { Menus.close(b); return Outcome.fail("missing_station", "the furnace would not open"); }
                try {
                    Slot in = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT), fuelIn = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT), out = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT);
                    if (out.hasItem() && !Body.name(out.getItem()).equals(item)) return Outcome.fail("blocked", "the furnace holds " + Body.name(out.getItem()));
                    if (in.hasItem() && !ItemStack.isSameItemSameComponents(in.getItem(), input)) return Outcome.fail("blocked", "the furnace is smelting " + Body.name(in.getItem()));
                    // Fuel: coal first, then whatever burns, never the thing being smelted.
                    if (!fuelIn.hasItem()) {
                        int fuelSlot = -1;
                        for (String preferred : new String[] {"coal", "charcoal"}) if (fuelSlot < 0) fuelSlot = b.slotOf(preferred);
                        for (int i = 0; i < items.size() && fuelSlot < 0; i++) {
                            if (i != slot && !items.get(i).isEmpty() && Compat.isFuel(level, items.get(i)) && !items.get(i).isDamageableItem()) fuelSlot = i;
                        }
                        if (fuelSlot < 0) return Outcome.fail("missing_materials", "missing fuel");
                        ItemStack fuel = items.get(fuelSlot);
                        String fuelName = Body.name(fuel);
                        int need = (int) Math.ceil(count * (double) SMELT_TICKS / Math.max(1, Compat.burnTicks(level, fuel)));
                        if (b.count(fuelName) < need) return Outcome.fail("missing_materials", "missing fuel: " + need + " " + fuelName + " needed");
                        Menus.move(b, s -> Menus.own(b, s), s -> s == fuelIn, x -> Body.name(x).equals(fuelName), need);
                    }
                    before = out.getItem().getCount();
                    ItemStack kind = input.copy();
                    int put = Menus.move(b, s -> Menus.own(b, s), s -> s == in, x -> ItemStack.isSameItemSameComponents(x, kind), count);
                    if (put < count) return Outcome.fail("blocked", "the furnace took only " + put + " of " + count);
                } finally {
                    Menus.close(b);
                }
                loaded = true;
                return null;
            }
            boolean ready = furnace.getItem(AbstractFurnaceMenu.RESULT_SLOT).getCount() - before >= count;
            if (!ready && ++ticks < count * SMELT_TICKS + 200) return null;
            int held = b.count(item);
            if (Menus.open(b, at) instanceof AbstractFurnaceMenu menu && menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).hasItem()) {
                Menus.click(b, AbstractFurnaceMenu.RESULT_SLOT, 0, true);
            }
            Menus.close(b);
            int take = b.count(item) - held;
            return take >= count ? Outcome.OK : Outcome.fail("timeout", "the furnace made " + take + " of " + count + " " + item);
        }
    }
}
