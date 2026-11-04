package com.inventorystealer.session;

import com.inventorystealer.config.InventoryStealerConfig;
import com.inventorystealer.integration.BackpackSupport;
import com.inventorystealer.integration.CapturedEquipment;
import com.inventorystealer.integration.EquipmentOrigin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side, authoritative state of one PvP death: owns the victim's lootable possessions until they are
 * either taken by looters or returned to the victim (exactly once, by {@link #drain()}).
 */
public final class DeathLootSession {
    public enum State { ACTIVE, FINALIZED }

    private final UUID id;
    private final UUID victimId;
    private final String victimName;
    private final String killerName;
    private final ResourceKey<Level> dimension;
    private final Vec3 position;
    private final long createdTick;
    private final long deadlineTick;
    private final int inventorySize;
    private final List<EquipmentOrigin> extraOrigins;
    private final VictimContainer victimItems;
    private final List<BackpackStorageContainer> backpacks = new ArrayList<>();
    private final Runnable dirtyListener;

    private State state = State.ACTIVE;
    private boolean deathConfirmed;
    @Nullable
    private UUID corpseId;

    private DeathLootSession(UUID id, ServerPlayer victim, ServerPlayer killer, long createdTick, int durationTicks,
                             int inventorySize, NonNullList<ItemStack> items, List<EquipmentOrigin> extraOrigins,
                             Runnable dirtyListener) {
        this.id = id;
        this.victimId = victim.getUUID();
        this.victimName = victim.getGameProfile().getName();
        this.killerName = killer.getGameProfile().getName();
        this.dimension = victim.level().dimension();
        double minY = victim.level().getMinBuildHeight();
        this.position = new Vec3(victim.getX(), Math.max(victim.getY(), minY), victim.getZ());
        this.createdTick = createdTick;
        this.deadlineTick = createdTick + durationTicks;
        this.inventorySize = inventorySize;
        this.extraOrigins = List.copyOf(extraOrigins);
        this.victimItems = new VictimContainer(this, items);
        this.dirtyListener = dirtyListener;
    }

    /**
     * Moves (not copies) every lootable item out of the victim into a new session. After this the victim's
     * inventory, cursor, 2x2 crafting grid and equipped backpack slots are empty, so vanilla death drops,
     * Curios/TB death handling and {@code keepInventory} cloning have nothing left to duplicate.
     *
     * <p>With {@code keepInventory=false}, items carrying an equipment-drop-preventing enchantment
     * (Curse of Vanishing) are left in place so vanilla destroys them as usual.
     */
    static DeathLootSession capture(ServerPlayer victim, ServerPlayer killer, long now, Runnable dirtyListener) {
        boolean keepInventory = victim.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
        Inventory inventory = victim.getInventory();
        int inventorySize = inventory.getContainerSize();
        NonNullList<ItemStack> items = NonNullList.create();
        for (int i = 0; i < inventorySize; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || (!keepInventory && isVanishing(stack))) {
                items.add(ItemStack.EMPTY);
            } else {
                items.add(inventory.removeItemNoUpdate(i));
            }
        }
        inventory.setChanged();

        List<EquipmentOrigin> origins = new ArrayList<>();
        for (CapturedEquipment equipment : BackpackSupport.captureEquippedBackpacks(victim, keepInventory)) {
            items.add(equipment.stack());
            origins.add(equipment.origin());
        }

        ItemStack carried = victim.containerMenu.getCarried();
        if (!carried.isEmpty() && !(!keepInventory && isVanishing(carried))) {
            victim.containerMenu.setCarried(ItemStack.EMPTY);
            items.add(carried);
            origins.add(EquipmentOrigin.loose("cursor"));
        }
        CraftingContainer craftSlots = victim.inventoryMenu.getCraftSlots();
        for (int i = 0; i < craftSlots.getContainerSize(); i++) {
            ItemStack stack = craftSlots.getItem(i);
            if (!stack.isEmpty() && !(!keepInventory && isVanishing(stack))) {
                items.add(craftSlots.removeItemNoUpdate(i));
                origins.add(EquipmentOrigin.loose("crafting"));
            }
        }
        victim.inventoryMenu.slotsChanged(craftSlots);

        DeathLootSession session = new DeathLootSession(UUID.randomUUID(), victim, killer, now,
                InventoryStealerConfig.lootDurationTicks(), inventorySize, items, origins, dirtyListener);
        session.indexBackpacks();
        return session;
    }

    /** Equipped backpacks get the first tabs, then hotbar, main inventory, offhand, armor. */
    private void indexBackpacks() {
        List<Integer> order = new ArrayList<>();
        for (int i = inventorySize; i < victimItems.getContainerSize(); i++) {
            if (extraOrigins.get(i - inventorySize).isEquipment()) {
                order.add(i);
            }
        }
        for (int i = 0; i < Math.min(inventorySize, 36); i++) {
            order.add(i);
        }
        for (int i = 40; i < inventorySize; i++) {
            order.add(i);
        }
        for (int i = 36; i < Math.min(inventorySize, 40); i++) {
            order.add(i);
        }
        for (int i = inventorySize; i < victimItems.getContainerSize(); i++) {
            if (!extraOrigins.get(i - inventorySize).isEquipment()) {
                order.add(i);
            }
        }
        for (int slot : order) {
            ItemStack stack = victimItems.getItem(slot);
            if (BackpackSupport.isBackpack(stack)) {
                backpacks.add(new BackpackStorageContainer(this, slot, stack));
            }
        }
    }

    private static boolean isVanishing(ItemStack stack) {
        return EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP);
    }

    public boolean canAccess(Player player) {
        if (!isActive() || !(player instanceof ServerPlayer) || !player.isAlive() || player.isSpectator()) {
            return false;
        }
        if (player.getUUID().equals(victimId) || player.level().dimension() != dimension) {
            return false;
        }
        double max = InventoryStealerConfig.maxLootDistance();
        return player.distanceToSqr(position) <= max * max;
    }

    /** Ends ownership: hands out every remaining item exactly once and leaves the session empty. */
    PendingRestoration drain() {
        if (state == State.ACTIVE) {
            throw new IllegalStateException("Session " + id + " must be finalized before draining");
        }
        flushBackpacks();
        NonNullList<ItemStack> items = victimItems.items();
        NonNullList<ItemStack> remaining = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                remaining.set(i, stack);
            }
            items.set(i, ItemStack.EMPTY);
        }
        return new PendingRestoration(id, victimId, victimName, inventorySize, remaining, extraOrigins);
    }

    /** A persistence snapshot that does not transfer ownership (used for world saves while active). */
    PendingRestoration snapshot() {
        flushBackpacks();
        return new PendingRestoration(id, victimId, victimName, inventorySize, victimItems.items(), extraOrigins);
    }

    public void flushBackpacks() {
        for (BackpackStorageContainer backpack : backpacks) {
            backpack.flush();
        }
    }

    void markFinalized() {
        state = State.FINALIZED;
        markDirty();
    }

    void markDirty() {
        dirtyListener.run();
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public UUID victimId() {
        return victimId;
    }

    public String victimName() {
        return victimName;
    }

    public String killerName() {
        return killerName;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public Vec3 position() {
        return position;
    }

    public long createdTick() {
        return createdTick;
    }

    public long deadlineTick() {
        return deadlineTick;
    }

    public int durationTicks() {
        return (int) (deadlineTick - createdTick);
    }

    public int remainingTicks(long now) {
        return (int) Math.max(0L, deadlineTick - now);
    }

    public int inventorySize() {
        return inventorySize;
    }

    public List<EquipmentOrigin> extraOrigins() {
        return Collections.unmodifiableList(extraOrigins);
    }

    public VictimContainer victimContainer() {
        return victimItems;
    }

    public List<BackpackStorageContainer> backpacks() {
        return Collections.unmodifiableList(backpacks);
    }

    public int countItems() {
        int count = 0;
        for (ItemStack stack : victimItems.items()) {
            count += stack.isEmpty() ? 0 : 1;
        }
        return count;
    }

    boolean isDeathConfirmed() {
        return deathConfirmed;
    }

    void confirmDeath() {
        deathConfirmed = true;
    }

    @Nullable
    public UUID corpseId() {
        return corpseId;
    }

    void setCorpseId(@Nullable UUID corpseId) {
        this.corpseId = corpseId;
    }
}
