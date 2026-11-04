package com.inventorystealer.menu;

import com.inventorystealer.integration.EquipmentOrigin;
import com.inventorystealer.session.BackpackStorageContainer;
import com.inventorystealer.session.DeathLootSession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.item.ItemStack;

/**
 * Layout information sent once when a corpse menu opens, so the client can build the same slots as the server.
 * Slot contents are not part of this: they arrive through normal menu synchronisation.
 */
public record CorpseMenuData(UUID sessionId, UUID victimId, String victimName, int totalTicks, int inventorySize,
                             List<Boolean> extraIsEquipment, List<Tab> tabs) {

    public record Tab(int victimSlot, int storageSize, int toolSize, ItemStack icon, Component location) {
    }

    public int victimSlotCount() {
        return inventorySize + extraIsEquipment.size();
    }

    public static CorpseMenuData of(DeathLootSession session) {
        List<Boolean> extras = new ArrayList<>();
        for (EquipmentOrigin origin : session.extraOrigins()) {
            extras.add(origin.isEquipment());
        }
        List<Tab> tabs = new ArrayList<>();
        for (BackpackStorageContainer backpack : session.backpacks()) {
            tabs.add(new Tab(backpack.victimSlot(), backpack.storageSize(), backpack.toolSize(),
                    backpack.backpack().copyWithCount(1), locationOf(session, backpack.victimSlot())));
        }
        return new CorpseMenuData(session.id(), session.victimId(), session.victimName(), session.durationTicks(),
                session.inventorySize(), extras, tabs);
    }

    private static Component locationOf(DeathLootSession session, int victimSlot) {
        if (victimSlot >= session.inventorySize()) {
            return session.extraOrigins().get(victimSlot - session.inventorySize()).describe();
        }
        if (victimSlot < 9) {
            return Component.translatable("inventorystealer.location.hotbar", victimSlot + 1);
        }
        if (victimSlot < 36) {
            return Component.translatable("inventorystealer.location.inventory");
        }
        if (victimSlot == 40) {
            return Component.translatable("inventorystealer.location.offhand");
        }
        return Component.translatable("inventorystealer.location.armor");
    }

    public void write(RegistryFriendlyByteBuf buf) {
        UUIDUtil.STREAM_CODEC.encode(buf, sessionId);
        UUIDUtil.STREAM_CODEC.encode(buf, victimId);
        buf.writeUtf(victimName, 64);
        buf.writeVarInt(totalTicks);
        buf.writeVarInt(inventorySize);
        buf.writeVarInt(extraIsEquipment.size());
        for (boolean equipment : extraIsEquipment) {
            buf.writeBoolean(equipment);
        }
        buf.writeVarInt(tabs.size());
        for (Tab tab : tabs) {
            buf.writeVarInt(tab.victimSlot());
            buf.writeVarInt(tab.storageSize());
            buf.writeVarInt(tab.toolSize());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, tab.icon());
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, tab.location());
        }
    }

    public static CorpseMenuData read(RegistryFriendlyByteBuf buf) {
        UUID sessionId = UUIDUtil.STREAM_CODEC.decode(buf);
        UUID victimId = UUIDUtil.STREAM_CODEC.decode(buf);
        String victimName = buf.readUtf(64);
        int totalTicks = buf.readVarInt();
        int inventorySize = buf.readVarInt();
        int extraCount = buf.readVarInt();
        List<Boolean> extras = new ArrayList<>(extraCount);
        for (int i = 0; i < extraCount; i++) {
            extras.add(buf.readBoolean());
        }
        int tabCount = buf.readVarInt();
        List<Tab> tabs = new ArrayList<>(tabCount);
        for (int i = 0; i < tabCount; i++) {
            tabs.add(new Tab(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf)));
        }
        return new CorpseMenuData(sessionId, victimId, victimName, totalTicks, inventorySize, extras, tabs);
    }
}
