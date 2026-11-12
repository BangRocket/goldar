package dev.goldar;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Goldar: minds connect over MCP and live in the world through bodies the server runs for them. */
@Mod(Goldar.ID)
public final class Goldar {
    public static final String ID = "goldar";
    public static final String VERSION = "0.1.0";
    public static final Logger LOGGER = LoggerFactory.getLogger("Goldar");
    private static final String PLAYER_ID = "[a-z][a-z0-9-]{0,30}";
    private static final String NAME = "[A-Za-z0-9_]{3,16}";

    private static World world;
    private static Mcp mcp;
    private static Glass glass;

    public Goldar() { NeoForge.EVENT_BUS.register(this); }

    public static World world() { return world; }

    public static long time(Level level) { return level.getDefaultClockTime(); }

    public static long day(Level level) { return time(level) / 24000; }

    @SubscribeEvent public void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            var dir = server.getServerDirectory().resolve("config");
            GoldarConfig config = GoldarConfig.load(dir.resolve("goldar.json"));
            Crafting.reset();
            world = new World(server, config, dir.resolve("goldar"));
            mcp = new Mcp(world);
            if (config.glass) {
                glass = new Glass(world);
                world.glass = glass;
                LOGGER.info("The Glass is open to people at http://{}:{}/", config.glass_bind, config.glass_port);
            }
            LOGGER.info("Goldar is listening for minds on {}:{} ({} player(s) configured)", config.bind, config.port, config.players.size());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Goldar could not start its endpoint", e);
        }
    }

    @SubscribeEvent public void stopping(ServerStoppingEvent event) {
        if (mcp != null) mcp.stop();
        if (glass != null) glass.stop();
        glass = null;
        if (world != null) world.close();
        mcp = null;
        world = null;
    }

    @SubscribeEvent public void tick(ServerTickEvent.Post event) {
        if (world != null) world.tickAll();
    }

    private static int reply(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** Moves the Shrine or the Moot Stone to where the one giving the command stands. */
    private static int place(CommandContext<CommandSourceStack> c, boolean shrine) {
        if (world == null) return reply(c, "Goldar is not running.");
        var at = net.minecraft.core.BlockPos.containing(c.getSource().getPosition());
        int[] pos = {at.getX(), at.getY(), at.getZ()};
        if (shrine) world.commons.shrine = pos; else world.commons.moot = pos;
        world.commons.save();
        return reply(c, "The " + (shrine ? "Shrine" : "Moot Stone") + " is now at " + at.getX() + " " + at.getY() + " " + at.getZ() + ".");
    }

    @SubscribeEvent public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("goldar").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
            .then(Commands.literal("list").executes(c -> {
                if (world == null) return reply(c, "Goldar is not running.");
                StringBuilder sb = new StringBuilder(world.config.players.size() + " player(s) on port " + world.config.port);
                for (var p : world.config.players) sb.append("\n  ").append(p.id).append(" as ").append(p.name).append(p.ended ? " (life ended)" : "");
                return reply(c, sb.toString());
            }))
            .then(Commands.literal("add").then(Commands.argument("id", StringArgumentType.string())
                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    String player = StringArgumentType.getString(c, "id"), name = StringArgumentType.getString(c, "name");
                    if (world == null) return reply(c, "Goldar is not running.");
                    if (!player.matches(PLAYER_ID)) return reply(c, "A player id is lowercase letters, digits and dashes, starting with a letter.");
                    if (!name.matches(NAME)) return reply(c, "A name is 3 to 16 letters, digits or underscores.");
                    try {
                        String token = world.config.add(player, name);
                        if (token == null) return reply(c, "There is already a player " + player + ".");
                        return reply(c, "Added " + player + ". Its token, shown once: " + token + "\nEndpoint: http://<this host>:" + world.config.port + "/p/" + player);
                    } catch (IOException e) {
                        return reply(c, "Could not save the config: " + e.getMessage());
                    }
                }))))
            .then(Commands.literal("token").then(Commands.argument("id", StringArgumentType.string()).executes(c -> {
                if (world == null) return reply(c, "Goldar is not running.");
                try {
                    String token = world.config.rotate(StringArgumentType.getString(c, "id"));
                    return reply(c, token == null ? "No such player." : "New token, shown once: " + token);
                } catch (IOException e) {
                    return reply(c, "Could not save the config: " + e.getMessage());
                }
            })))
            .then(Commands.literal("shrine").executes(c -> place(c, true)))
            .then(Commands.literal("moot").executes(c -> place(c, false)))
            .then(Commands.literal("revive").then(Commands.argument("id", StringArgumentType.string()).executes(c -> {
                if (world == null) return reply(c, "Goldar is not running.");
                var seat = world.config.player(StringArgumentType.getString(c, "id"));
                if (seat == null) return reply(c, "No such player.");
                seat.ended = false;
                try { world.config.save(); } catch (IOException e) { return reply(c, "Could not save the config: " + e.getMessage()); }
                return reply(c, seat.id + " may live again; its body wakes on its next call.");
            }))));
    }
}
