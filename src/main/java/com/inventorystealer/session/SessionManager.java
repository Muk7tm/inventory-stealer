package com.inventorystealer.session;

import com.inventorystealer.InventoryStealer;
import com.inventorystealer.config.InventoryStealerConfig;
import com.inventorystealer.corpse.CorpseEntity;
import com.inventorystealer.integration.BackpackSupport;
import com.inventorystealer.integration.EquipmentOrigin;
import com.inventorystealer.menu.CorpseMenu;
import com.inventorystealer.network.DeathCountdownPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Owns the lifecycle of every loot session on the running server. All methods run on the logical server thread.
 *
 * <pre>
 * LivingDeathEvent (LOWEST, not cancelled) ─ capture items ─► ACTIVE session + corpse + countdown to victim
 * every server tick ─ confirm the death really happened (else roll back), keep corpse present, check deadline
 * deadline ─► FINALIZED (rejects all loot ops) ─► close every viewer ─► remove corpse
 *          ─► drain remaining items exactly once ─► force-respawn victim + restore  (offline: persist as pending)
 * </pre>
 */
public final class SessionManager {
    @Nullable
    private static MinecraftServer server;
    @Nullable
    private static SessionSavedData data;
    private static boolean checkpointRequested;

    private SessionManager() {
    }

    // ---------------------------------------------------------------- lifecycle

    public static void onServerStarted(MinecraftServer startedServer) {
        server = startedServer;
        data = SessionSavedData.get(startedServer);
        checkpointRequested = false;
    }

    public static void onServerStopped() {
        server = null;
        data = null;
        checkpointRequested = false;
    }

    // ---------------------------------------------------------------- queries

    @Nullable
    public static DeathLootSession get(@Nullable UUID sessionId) {
        return data == null || sessionId == null ? null : data.activeSession(sessionId);
    }

    @Nullable
    public static DeathLootSession getActiveForVictim(UUID victimId) {
        if (data == null) {
            return null;
        }
        for (DeathLootSession session : data.activeSessions()) {
            if (session.isActive() && session.victimId().equals(victimId)) {
                return session;
            }
        }
        return null;
    }

    /** True while the victim must stay dead (blocks the vanilla respawn request). */
    public static boolean isRespawnLocked(ServerPlayer player) {
        return getActiveForVictim(player.getUUID()) != null;
    }

    public static long now() {
        return server == null ? 0L : server.getTickCount();
    }

    public static List<DeathLootSession> activeSessions() {
        return data == null ? List.of() : List.copyOf(data.activeSessions());
    }

    public static int pendingCount(UUID victimId) {
        return data == null ? 0 : data.pendingCount(victimId);
    }

    @Nullable
    static SessionSavedData savedData() {
        return data;
    }

    // ---------------------------------------------------------------- death

    public static void onPlayerDeath(ServerPlayer victim, DamageSource source) {
        if (data == null || server == null || !InventoryStealerConfig.isEnabled()) {
            return;
        }
        if (victim instanceof FakePlayer || victim.isSpectator() || server.isHardcore()) {
            return;
        }
        if (getActiveForVictim(victim.getUUID()) != null) {
            return;
        }
        ServerPlayer killer = PvpAttribution.findKiller(victim, source);
        if (killer == null) {
            return;
        }

        SessionSavedData store = data;
        DeathLootSession session = DeathLootSession.capture(victim, killer, server.getTickCount(), store::setDirty);
        store.addActive(session);
        spawnCorpse(victim.serverLevel(), session);
        PacketDistributor.sendToPlayer(victim, DeathCountdownPayload.start(session.remainingTicks(now()), session.durationTicks(), session.killerName()));
        checkpointRequested = true;

        InventoryStealer.LOGGER.info("PvP death: {} killed by {} at {} in {}; captured {} stack(s), {} backpack tab(s); loot window {} ticks (session {})",
                session.victimName(), session.killerName(), BlockPos.containing(session.position()).toShortString(),
                session.dimension().location(), session.countItems(), session.backpacks().size(), session.durationTicks(), session.id());
    }

    private static void spawnCorpse(ServerLevel level, DeathLootSession session) {
        CorpseEntity corpse = CorpseEntity.create(level, session);
        if (level.addFreshEntity(corpse)) {
            session.setCorpseId(corpse.getUUID());
        } else {
            session.setCorpseId(null);
        }
    }

    // ---------------------------------------------------------------- tick

    public static void tick(MinecraftServer tickingServer) {
        if (data == null) {
            return;
        }
        long now = tickingServer.getTickCount();
        for (DeathLootSession session : new ArrayList<>(data.activeSessions())) {
            if (!session.isActive()) {
                continue;
            }
            if (!session.isDeathConfirmed()) {
                ServerPlayer victim = tickingServer.getPlayerList().getPlayer(session.victimId());
                if (victim != null && victim.isAlive() && !victim.isRemoved()) {
                    // Another LOWEST-priority listener cancelled the death after we captured: undo everything.
                    InventoryStealer.LOGGER.warn("Death of {} was cancelled by another mod after capture; returning items (session {})",
                            session.victimName(), session.id());
                    finalizeSession(session, "death cancelled");
                    continue;
                }
                session.confirmDeath();
            }
            if (now >= session.deadlineTick()) {
                finalizeSession(session, "expired");
            } else {
                ensureCorpse(tickingServer, session);
            }
        }
        if (checkpointRequested) {
            checkpointRequested = false;
            checkpoint(tickingServer);
        }
    }

    private static void ensureCorpse(MinecraftServer tickingServer, DeathLootSession session) {
        ServerLevel level = tickingServer.getLevel(session.dimension());
        if (level == null) {
            return;
        }
        Entity existing = session.corpseId() == null ? null : level.getEntity(session.corpseId());
        if (existing instanceof CorpseEntity && existing.isAlive()) {
            return;
        }
        // The corpse is only a view of the session: recreate it if it was unloaded or removed by a command.
        if (level.isLoaded(BlockPos.containing(session.position()))) {
            spawnCorpse(level, session);
        }
    }

    // ---------------------------------------------------------------- looting

    public static boolean tryOpen(ServerPlayer looter, CorpseEntity corpse) {
        DeathLootSession session = get(corpse.sessionId());
        if (session == null || !session.canAccess(looter)) {
            return false;
        }
        looter.openMenu(new SimpleMenuProvider((containerId, inventory, player) -> CorpseMenu.server(containerId, inventory, session),
                        Component.literal(session.victimName())),
                buf -> CorpseMenu.writeOpenData(session, buf));
        return true;
    }

    // ---------------------------------------------------------------- finalization

    /** Idempotent: a session is finalized, drained and restored at most once. */
    public static void finalizeSession(DeathLootSession session, String reason) {
        if (!session.isActive() || data == null || server == null) {
            return;
        }
        MinecraftServer currentServer = server;
        SessionSavedData store = data;

        // 1. From here on every container of this session rejects all operations.
        session.markFinalized();

        // 2. Server-side close of every viewer (returns their cursor stacks to them).
        for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof CorpseMenu menu && menu.isFor(session)) {
                player.closeContainer();
            }
        }

        // 3. Remove the corpse view.
        ServerLevel level = currentServer.getLevel(session.dimension());
        if (level != null && session.corpseId() != null && level.getEntity(session.corpseId()) instanceof CorpseEntity corpse) {
            corpse.discard();
        }

        // 4. Transfer ownership of whatever is left, exactly once.
        PendingRestoration remaining = session.drain();
        store.removeActive(session);

        ServerPlayer victim = currentServer.getPlayerList().getPlayer(session.victimId());
        if (victim == null) {
            store.addPending(remaining);
            InventoryStealer.LOGGER.info("Loot session {} for {} {}; victim offline, {} stack(s) stored until they return",
                    session.id(), session.victimName(), reason, countStacks(remaining));
        } else {
            PacketDistributor.sendToPlayer(victim, DeathCountdownPayload.end());
            if (!victim.isAlive()) {
                victim = forceRespawn(currentServer, victim);
            }
            int returned = restore(victim, remaining);
            InventoryStealer.LOGGER.info("Loot session {} for {} {}; returned {} stack(s)", session.id(), session.victimName(), reason, returned);
        }
        checkpointRequested = true;
    }

    /** Same server-side path as the vanilla respawn button (see ServerGamePacketListenerImpl#handleClientCommand). */
    private static ServerPlayer forceRespawn(MinecraftServer currentServer, ServerPlayer deadPlayer) {
        ServerPlayer respawned = currentServer.getPlayerList().respawn(deadPlayer, false, Entity.RemovalReason.KILLED);
        // Vanilla's packet handler re-points the connection at the new player; a server-initiated respawn must too.
        respawned.connection.player = respawned;
        return respawned;
    }

    /** Puts items back into their original slots where possible; anything else goes to the inventory or is dropped at the player. */
    static int restore(ServerPlayer player, PendingRestoration restoration) {
        Inventory inventory = player.getInventory();
        int returned = 0;
        for (int i = 0; i < restoration.items().size(); i++) {
            ItemStack stack = restoration.items().get(i);
            if (stack.isEmpty()) {
                continue;
            }
            restoration.items().set(i, ItemStack.EMPTY);
            returned++;
            if (i < restoration.inventorySize()) {
                if (i < inventory.getContainerSize() && inventory.getItem(i).isEmpty()) {
                    inventory.setItem(i, stack);
                } else {
                    inventory.placeItemBackInInventory(stack);
                }
            } else {
                int extra = i - restoration.inventorySize();
                EquipmentOrigin origin = extra < restoration.extraOrigins().size() ? restoration.extraOrigins().get(extra) : EquipmentOrigin.loose("unknown");
                if (!origin.isEquipment() || !BackpackSupport.restoreEquipped(player, origin, stack)) {
                    inventory.placeItemBackInInventory(stack);
                }
            }
        }
        inventory.setChanged();
        return returned;
    }

    private static int countStacks(PendingRestoration restoration) {
        int count = 0;
        for (ItemStack stack : restoration.items()) {
            count += stack.isEmpty() ? 0 : 1;
        }
        return count;
    }

    // ---------------------------------------------------------------- pending restorations

    /** Returns stored items to a living victim (after login or a normal respawn). */
    public static void deliverPending(ServerPlayer player) {
        if (data == null || !player.isAlive() || isRespawnLocked(player)) {
            return;
        }
        List<PendingRestoration> owed = data.takePending(player.getUUID());
        if (owed.isEmpty()) {
            return;
        }
        int returned = 0;
        for (PendingRestoration restoration : owed) {
            returned += restore(player, restoration);
        }
        player.sendSystemMessage(Component.translatable("inventorystealer.message.restored", returned));
        InventoryStealer.LOGGER.info("Returned {} stored stack(s) from {} interrupted/offline session(s) to {}", returned, owed.size(), player.getGameProfile().getName());
        checkpointRequested = true;
    }

    public static void onPlayerLoggedIn(ServerPlayer player) {
        DeathLootSession session = getActiveForVictim(player.getUUID());
        if (session != null) {
            PacketDistributor.sendToPlayer(player, DeathCountdownPayload.start(session.remainingTicks(now()), session.durationTicks(), session.killerName()));
        } else {
            deliverPending(player);
        }
    }

    /** A player's data was just written by a logout: write ours too so the two never disagree after a crash. */
    public static void onPlayerLoggedOut() {
        if (server != null && data != null && data.hasAnyState()) {
            server.overworld().getDataStorage().save();
        }
    }

    /**
     * Saves every online player and our data at the same moment, at session start and end. Without this, a crash
     * after a loot session could pair an old player file with newer session data (or vice versa).
     */
    private static void checkpoint(MinecraftServer currentServer) {
        if (!InventoryStealerConfig.checkpointSaves()) {
            return;
        }
        currentServer.getPlayerList().saveAll();
        currentServer.overworld().getDataStorage().save();
    }
}
