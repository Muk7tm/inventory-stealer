package com.inventorystealer.session;

import com.inventorystealer.InventoryStealer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Durable store ({@code <world>/data/inventorystealer_sessions.dat}) for active sessions and items owed to victims.
 *
 * <p>Active sessions are written as restorations. A session cannot outlive the server process (its corpse and
 * timer are runtime-only), so on the next start every saved session becomes a pending restoration for its victim:
 * items are returned, never replayed into a second loot window.
 */
public final class SessionSavedData extends SavedData {
    public static final String NAME = InventoryStealer.MOD_ID + "_sessions";

    private final Map<UUID, DeathLootSession> active = new LinkedHashMap<>();
    private final Map<UUID, List<PendingRestoration>> pending = new HashMap<>();

    public static SessionSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SessionSavedData::new, SessionSavedData::load, null), NAME);
    }

    static SessionSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SessionSavedData data = new SessionSavedData();
        int recovered = 0;
        ListTag list = tag.getList("restorations", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            PendingRestoration restoration = PendingRestoration.load(entry, registries);
            if (entry.getBoolean("wasActive")) {
                recovered++;
            }
            if (!restoration.isEmpty()) {
                data.pending.computeIfAbsent(restoration.victimId(), k -> new ArrayList<>()).add(restoration);
            }
        }
        if (recovered > 0) {
            InventoryStealer.LOGGER.warn("Recovered {} loot session(s) interrupted by a server stop/crash; "
                    + "their remaining items will be returned to the victims.", recovered);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (DeathLootSession session : active.values()) {
            CompoundTag entry = session.snapshot().save(registries);
            entry.putBoolean("wasActive", true);
            list.add(entry);
        }
        for (List<PendingRestoration> restorations : pending.values()) {
            for (PendingRestoration restoration : restorations) {
                list.add(restoration.save(registries));
            }
        }
        tag.put("restorations", list);
        return tag;
    }

    void addActive(DeathLootSession session) {
        active.put(session.id(), session);
        setDirty();
    }

    void removeActive(DeathLootSession session) {
        active.remove(session.id());
        setDirty();
    }

    Collection<DeathLootSession> activeSessions() {
        return Collections.unmodifiableCollection(active.values());
    }

    DeathLootSession activeSession(UUID id) {
        return active.get(id);
    }

    void addPending(PendingRestoration restoration) {
        if (!restoration.isEmpty()) {
            pending.computeIfAbsent(restoration.victimId(), k -> new ArrayList<>()).add(restoration);
        }
        setDirty();
    }

    /** Removes and returns everything owed to a victim (ownership moves to the caller). */
    List<PendingRestoration> takePending(UUID victimId) {
        List<PendingRestoration> taken = pending.remove(victimId);
        if (taken == null) {
            return List.of();
        }
        setDirty();
        return taken;
    }

    boolean hasAnyState() {
        return !active.isEmpty() || !pending.isEmpty();
    }

    int pendingCount(UUID victimId) {
        return pending.getOrDefault(victimId, List.of()).size();
    }
}
