package com.inventorystealer.menu;

import com.inventorystealer.registry.ModMenus;
import com.inventorystealer.session.BackpackStorageContainer;
import com.inventorystealer.session.DeathLootSession;
import com.inventorystealer.session.SessionManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Multi-tab loot menu. Every viewer of a corpse gets their own menu, but all server menus of a session wrap the
 * same session-owned containers, so vanilla {@code broadcastChanges} keeps every viewer in sync and a stack can
 * only ever be taken once.
 *
 * <p>Views: view 0 is the victim's inventory; every backpack adds one view per page of up to 54 storage slots
 * (tool slots in the left column). The selected view is chosen with vanilla container button clicks and synced
 * back through a data slot, as are the timer and which backpack tabs are still valid.
 */
public class CorpseMenu extends AbstractContainerMenu {
    // Wide layout: corpse (left) and the looter's own inventory (right) side by side.
    public static final int IMAGE_WIDTH = 382;
    public static final int IMAGE_HEIGHT = 134;
    public static final int COLUMN_X = 8;
    public static final int GRID_X = 30;
    public static final int GRID_Y = 18;
    public static final int GRID_ROWS = 6;
    public static final int GRID_RIGHT = GRID_X + 9 * 18;
    public static final int PAGE_SIZE = 9 * GRID_ROWS;
    public static final int TOOLS_PER_PAGE = GRID_ROWS;
    public static final int EXTRAS_Y = 110;
    public static final int MAX_EXTRA_SLOTS = 10;
    public static final int LOOTER_X = 212;
    public static final int LOOTER_INV_Y = GRID_Y;
    public static final int LOOTER_HOTBAR_Y = GRID_Y + 58;
    private static final int BITS_PER_DATA_SLOT = 15;

    private static final ResourceLocation[] ARMOR_ICONS = {
            InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
            InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET};

    /** One selectable view. {@code tab == -1} is the victim inventory. */
    public record View(int tab, int page, int pageCount) {
    }

    private final CorpseMenuData data;
    @Nullable
    private final DeathLootSession session;
    @Nullable
    private final List<BackpackStorageContainer> serverBackpacks;
    private final List<View> views = new ArrayList<>();
    private final DataSlot activeView = DataSlot.standalone();
    private final DataSlot remainingTicks = DataSlot.standalone();
    private final DataSlot[] tabValidity;
    private final int looterStart;
    private final int looterEnd;

    private CorpseMenu(int containerId, Inventory looterInventory, CorpseMenuData data, Container victim,
                       List<? extends Container> backpacks, @Nullable DeathLootSession session,
                       @Nullable List<BackpackStorageContainer> serverBackpacks) {
        super(ModMenus.CORPSE.get(), containerId);
        this.data = data;
        this.session = session;
        this.serverBackpacks = serverBackpacks;

        views.add(new View(-1, 0, 1));
        addVictimSlots(victim);
        for (int tab = 0; tab < data.tabs().size(); tab++) {
            addBackpackSlots(tab, data.tabs().get(tab), backpacks.get(tab));
        }

        looterStart = slots.size();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(looterInventory, 9 + row * 9 + col, LOOTER_X + col * 18, LOOTER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(looterInventory, col, LOOTER_X + col * 18, LOOTER_HOTBAR_Y));
        }
        looterEnd = slots.size();

        addDataSlot(activeView);
        addDataSlot(remainingTicks);
        tabValidity = new DataSlot[(data.tabs().size() + BITS_PER_DATA_SLOT - 1) / BITS_PER_DATA_SLOT];
        for (int i = 0; i < tabValidity.length; i++) {
            tabValidity[i] = addDataSlot(DataSlot.standalone());
        }
        activeView.set(0);
        remainingTicks.set(data.totalTicks());
        updateServerData();
    }

    public static CorpseMenu server(int containerId, Inventory looterInventory, DeathLootSession session) {
        List<BackpackStorageContainer> backpacks = List.copyOf(session.backpacks());
        return new CorpseMenu(containerId, looterInventory, CorpseMenuData.of(session), session.victimContainer(), backpacks, session, backpacks);
    }

    public static void writeOpenData(DeathLootSession session, RegistryFriendlyByteBuf buf) {
        CorpseMenuData.of(session).write(buf);
    }

    public static CorpseMenu fromNetwork(int containerId, Inventory looterInventory, RegistryFriendlyByteBuf buf) {
        CorpseMenuData data = CorpseMenuData.read(buf);
        List<Container> backpacks = new ArrayList<>();
        for (CorpseMenuData.Tab tab : data.tabs()) {
            backpacks.add(new SimpleContainer(tab.storageSize() + tab.toolSize()));
        }
        return new CorpseMenu(containerId, looterInventory, data, new SimpleContainer(data.victimSlotCount()), backpacks, null, null);
    }

    // ---------------------------------------------------------------- layout

    private void addVictimSlots(Container victim) {
        int size = data.inventorySize();
        for (int i = 0; i < Math.min(size, 36); i++) {
            int x = GRID_X + (i % 9) * 18;
            int y = i < 9 ? GRID_Y + 58 : GRID_Y + ((i - 9) / 9) * 18;
            addSlot(new CorpseSlot(this, 0, victim, i, x, y, null));
        }
        for (int i = 36; i < Math.min(size, 40); i++) {
            addSlot(new CorpseSlot(this, 0, victim, i, COLUMN_X, GRID_Y + (39 - i) * 18, ARMOR_ICONS[i - 36]));
        }
        if (size > 40) {
            addSlot(new CorpseSlot(this, 0, victim, 40, COLUMN_X, GRID_Y + 74, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD));
        }
        // Extras: equipped backpacks in the left column, then cursor/crafting-grid items along the bottom row.
        int placed = 0;
        int rowIndex = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int e = 0; e < data.extraIsEquipment().size() && placed < MAX_EXTRA_SLOTS; e++) {
                boolean equipment = data.extraIsEquipment().get(e);
                if (equipment != (pass == 0)) {
                    continue;
                }
                int x;
                if (equipment && placed == 0) {
                    x = COLUMN_X;
                } else {
                    x = GRID_X + rowIndex * 18;
                    rowIndex++;
                }
                addSlot(new CorpseSlot(this, 0, victim, size + e, x, EXTRAS_Y, null));
                placed++;
            }
        }
    }

    private void addBackpackSlots(int tab, CorpseMenuData.Tab info, Container container) {
        int storagePages = (info.storageSize() + PAGE_SIZE - 1) / PAGE_SIZE;
        int toolPages = (info.toolSize() + TOOLS_PER_PAGE - 1) / TOOLS_PER_PAGE;
        int pageCount = Math.max(1, Math.max(storagePages, toolPages));
        for (int page = 0; page < pageCount; page++) {
            int view = views.size();
            views.add(new View(tab, page, pageCount));
            for (int s = page * PAGE_SIZE; s < Math.min(info.storageSize(), (page + 1) * PAGE_SIZE); s++) {
                int k = s - page * PAGE_SIZE;
                addSlot(new CorpseSlot(this, view, container, s, GRID_X + (k % 9) * 18, GRID_Y + (k / 9) * 18, null));
            }
            for (int t = page * TOOLS_PER_PAGE; t < Math.min(info.toolSize(), (page + 1) * TOOLS_PER_PAGE); t++) {
                int k = t - page * TOOLS_PER_PAGE;
                addSlot(new CorpseSlot(this, view, container, info.storageSize() + t, COLUMN_X, GRID_Y + k * 18, null));
            }
        }
    }

    // ---------------------------------------------------------------- state

    public CorpseMenuData data() {
        return data;
    }

    public List<View> views() {
        return Collections.unmodifiableList(views);
    }

    public int getActiveView() {
        int view = activeView.get();
        return view >= 0 && view < views.size() ? view : 0;
    }

    public int getRemainingTicks() {
        return Math.max(0, remainingTicks.get());
    }

    public boolean isFor(DeathLootSession candidate) {
        return session == candidate;
    }

    public boolean isTabValid(int tab) {
        if (tab < 0) {
            return true;
        }
        if (serverBackpacks != null) {
            return tab < serverBackpacks.size() && serverBackpacks.get(tab).isValid();
        }
        int slot = tab / BITS_PER_DATA_SLOT;
        return slot < tabValidity.length && (tabValidity[slot].get() & (1 << (tab % BITS_PER_DATA_SLOT))) != 0;
    }

    public boolean isViewAvailable(int view) {
        return view >= 0 && view < views.size() && isTabValid(views.get(view).tab());
    }

    public boolean isViewShown(int view) {
        return getActiveView() == view && isViewAvailable(view);
    }

    /** First view (page 0) of a tab; tab -1 is the victim inventory. */
    public int firstViewOf(int tab) {
        for (int v = 0; v < views.size(); v++) {
            if (views.get(v).tab() == tab) {
                return v;
            }
        }
        return 0;
    }

    public boolean canLoot(Player player) {
        return session == null || session.canAccess(player);
    }

    /** Client-side prediction of a tab switch; the server confirms it through the synced data slot. */
    public void selectViewClient(int view) {
        if (isViewAvailable(view)) {
            activeView.set(view);
        }
    }

    private void updateServerData() {
        if (session == null || serverBackpacks == null) {
            return;
        }
        remainingTicks.set(session.remainingTicks(SessionManager.now()));
        for (int i = 0; i < tabValidity.length; i++) {
            int bits = 0;
            for (int b = 0; b < BITS_PER_DATA_SLOT; b++) {
                int tab = i * BITS_PER_DATA_SLOT + b;
                if (tab < serverBackpacks.size() && serverBackpacks.get(tab).isValid()) {
                    bits |= 1 << b;
                }
            }
            tabValidity[i].set(bits);
        }
        if (!isViewAvailable(activeView.get())) {
            activeView.set(0);
        }
    }

    // ---------------------------------------------------------------- vanilla menu hooks

    @Override
    public void broadcastChanges() {
        updateServerData();
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!isViewAvailable(id)) {
            return false;
        }
        activeView.set(id);
        return true;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (session != null) {
            // A stale menu (session ended, or a hidden/stolen tab) can never move items.
            if (!session.isActive() || !session.canAccess(player)) {
                return;
            }
            if (slotId >= 0 && slotId < slots.size() && slots.get(slotId) instanceof CorpseSlot corpseSlot && !corpseSlot.isActive()) {
                return;
            }
        }
        super.clicked(slotId, button, clickType, player);
        if (session != null) {
            session.flushBackpacks();
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size() || !(slots.get(index) instanceof CorpseSlot slot)) {
            // Corpses are take-only: shift-clicking your own items does nothing.
            return ItemStack.EMPTY;
        }
        if (!slot.hasItem() || !slot.mayPickup(player)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (!moveItemStackTo(stack, looterStart, looterEnd, true)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY, original);
        } else {
            slot.setChanged();
        }
        slot.onTake(player, original);
        return original;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return !(slot instanceof CorpseSlot corpseSlot) || corpseSlot.isActive();
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return !(slot instanceof CorpseSlot);
    }

    @Override
    public boolean stillValid(Player player) {
        return session == null || session.canAccess(player);
    }
}
