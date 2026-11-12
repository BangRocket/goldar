package dev.goldar;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.storage.TagValueInput;

/** The parts of a body that are written differently for each Minecraft version. This is Minecraft 26.1's. */
abstract class BodyBase extends ServerPlayer {
    /** A connection that goes nowhere: packets for the client that isn't there are dropped. */
    private static final class NoConnection extends net.minecraft.network.Connection {
        NoConnection() {
            super(PacketFlow.SERVERBOUND);
            // The base class reads its channel in places that can't be overridden; an embedded one is always open.
            try {
                var field = net.minecraft.network.Connection.class.getDeclaredField("channel");
                field.setAccessible(true);
                field.set(this, new EmbeddedChannel());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not prepare a body's connection", e);
            }
            // Other mods send their own payloads to every player who joins, and NeoForge refuses one for a
            // connection that never agreed its channels. This marks every channel as agreed; send still drops them.
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(this);
        }

        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {}
        @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {}
        @Override public void setListenerForServerboundHandshake(PacketListener listener) {}
        @Override public void setReadOnly() {}
        @Override public void handleDisconnection() {}
        @Override public void flushChannel() {}
        @Override public boolean isConnected() { return true; }
        @Override public boolean isMemoryConnection() { return false; }
    }

    BodyBase(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    static net.minecraft.network.Connection noConnection() { return new NoConnection(); }

    /** Reads this player's saved data into the body. False when there is none. */
    boolean loadSaved(MinecraftServer server) {
        var saved = server.getPlayerList().loadPlayerData(nameAndId());
        if (saved.isEmpty()) return false;
        load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), saved.get()));
        return true;
    }

    void snap(double x, double y, double z) { snapTo(x, y, z, 0, 0); }

    void syncChunks() { level().getChunkSource().move(this); }

    // The server, not a client, moves this player.
    @Override public boolean isClientAuthoritative() { return false; }

    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        float before = getHealth() + getAbsorptionAmount();
        boolean hurt = super.hurtServer(level, source, damage);
        if (hurt) struck(before);
        return hurt;
    }

    /** A blow landed. `before` is the health and absorption the body had. */
    abstract void struck(float before);
}
