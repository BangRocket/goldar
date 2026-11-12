package dev.goldar;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A mind's body: a real player on the server with no client behind it. The server simulates its movement from the
 * inputs set here, and it eats, falls, burns and dies as any player does.
 */
public final class Body extends BodyBase {
    private static final int CHUNK_SYNC_TICKS = 10;

    public final Mind mind;
    /** Blocks broken or placed since the body woke: intents are limited by it. */
    public int blockOperations;
    /** The body tick on which it was last hurt, and on which it last followed a path. */
    public int lastHurtTick = -1000, pathTick = -1000;
    /** The last thing a colony told this body, and the body tick it came on. */
    String lastTold;
    int lastToldTick = -1000;
    private float forward;
    private boolean jump;

    private Body(MinecraftServer server, ServerLevel level, GameProfile profile, Mind mind) {
        super(server, level, profile);
        this.mind = mind;
    }

    /** Puts the mind's body into the world, where it was when it last left, or at the world spawn. Server thread only. */
    public static Body wake(MinecraftServer server, Mind mind) {
        String name = mind.seat.name;
        UUID id = UUID.nameUUIDFromBytes(("Goldar:" + mind.seat.id).getBytes(StandardCharsets.UTF_8));
        ServerLevel level = server.overworld();
        Body body = new Body(server, level, new GameProfile(id, name), mind);
        boolean saved = body.loadSaved(server);
        boolean atSpawn = !saved;
        if (saved && body.isDeadOrDying()) {
            atSpawn = true;
            // The body died: it comes back as a respawned player does, keeping what a respawn keeps.
            Body fresh = new Body(server, level, new GameProfile(id, name), mind);
            fresh.restoreFrom(body, false);
            body = fresh;
        }
        if (atSpawn) {
            BlockPos spawn = body.adjustSpawnLocation(level, Compat.worldSpawn(level));
            body.snap(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        }
        server.getPlayerList().placeNewPlayer(noConnection(), body, CommonListenerCookie.createInitial(body.getGameProfile(), false));
        return body;
    }

    /** Takes the body out of the world and saves it. Server thread only. */
    public void rest() {
        if (!isRemoved()) level().getServer().getPlayerList().remove(this);
    }

    @Override public void tick() {
        if (tickCount % CHUNK_SYNC_TICKS == 0) syncChunks();
        super.tick();
        zza = forward;
        xxa = 0;
        setJumping(jump);
        doTick();
    }

    @Override void struck(float before) {
        {
            float lost = before - (getHealth() + getAbsorptionAmount());
            if (lost > 0) lastHurtTick = tickCount;
            // Each blow is news, the ones armour took whole among them.
            if (isAlive()) Goldar.world().onHurt(mind, this, lost);
            else Goldar.world().onKilled(mind, lost);
        }
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
        for (ItemStack s : Compat.items(getInventory())) if (!s.isEmpty() && name(s).equals(item)) n += s.getCount();
        return n;
    }

    /** The first slot holding the item, or -1. */
    public int slotOf(String item) {
        var items = Compat.items(getInventory());
        for (int i = 0; i < items.size(); i++) if (!items.get(i).isEmpty() && name(items.get(i)).equals(item)) return i;
        return -1;
    }

    /** Puts the stack in `slot` into the main hand by swapping it with the selected hotbar slot. */
    public void hold(int slot) {
        var inv = getInventory();
        int selected = Compat.selected(inv);
        if (slot == selected || slot < 0) return;
        if (slot < 9) { Compat.select(inv, slot); return; }
        ItemStack held = inv.getItem(selected);
        inv.setItem(selected, inv.getItem(slot));
        inv.setItem(slot, held);
    }

    /** Holds the fastest tool for the block. Returns false when the block needs a tool the body lacks. */
    public boolean holdToolFor(BlockState state) {
        var items = Compat.items(getInventory());
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
