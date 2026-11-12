package dev.goldar;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

/** The parts of a body that are written differently for each Minecraft version. This is Minecraft 1.21.1's. */
abstract class BodyBase extends ServerPlayer {
    /** A connection that goes nowhere: packets for the client that isn't there are dropped. */
    private static final class NoConnection extends Connection {
        NoConnection() {
            super(PacketFlow.SERVERBOUND);
            // The base class reads its channel in places that can't be overridden; an embedded one is always open.
            try {
                var field = Connection.class.getDeclaredField("channel");
                field.setAccessible(true);
                field.set(this, new EmbeddedChannel());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not prepare a body's connection", e);
            }
        }

        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {}
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

    static Connection noConnection() { return new NoConnection(); }

    /** A body is only ever in a server's world. */
    @Override public ServerLevel level() { return (ServerLevel) super.level(); }

    /** Reads this player's saved data into the body. False when there is none. */
    boolean loadSaved(MinecraftServer server) { return server.getPlayerList().load(this).isPresent(); }

    void snap(double x, double y, double z) { moveTo(x, y, z, 0, 0); }

    void syncChunks() { level().getChunkSource().move(this); }

    @Override public boolean hurt(DamageSource source, float damage) {
        float before = getHealth() + getAbsorptionAmount();
        boolean hurt = super.hurt(source, damage);
        if (hurt) struck(before);
        return hurt;
    }

    /** A blow landed. `before` is the health and absorption the body had. */
    abstract void struck(float before);
}
