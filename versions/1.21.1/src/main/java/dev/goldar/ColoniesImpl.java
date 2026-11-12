package dev.goldar;

import com.ldtteam.structurize.storage.ServerFutureProcessor;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.items.component.ColonyId;
import com.minecolonies.api.items.component.HutBlockData;
import com.minecolonies.core.blocks.huts.BlockHutTownHall;
import com.minecolonies.core.event.EventHandler;
import com.minecolonies.core.network.messages.server.CreateColonyMessage;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import dev.goldar.Tasks.Outcome;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** The part of {@link Colonies} that names MineColonies' own types. Only touched when that mod is installed. */
final class ColoniesImpl {
    private ColoniesImpl() {}

    /** How long to wait for the mod to find a hut's blueprint, and for a colony to appear once asked for. */
    private static final int BLUEPRINT_TICKS = 200, FOUND_TICKS = 60;
    /** The least time between two lines the colony tells one body, and how long the same line is not repeated, in ticks. */
    private static final int TOLD_GAP_TICKS = 10, TOLD_REPEAT_TICKS = 200;

    static boolean isHut(Block block) { return block instanceof AbstractBlockHut<?>; }

    // ---- what the colony says ----

    private static boolean fromColony(Component c) {
        if (c.getContents() instanceof TranslatableContents t) {
            if (t.getKey().contains("minecolonies")) return true;
            for (Object arg : t.getArgs()) if (arg instanceof Component inner && fromColony(inner)) return true;
        }
        for (Component sibling : c.getSiblings()) if (fromColony(sibling)) return true;
        return false;
    }

    static void told(Body b, Component message) {
        if (!fromColony(message)) return;
        String text = message.getString();
        int since = b.tickCount - b.lastToldTick;
        if (since < TOLD_GAP_TICKS || text.equals(b.lastTold) && since < TOLD_REPEAT_TICKS) return;
        b.lastTold = text;
        b.lastToldTick = b.tickCount;
        Goldar.LOGGER.info("The colony told {}: {}", b.mind.seat.id, text);
        Goldar.world().told(b, "colony", text);
    }

    // ---- styles ----

    /** The style a new colony is built in: the one the config names, or the first installed by name. */
    private static StructurePackMeta style() {
        String wanted = Goldar.world().config.colony_style;
        if (wanted != null && !wanted.isBlank() && StructurePacks.hasPack(wanted)) return StructurePacks.getStructurePack(wanted);
        return StructurePacks.getPackMetas().stream().min(Comparator.comparing(StructurePackMeta::getName)).orElse(null);
    }

    // ---- placing a hut ----

    /**
     * Does what the mod's "place directly" button does for a player, then what its windows would be used for next: a
     * first town hall founds the body's colony, and a hut inside a colony is ordered from the builder.
     */
    static final class Hut implements Tasks.Task {
        private final String name;
        private final BlockPos at;
        private int stage, waited;
        private boolean looked, found;
        private String style;
        private int toldBefore;

        Hut(String name, BlockPos at) { this.name = name; this.at = at; }

        /** Takes up a hut that already stands, where placing it left off. */
        Hut(BlockPos at) {
            this("hut", at);
            stage = 1;
            looked = found = true;
        }

        /** A refusal in the colony's own words when it gave any since this began. */
        private Outcome refuse(Body b, String fallback) {
            boolean said = b.lastToldTick > toldBefore && b.lastTold != null;
            String why = said ? b.lastTold : fallback;
            // The mind is only told that its action failed, so the reason reaches it as the colony's word.
            if (!said) Goldar.world().told(b, "colony", fallback);
            Goldar.LOGGER.info("{} could not finish placing {} at {}: {}", b.mind.seat.id, name, at.toShortString(), why);
            return Outcome.fail("not_allowed", why);
        }

        @Override public Outcome tick(Body b) {
            ServerLevel level = b.level();
            IColonyManager colonies = IColonyManager.getInstance();
            switch (stage) {
                case 0 -> {
                    int slot = b.slotOf(name);
                    if (slot < 0) return Outcome.fail("missing_materials", "no " + name + " in the inventory");
                    ItemStack stack = Compat.items(b.getInventory()).get(slot);
                    if (!(stack.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof AbstractBlockHut<?> hut)) {
                        return Outcome.fail("not_possible", name + " is not a hut");
                    }
                    toldBefore = b.lastToldTick;
                    if (!EventHandler.onBlockHutPlaced(level, b, hut, at)) return refuse(b, "a hut cannot be placed here");
                    IColony colony = colonies.getColonyByPosFromWorld(level, at);
                    if (colony == null ? !(hut instanceof BlockHutTownHall) : !colony.getPermissions().hasPermission(b, Action.MANAGE_HUTS)) {
                        return refuse(b, colony == null ? "there is no colony here: a town hall comes first" : "the colony here does not let you place huts");
                    }
                    ColonyId marked = ColonyId.readFromItemStack(stack);
                    if (colony != null && marked.hasColonyId() && colony.getID() != marked.id()) return refuse(b, "this hut belongs to another colony");
                    StructurePackMeta pack = colony == null ? style() : StructurePacks.getStructurePack(colony.getStructurePack());
                    if (pack == null) pack = style();
                    if (pack == null) return Outcome.fail("not_possible", "no building style is installed");
                    StructurePackMeta chosen = pack;
                    style = chosen.getName();

                    BlockState state = hut.defaultBlockState().setValue(AbstractBlockHut.FACING, Direction.fromYRot(b.getYRot()));
                    ItemStack used = stack.copyWithCount(1);
                    b.lookAt(at.getCenter());
                    stack.shrink(1);
                    level.setBlockAndUpdate(at, state);
                    b.swing(InteractionHand.MAIN_HAND);
                    b.blockOperations++;
                    if (!(level.getBlockEntity(at) instanceof TileEntityColonyBuilding tile)) return Outcome.OK;
                    tile.setStructurePack(chosen);
                    // The hut's own blueprint in that style, as the mod looks it up: the one whose anchor is this block.
                    ServerFutureProcessor.queueBlueprint(new ServerFutureProcessor.BlueprintProcessingData(
                        StructurePacks.findBlueprintFuture(style, bp -> bp.getBlockState(bp.getPrimaryBlockOffset()).getBlock() == hut, level.registryAccess()),
                        level, blueprint -> {
                            looked = true;
                            if (blueprint == null || !(level.getBlockEntity(at) instanceof TileEntityColonyBuilding placed)) return;
                            String path = blueprint.getFilePath().toString().replace(chosen.getPath().toString() + "/", "");
                            placed.setBlueprintPath(path + "/" + blueprint.getFileName().substring(0, blueprint.getFileName().length() - 1) + "1.blueprint");
                            hut.setPlacedBy(level, at, state, b, used);
                            HutBlockData kept = HutBlockData.readFromItemStack(used);
                            IColony here = colonies.getColonyByPosFromWorld(level, at);
                            IBuilding building = here == null ? null : here.getServerBuildingManager().getBuilding(at);
                            if (kept != null && building != null) {
                                building.setBuildingLevel(kept.level());
                                building.setDeconstructed();
                            }
                            found = true;
                        }));
                    stage = 1;
                    return null;
                }
                case 1 -> {
                    if (!looked) return ++waited > BLUEPRINT_TICKS ? refuse(b, "the hut is placed, but its blueprint was not found in time") : null;
                    if (!found) return refuse(b, "the hut is placed, but the style " + style + " has no blueprint for it");
                    waited = 0;
                    if (!(level.getBlockEntity(at) instanceof TileEntityColonyBuilding tile)) return Outcome.fail("blocked", "the hut is gone");
                    if (style == null) {
                        StructurePackMeta pack = tile.getStructurePack() != null ? tile.getStructurePack() : style();
                        if (pack == null) return Outcome.fail("not_possible", "no building style is installed");
                        style = pack.getName();
                    }
                    if (tile.getBlueprintPath() == null || tile.getBlueprintPath().isEmpty()) return Outcome.fail("not_possible", "the hut has no blueprint: mine it and place it again");
                    if (colonies.getIColony(level, at) == null && level.getBlockState(at).getBlock() instanceof BlockHutTownHall) {
                        // The founding window's button, pressed for the body: the mod applies its own rules to it.
                        toldBefore = b.lastToldTick;
                        String colonyName = Compat.name(b.getGameProfile()) + "'s Colony";
                        Goldar.LOGGER.info("{} asks to found {} round the town hall at {} in the style {}", b.mind.seat.id, colonyName, at.toShortString(), style);
                        NetworkRegistry.handleModdedPayload(b.connection, new ServerboundCustomPayloadPacket(new CreateColonyMessage(at, false, colonyName, style, tile.getBlueprintPath())));
                        stage = 2;
                        return null;
                    }
                    stage = 3;
                    return null;
                }
                case 2 -> {
                    if (colonies.getIColony(level, at) != null) { stage = 3; return null; }
                    return ++waited > FOUND_TICKS ? refuse(b, "the town hall is placed, but no colony was founded") : null;
                }
                default -> {
                    IColony colony = colonies.getIColony(level, at);
                    IBuilding building = colony == null ? null : colony.getServerBuildingManager().getBuilding(at);
                    // The hut window's build button: the colony's builder takes the order when one is free.
                    if (building != null && building.getBuildingLevel() == 0 && !building.isPendingConstruction() && colony.getPermissions().hasPermission(b, Action.MANAGE_HUTS)) {
                        Goldar.LOGGER.info("{} orders the {} at {} built", b.mind.seat.id, name, at.toShortString());
                        building.requestUpgrade(b, BlockPos.ZERO);
                    } else {
                        Goldar.LOGGER.info("{} leaves the {} at {} as it is: {}", b.mind.seat.id, name, at.toShortString(),
                            building == null ? "no building is registered there" : building.isPendingConstruction() ? "its building is already ordered" : "it stands at level " + building.getBuildingLevel());
                    }
                    return Outcome.OK;
                }
            }
        }
    }
}
