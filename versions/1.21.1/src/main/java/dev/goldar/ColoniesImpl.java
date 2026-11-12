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
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.modules.settings.ISetting;
import com.minecolonies.api.colony.buildings.modules.settings.ISettingKey;
import com.minecolonies.core.colony.buildings.modules.BuildingResourcesModule;
import com.minecolonies.core.colony.buildings.modules.SettingsModule;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.modules.settings.IntSetting;
import com.minecolonies.core.colony.buildings.modules.settings.StringSetting;
import com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource;
import com.minecolonies.core.tileentities.TileEntityRack;
import dev.goldar.Tasks.Outcome;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
                        try {
                            NetworkRegistry.handleModdedPayload(b.connection, new ServerboundCustomPayloadPacket(new CreateColonyMessage(at, false, colonyName, style, tile.getBlueprintPath())));
                        } catch (RuntimeException e) {
                            Goldar.LOGGER.warn("Founding {} for {} failed inside MineColonies", colonyName, b.mind.seat.id, e);
                            return refuse(b, "the town hall is placed, but founding the colony failed: " + e);
                        }
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
                    if (colony != null) share(colony);
                    IBuilding building = colony == null ? null : colony.getServerBuildingManager().getBuilding(at);
                    // The hut window's build button: the colony's builder takes the order when one is free.
                    if (building != null && building.getBuildingLevel() == 0 && !building.isPendingConstruction() && colony.getPermissions().hasPermission(b, Action.MANAGE_HUTS)) {
                        Goldar.LOGGER.info("{} orders the {} at {} built", b.mind.seat.id, name, at.toShortString());
                        building.requestUpgrade(b, BlockPos.ZERO);
                    } else if (building != null && name.equals("hut")) {
                        // Opening a hut that needs nothing done is a look inside it.
                        if (colony.getPermissions().hasPermission(b, Action.ACCESS_HUTS)) report(b, colony, building);
                        else Goldar.world().told(b, "colony", "You may not open huts in " + colony.getName() + ".");
                    } else {
                        Goldar.LOGGER.info("{} leaves the {} at {} as it is: {}", b.mind.seat.id, name, at.toShortString(),
                            building == null ? "no building is registered there" : building.isPendingConstruction() ? "its building is already ordered" : "it stands at level " + building.getBuildingLevel());
                    }
                    return Outcome.OK;
                }
            }
        }
    }

    // ---- one colony for all minds ----

    /** Makes every mind an officer of this colony, when a mind owns it and the config shares colonies. */
    static void share(IColony colony) {
        GoldarConfig config = Goldar.world().config;
        if (!config.colony_shared) return;
        var permissions = colony.getPermissions();
        List<GoldarConfig.Player> players = new ArrayList<>(config.players);
        boolean ours = false;
        for (GoldarConfig.Player p : players) ours |= Body.uuid(p.id).equals(permissions.getOwner());
        if (!ours) return;
        for (GoldarConfig.Player p : players) {
            UUID id = Body.uuid(p.id);
            if (id.equals(permissions.getOwner()) || permissions.getPlayers().containsKey(id)) continue;
            if (permissions.addPlayer(id, p.name, permissions.getRankOfficer())) Goldar.LOGGER.info("{} is now an officer of {}", p.id, colony.getName());
        }
    }

    static void shareAll() {
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) share(colony);
    }

    // ---- the store of a hut or rack ----

    static boolean isStore(Body b, BlockPos at) { return b.level().getBlockEntity(at) instanceof TileEntityRack; }

    /** Opens the store as the window's inventory button does. Null when the colony does not let this body in. */
    static AbstractContainerMenu openStore(Body b, BlockPos at) {
        Menus.close(b);
        if (!(b.level().getBlockEntity(at) instanceof TileEntityRack rack)) return null;
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(b.level(), at);
        if (colony != null && !colony.getPermissions().hasPermission(b, Action.ACCESS_HUTS)) {
            Goldar.world().told(b, "colony", "You may not open this in " + colony.getName() + ".");
            return null;
        }
        b.lookAt(at.getCenter());
        int id = colony == null ? 0 : colony.getID();
        b.openMenu(rack, buf -> buf.writeVarInt(id).writeBlockPos(at));
        return b.containerMenu == b.inventoryMenu ? null : b.containerMenu;
    }

    // ---- what a hut's window would show ----

    private static String plain(String key) { return key == null ? "" : Component.translatable(key).getString(); }

    /** The last part of a dotted name, in lower case: how a setting's choice is told and taken. */
    private static String last(String s) {
        int dot = s.lastIndexOf('.');
        return (dot >= 0 ? s.substring(dot + 1) : s).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<ISettingKey<?>, ISetting<?>> settings(SettingsModule module) {
        try {
            var field = SettingsModule.class.getDeclaredField("settings");
            field.setAccessible(true);
            return (Map<ISettingKey<?>, ISetting<?>>) field.get(module);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Map.of();
        }
    }

    /** A setting as "name=value", with its choices when it has a fixed list. Null for a kind a body cannot change. */
    private static String describe(ISettingKey<?> key, ISetting<?> setting) {
        String name = key.getUniqueId().getPath();
        try {
            if (setting instanceof BoolSetting bool) return name + "=" + bool.getValue();
            if (setting instanceof IntSetting number) return name + "=" + number.getValue();
            if (setting instanceof StringSetting text) {
                List<String> choices = new ArrayList<>();
                for (String choice : text.getSettings()) choices.add(last(choice));
                return name + "=" + last(text.getValue()) + " (" + String.join("|", choices) + ")";
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** Tells a list in as many lines as it needs, each short enough to be heard whole, and no more than three. */
    private static void list(List<String> lines, String lead, List<String> parts, String sep) {
        if (parts.isEmpty()) return;
        StringBuilder line = new StringBuilder(lead);
        int told = 0;
        for (String part : parts) {
            if (line.length() + part.length() + sep.length() > Voice.MAX_LINE_CHARS - 8 && line.length() > lead.length()) {
                lines.add(line + " ...");
                if (++told == 3) return;
                line = new StringBuilder(lead);
            }
            if (line.length() > lead.length()) line.append(sep);
            line.append(part);
        }
        lines.add(line + ".");
    }

    private static List<String> idle(IColony colony) {
        List<String> names = new ArrayList<>();
        for (ICitizenData c : colony.getCitizenManager().getCitizens()) if (c.getJob() == null && !c.isChild()) names.add(c.getName());
        return names;
    }

    /** Tells the mind what the hut's window shows a player: its level, who works there, what its builder lacks, its settings. */
    static void report(Body b, IColony colony, IBuilding building) {
        List<String> lines = new ArrayList<>();
        BlockPos at = building.getID();
        lines.add(plain(building.getBuildingDisplayName()) + " at " + at.toShortString() + " in " + colony.getName() + ": level " + building.getBuildingLevel()
            + " of " + building.getMaxBuildingLevel() + (building.isPendingConstruction() ? ", building ordered" : "") + ".");
        List<WorkerBuildingModule> jobs = building.getModules(WorkerBuildingModule.class);
        for (WorkerBuildingModule job : jobs) {
            List<String> names = new ArrayList<>();
            for (ICitizenData c : job.getAssignedCitizen()) names.add(c.getName());
            lines.add("Workers (" + plain(job.getJobEntry().getTranslationKey()) + "): " + (names.isEmpty() ? "none" : String.join(", ", names)) + "; " + names.size() + " of "
                + job.getModuleMax() + " places, hiring " + job.getHiringMode().name().toLowerCase(Locale.ROOT) + ".");
        }
        if (!jobs.isEmpty() || building.getBuildingDisplayName().contains("townhall")) list(lines, "Citizens without work: ", idle(colony), ", ");
        for (BuildingResourcesModule needs : building.getModules(BuildingResourcesModule.class)) {
            List<String> parts = new ArrayList<>();
            for (BuildingBuilderResource r : needs.getNeededResources().values()) {
                int missing = r.getAmount() - r.getAvailable();
                if (missing > 0) parts.add(missing + " " + Body.name(r.getItemStack()));
            }
            list(lines, "The builder still needs: ", parts, ", ");
        }
        List<String> set = new ArrayList<>();
        for (SettingsModule module : building.getModules(SettingsModule.class)) {
            for (Map.Entry<ISettingKey<?>, ISetting<?>> e : settings(module).entrySet()) {
                String one = describe(e.getKey(), e.getValue());
                if (one != null) set.add(one);
            }
        }
        list(lines, "Settings: ", set, "; ");
        for (String line : lines) Goldar.world().told(b, "colony", line);
    }

    // ---- what a hut's window would be used for ----

    /** Hires or dismisses a citizen at a hut, or changes one of the hut's settings, under the colony's own permissions. */
    static final class Manage implements Tasks.Task {
        private final String verb, what, value;
        private final BlockPos at;
        private Tasks.Walk walk;

        Manage(String verb, BlockPos at, String what, String value) { this.verb = verb; this.at = at; this.what = what; this.value = value; }

        /** The mind hears only that an action failed, so the reason goes to it as the colony's word. */
        private static Outcome fail(Body b, String reason, String why) {
            Goldar.world().told(b, "colony", why);
            return Outcome.fail(reason, why);
        }

        private static Outcome done(Body b, String what) {
            Goldar.world().told(b, "colony", what);
            return Outcome.OK;
        }

        @Override public Outcome tick(Body b) {
            ServerLevel level = b.level();
            if (!(level.getBlockEntity(at) instanceof TileEntityColonyBuilding)) return fail(b, "not_found", "There is no hut at " + at.toShortString() + ".");
            if (walk == null) walk = new Tasks.Walk(at, 3);
            Outcome o = walk.tick(b);
            if (o == null) return null;
            if (!o.ok()) return o;
            IColony colony = IColonyManager.getInstance().getIColony(level, at);
            IBuilding building = colony == null ? null : colony.getServerBuildingManager().getBuilding(at);
            if (building == null) return fail(b, "not_found", "The hut at " + at.toShortString() + " belongs to no colony yet: open it first.");
            if (!colony.getPermissions().hasPermission(b, Action.MANAGE_HUTS)) return fail(b, "not_allowed", "You may not manage huts in " + colony.getName() + ".");
            return verb.equals("set") ? set(b, building) : staff(b, colony, building, verb.equals("hire"));
        }

        private ICitizenData citizen(IColony colony) {
            ICitizenData begins = null;
            int count = 0;
            for (ICitizenData c : colony.getCitizenManager().getCitizens()) {
                if (c.getName().equalsIgnoreCase(what)) return c;
                if (c.getName().toLowerCase(Locale.ROOT).startsWith(what.toLowerCase(Locale.ROOT))) { begins = c; count++; }
            }
            return count == 1 ? begins : null;
        }

        private Outcome staff(Body b, IColony colony, IBuilding building, boolean hire) {
            ICitizenData citizen = citizen(colony);
            if (citizen == null) {
                List<String> idle = idle(colony);
                return fail(b, "not_found", "No citizen here is called " + what + ". Without work: " + (idle.isEmpty() ? "nobody" : String.join(", ", idle)) + ".");
            }
            List<WorkerBuildingModule> jobs = building.getModules(WorkerBuildingModule.class);
            if (jobs.isEmpty()) return fail(b, "not_possible", "Nobody works in this hut.");
            for (WorkerBuildingModule job : jobs) {
                if (!job.hasAssignedCitizen(citizen)) continue;
                if (hire) return done(b, citizen.getName() + " already works here.");
                job.removeCitizen(citizen);
                return done(b, citizen.getName() + " no longer works here.");
            }
            if (!hire) return fail(b, "not_found", citizen.getName() + " does not work here.");
            if (citizen.isChild()) return fail(b, "not_possible", citizen.getName() + " is a child and cannot work yet.");
            // A citizen has one job: the old one is given up first, as the hiring window does.
            IBuilding old = citizen.getWorkBuilding();
            if (old != null) for (WorkerBuildingModule job : old.getModules(WorkerBuildingModule.class)) if (job.hasAssignedCitizen(citizen)) job.removeCitizen(citizen);
            for (WorkerBuildingModule job : jobs) {
                if (job.isFull()) continue;
                citizen.setPaused(false);
                if (job.assignCitizen(citizen)) return done(b, citizen.getName() + " now works here as " + plain(job.getJobEntry().getTranslationKey()) + ".");
            }
            return fail(b, "not_possible", "The hut has no free place for " + citizen.getName() + ": it may need building first.");
        }

        private Outcome set(Body b, IBuilding building) {
            String wanted = value.trim().toLowerCase(Locale.ROOT);
            if (what.equalsIgnoreCase("hiring")) {
                HiringMode mode = switch (wanted) {
                    case "auto", "automatic" -> HiringMode.AUTO;
                    case "manual" -> HiringMode.MANUAL;
                    case "default" -> HiringMode.DEFAULT;
                    default -> null;
                };
                List<WorkerBuildingModule> jobs = building.getModules(WorkerBuildingModule.class);
                if (mode == null) return fail(b, "invalid_args", "Hiring is auto, manual or default.");
                if (jobs.isEmpty()) return fail(b, "not_possible", "Nobody works in this hut.");
                for (WorkerBuildingModule job : jobs) job.setHiringMode(mode);
                building.markDirty();
                return done(b, "Hiring here is now " + mode.name().toLowerCase(Locale.ROOT) + ".");
            }
            List<String> known = new ArrayList<>();
            for (SettingsModule module : building.getModules(SettingsModule.class)) {
                for (Map.Entry<ISettingKey<?>, ISetting<?>> e : settings(module).entrySet()) {
                    String name = e.getKey().getUniqueId().getPath();
                    if (describe(e.getKey(), e.getValue()) != null) known.add(name);
                    if (!name.equalsIgnoreCase(what) && !e.getKey().getUniqueId().toString().equalsIgnoreCase(what)) continue;
                    ISetting<?> setting = e.getValue();
                    if (setting instanceof BoolSetting bool) {
                        Boolean on = switch (wanted) {
                            case "true", "on", "yes" -> Boolean.TRUE;
                            case "false", "off", "no" -> Boolean.FALSE;
                            default -> null;
                        };
                        if (on == null) return fail(b, "invalid_args", name + " is true or false.");
                        if (bool.getValue() != on) bool.trigger();
                    } else if (setting instanceof IntSetting number) {
                        try {
                            number.setValue(Integer.parseInt(wanted));
                        } catch (NumberFormatException x) {
                            return fail(b, "invalid_args", name + " is a whole number.");
                        }
                    } else if (setting instanceof StringSetting text) {
                        String choice = null;
                        for (String c : text.getSettings()) if (c.equalsIgnoreCase(value.trim()) || last(c).equals(wanted)) choice = c;
                        if (choice == null) return fail(b, "invalid_args", "Not a choice for " + describe(e.getKey(), setting) + ".");
                        text.set(choice);
                    } else {
                        return fail(b, "not_possible", name + " cannot be changed from here.");
                    }
                    module.updateSetting(e.getKey(), setting, b);
                    building.markDirty();
                    return done(b, "Set " + describe(e.getKey(), setting) + ".");
                }
            }
            return fail(b, "not_found", "This hut has no setting " + what + ". It has: hiring" + (known.isEmpty() ? "" : ", " + String.join(", ", known)) + ".");
        }
    }
}
