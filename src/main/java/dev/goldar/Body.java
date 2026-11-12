package dev.goldar;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/**
 * A mind's body: a real player on the server with no client behind it. The server simulates its movement from the
 * inputs set here, and it eats, falls, burns and dies as any player does.
 */
public final class Body extends ServerPlayer {
    private static final int CHUNK_SYNC_TICKS = 10;

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

    public final Mind mind;
    /** Blocks broken or placed since the body woke: intents are limited by it. */
    public int blockOperations;
    /** The body tick on which it was last hurt, and on which it last followed a path. */
    public int lastHurtTick = -1000, pathTick = -1000;
    private float forward;
    private boolean jump;

    private Body(MinecraftServer server, ServerLevel level, GameProfile profile, Mind mind) {
        super(server, level, profile, ClientInformation.createDefault());
        this.mind = mind;
    }

    /** Puts the mind's body into the world, where it was when it last left, or at the world spawn. Server thread only. */
    public static Body wake(MinecraftServer server, Mind mind) {
        String name = mind.seat.name;
        UUID id = UUID.nameUUIDFromBytes(("Goldar:" + mind.seat.id).getBytes(StandardCharsets.UTF_8));
        ServerLevel level = server.overworld();
        Body body = new Body(server, level, new GameProfile(id, name), mind);
        var saved = server.getPlayerList().loadPlayerData(body.nameAndId());
        if (saved.isPresent()) body.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), saved.get()));
        boolean atSpawn = saved.isEmpty();
        if (saved.isPresent() && body.isDeadOrDying()) {
            atSpawn = true;
            // The body died: it comes back as a respawned player does, keeping what a respawn keeps.
            Body fresh = new Body(server, level, new GameProfile(id, name), mind);
            fresh.restoreFrom(body, false);
            body = fresh;
        }
        if (atSpawn) {
            BlockPos spawn = body.adjustSpawnLocation(level, level.getRespawnData().pos());
            body.snapTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
        }
        server.getPlayerList().placeNewPlayer(new NoConnection(), body, CommonListenerCookie.createInitial(body.getGameProfile(), false));
        return body;
    }

    /** Takes the body out of the world and saves it. Server thread only. */
    public void rest() {
        if (!isRemoved()) level().getServer().getPlayerList().remove(this);
    }

    // The server, not a client, moves this player.
    @Override public boolean isClientAuthoritative() { return false; }

    @Override public void tick() {
        if (tickCount % CHUNK_SYNC_TICKS == 0) level().getChunkSource().move(this);
        super.tick();
        zza = forward;
        xxa = 0;
        setJumping(jump);
        doTick();
    }

    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        float before = getHealth() + getAbsorptionAmount();
        boolean hurt = super.hurtServer(level, source, damage);
        if (hurt) {
            float lost = before - (getHealth() + getAbsorptionAmount());
            if (lost > 0) lastHurtTick = tickCount;
            // Each blow is news, the ones armour took whole among them.
            if (isAlive()) Goldar.world().onHurt(mind, this, lost);
            else Goldar.world().onKilled(mind, lost);
        }
        return hurt;
    }

    @Override public void die(DamageSource source) {
        String how = getCombatTracker().getDeathMessage().getString();
        super.die(source);
        Goldar.world().onDeath(mind, how);
    }

    // ---- inputs ----

    /** Walks toward a point, or stands still when `to` is null. */
    public void steer(Vec3 to, boolean jumping) {
        jump = jumping;
        if (to == null) { forward = 0; return; }
        double dx = to.x - getX(), dz = to.z - getZ();
        if (dx * dx + dz * dz > 1e-4) {
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
            setYRot(yaw);
            setYHeadRot(yaw);
        }
        forward = 1;
    }

    public void halt() { forward = 0; jump = false; }

    public void lookAt(Vec3 at) {
        Vec3 eye = getEyePosition();
        double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        setYRot(yaw);
        setYHeadRot(yaw);
        setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    // ---- inventory ----

    public static String name(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(); }

    public static String name(BlockState s) { return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath(); }

    public int count(String item) {
        int n = 0;
        for (ItemStack s : getInventory().getNonEquipmentItems()) if (!s.isEmpty() && name(s).equals(item)) n += s.getCount();
        return n;
    }

    /** The first slot holding the item, or -1. */
    public int slotOf(String item) {
        var items = getInventory().getNonEquipmentItems();
        for (int i = 0; i < items.size(); i++) if (!items.get(i).isEmpty() && name(items.get(i)).equals(item)) return i;
        return -1;
    }

    /** Puts the stack in `slot` into the main hand by swapping it with the selected hotbar slot. */
    public void hold(int slot) {
        var inv = getInventory();
        int selected = inv.getSelectedSlot();
        if (slot == selected || slot < 0) return;
        if (slot < 9) { inv.setSelectedSlot(slot); return; }
        ItemStack held = inv.getItem(selected);
        inv.setItem(selected, inv.getItem(slot));
        inv.setItem(slot, held);
    }

    /** Holds the fastest tool for the block. Returns false when the block needs a tool the body lacks. */
    public boolean holdToolFor(BlockState state) {
        var items = getInventory().getNonEquipmentItems();
        int best = -1;
        float speed = 1f;
        for (int i = 0; i < items.size(); i++) {
            float s = items.get(i).getDestroySpeed(state);
            if (s > speed && (!state.requiresCorrectToolForDrops() || items.get(i).isCorrectToolForDrops(state))) { speed = s; best = i; }
        }
        if (best >= 0) hold(best);
        return !state.requiresCorrectToolForDrops() || getMainHandItem().isCorrectToolForDrops(state);
    }
}
