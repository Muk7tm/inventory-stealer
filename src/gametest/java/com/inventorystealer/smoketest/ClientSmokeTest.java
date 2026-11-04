package com.inventorystealer.smoketest;

import com.inventorystealer.InventoryStealer;
import com.inventorystealer.config.InventoryStealerConfig;
import com.inventorystealer.client.CorpseScreen;
import com.inventorystealer.client.LootedDeathScreen;
import com.inventorystealer.corpse.CorpseEntity;
import com.inventorystealer.menu.CorpseMenu;
import com.inventorystealer.menu.CorpseSlot;
import com.inventorystealer.session.DeathLootSession;
import com.inventorystealer.session.SessionManager;
import com.mojang.authlib.GameProfile;
import com.tiviacz.travelersbackpack.TravelersBackpack;
import com.tiviacz.travelersbackpack.capability.AttachmentUtils;
import com.tiviacz.travelersbackpack.components.BackpackContainerContents;
import com.tiviacz.travelersbackpack.init.ModDataComponents;
import com.tiviacz.travelersbackpack.init.ModItems;
import com.tiviacz.travelersbackpack.inventory.BackpackWrapper;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.NonNullList;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Dev-only end-to-end check with a real client and its integrated server ({@code ./gradlew runClientSmokeTest}).
 * Drives both roles automatically, saves screenshots to {@code run/client-smoke/screenshots/} and logs
 * {@code [SMOKE] PASS}/{@code FAIL} lines. Inactive unless {@code -Dinventorystealer.clientSmokeTest=true}.
 */
@EventBusSubscriber(modid = InventoryStealer.MOD_ID, value = Dist.CLIENT)
public final class ClientSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("inventorystealer.clientSmokeTest");
    private static final int STEP_TIMEOUT = 800;

    private static int step;
    private static int wait;
    private static int elapsed;
    private static final List<String> failures = new ArrayList<>();
    private static UUID realPlayerId;
    private static UUID looterId;
    private static int viewCursor;

    private ClientSmokeTest() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.player == null || server == null || mc.level == null) {
            return;
        }
        if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
            mc.setScreen(null);
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (++elapsed > STEP_TIMEOUT && step < 99) {
            fail("step " + step + " timed out (screen=" + mc.screen + ")");
            step = 98;
        }
        switch (step) {
            case 0 -> next(80);
            case 1 -> {
                realPlayerId = mc.player.getUUID();
                server.execute(() -> setupLootScenario(server));
                next(5);
            }
            case 2 -> {
                if (mc.screen instanceof CorpseScreen) {
                    next(20);
                }
            }
            case 3 -> {
                shot(mc, "01_loot_gui_player_tab");
                CorpseMenu menu = ((CorpseScreen) mc.screen).getMenu();
                check(menu.data().tabs().size() == 6, "expected 6 backpack tabs, got " + menu.data().tabs().size());
                for (int i = 0; i < menu.slots.size(); i++) {
                    if (menu.slots.get(i) instanceof CorpseSlot slot && slot.view() == 0 && slot.getContainerSlot() == 39) {
                        check(slot.getItem().is(Items.DIAMOND_HELMET), "client does not show the helmet in the head slot");
                    }
                }
                viewCursor = 1;
                select(mc, menu, viewCursor);
                next(10);
            }
            case 4 -> {
                // Every backpack tab and page (all five tiers), one screenshot each.
                CorpseMenu menu = ((CorpseScreen) mc.screen).getMenu();
                CorpseMenu.View view = menu.views().get(menu.getActiveView());
                check(menu.getActiveView() == viewCursor, "client did not switch to view " + viewCursor);
                long shown = menu.slots.stream().filter(s -> s instanceof CorpseSlot && s.isActive() && s.hasItem()).count();
                check(shown > 0, "view " + viewCursor + " shows no items on the client");
                shot(mc, String.format("02_view%02d_tab%d_page%d_of_%d", viewCursor, view.tab() + 1, view.page() + 1, view.pageCount()));
                viewCursor++;
                if (viewCursor < menu.views().size()) {
                    select(mc, menu, viewCursor);
                    wait = 10;
                    elapsed = 0;
                } else {
                    next(0);
                }
            }
            case 5 -> {
                mc.player.closeContainer();
                List<CorpseEntity> corpses = mc.level.getEntitiesOfClass(CorpseEntity.class, mc.player.getBoundingBox().inflate(8));
                check(corpses.size() == 1, "client sees " + corpses.size() + " corpses");
                if (!corpses.isEmpty()) {
                    mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, corpses.get(0).position());
                }
                mc.options.hideGui = true;
                next(20);
            }
            case 6 -> {
                shot(mc, "04_corpse_render");
                mc.options.hideGui = false;
                next(0);
            }
            case 7 -> {
                // Wait for the (extended) first loot window to expire: the corpse disappears on the client.
                List<CorpseEntity> corpses = mc.level.getEntitiesOfClass(CorpseEntity.class, mc.player.getBoundingBox().inflate(8));
                if (corpses.isEmpty()) {
                    server.execute(() -> setupDeathScenario(server));
                    next(5);
                }
            }
            case 8 -> {
                if (mc.screen instanceof LootedDeathScreen) {
                    next(25);
                }
            }
            case 9 -> {
                shot(mc, "05_countdown_death_screen");
                next(0);
            }
            case 10 -> {
                if (mc.player.isAlive() && mc.screen == null) {
                    next(30);
                }
            }
            case 11 -> {
                shot(mc, "06_after_auto_respawn");
                server.execute(() -> verifyRestoration(server));
                next(10);
            }
            case 12 -> step = 98;
            case 98 -> {
                if (failures.isEmpty()) {
                    InventoryStealer.LOGGER.info("[SMOKE] PASS - all client smoke checks passed");
                } else {
                    failures.forEach(f -> InventoryStealer.LOGGER.error("[SMOKE] FAIL - {}", f));
                }
                step = 99;
                wait = 20;
            }
            case 99 -> {
                step = 100;
                mc.stop();
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ server-side scenario setup

    private static void setupLootScenario(MinecraftServer server) {
        ServerPlayer me = server.getPlayerList().getPlayer(realPlayerId);
        ServerLevel level = me.serverLevel();
        // Long enough to screenshot every tab; the death scenario below runs with the default again.
        InventoryStealerConfig.LOOT_DURATION_SECONDS.set(30);
        level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true, server);
        level.setDayTime(6000);
        me.setYRot(0.0F);
        Vec3 front = me.position().add(0.0, 0.0, 2.5);
        ServerPlayer victim = joinMock(server, "Corpse_Test", front);

        Inventory inv = victim.getInventory();
        inv.setItem(0, new ItemStack(Items.DIAMOND, 32));
        inv.setItem(1, new ItemStack(Items.IRON_SWORD));
        inv.setItem(2, new ItemStack(Items.GOLDEN_APPLE, 5));
        inv.setItem(10, new ItemStack(Items.ENDER_PEARL, 16));
        inv.setItem(20, new ItemStack(Items.COOKED_BEEF, 24));
        inv.setItem(36, new ItemStack(Items.DIAMOND_BOOTS));
        inv.setItem(37, new ItemStack(Items.DIAMOND_LEGGINGS));
        inv.setItem(38, new ItemStack(Items.DIAMOND_CHESTPLATE));
        inv.setItem(39, new ItemStack(Items.DIAMOND_HELMET));
        inv.setItem(40, new ItemStack(Items.SHIELD));
        ItemStack netherite = new ItemStack(ModItems.NETHERITE_TRAVELERS_BACKPACK.get());
        netherite.set(ModDataComponents.TIER.get(), 4);
        BackpackWrapper.initializeSize(netherite);
        fill(netherite, new Item[]{Items.OAK_LOG, Items.COBBLESTONE, Items.REDSTONE, Items.LAPIS_LAZULI, Items.GOLD_INGOT}, 70);
        wear(victim, netherite);
        // One carried backpack per remaining tier (0 leather, 1 iron, 2 gold, 3 diamond, 4 netherite), filled to the brim.
        Item[] designs = {ModItems.STANDARD_TRAVELERS_BACKPACK.get(), ModItems.IRON_TRAVELERS_BACKPACK.get(), ModItems.GOLD_TRAVELERS_BACKPACK.get(),
                ModItems.DIAMOND_TRAVELERS_BACKPACK.get(), ModItems.EMERALD_TRAVELERS_BACKPACK.get()};
        Item[] contents = {Items.EMERALD, Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.NETHERITE_SCRAP};
        for (int tier = 0; tier < 5; tier++) {
            ItemStack carried = new ItemStack(designs[tier]);
            carried.set(ModDataComponents.TIER.get(), tier);
            BackpackWrapper.initializeSize(carried);
            fill(carried, new Item[]{contents[tier]}, 256);
            fillTools(carried);
            inv.setItem(4 + tier, carried);
        }

        victim.setHealth(0.0F);
        victim.die(victim.damageSources().playerAttack(me));

        List<CorpseEntity> corpses = level.getEntitiesOfClass(CorpseEntity.class, new AABB(front, front).inflate(2));
        check(corpses.size() == 1, "server: expected one corpse, found " + corpses.size());
        if (!corpses.isEmpty()) {
            check(SessionManager.tryOpen(me, corpses.get(0)), "server: could not open the corpse");
        }
    }

    private static void setupDeathScenario(MinecraftServer server) {
        InventoryStealerConfig.LOOT_DURATION_SECONDS.set(5);
        ServerPlayer me = server.getPlayerList().getPlayer(realPlayerId);
        me.getInventory().clearContent();
        me.getInventory().setItem(5, new ItemStack(Items.GOLD_INGOT, 10));
        me.getInventory().setItem(6, new ItemStack(Items.APPLE, 3));
        ItemStack pack = new ItemStack(ModItems.STANDARD_TRAVELERS_BACKPACK.get());
        BackpackWrapper.initializeSize(pack);
        fill(pack, new Item[]{Items.DIAMOND}, 1);
        wear(me, pack);

        ServerPlayer killer = joinMock(server, "Killer_Test", me.position().add(1.5, 0.0, 0.0));
        ServerPlayer looter = joinMock(server, "Looter_Test", me.position().add(-1.5, 0.0, 0.0));
        looterId = looter.getUUID();
        me.setHealth(0.0F);
        me.die(me.damageSources().playerAttack(killer));

        DeathLootSession session = SessionManager.getActiveForVictim(me.getUUID());
        check(session != null, "server: no session for the real player's death");
        List<CorpseEntity> corpses = me.serverLevel().getEntitiesOfClass(CorpseEntity.class, me.getBoundingBox().inflate(3));
        if (session != null && !corpses.isEmpty() && SessionManager.tryOpen(looter, corpses.get(0)) && looter.containerMenu instanceof CorpseMenu menu) {
            for (int i = 0; i < menu.slots.size(); i++) {
                if (menu.slots.get(i) instanceof CorpseSlot slot && slot.view() == 0 && slot.getContainerSlot() == 5) {
                    menu.clicked(i, 0, ClickType.QUICK_MOVE, looter);
                }
            }
        }
    }

    private static void verifyRestoration(MinecraftServer server) {
        ServerPlayer me = server.getPlayerList().getPlayer(realPlayerId);
        ServerPlayer looter = server.getPlayerList().getPlayer(looterId);
        check(me != null && me.isAlive(), "server: real player not alive after the countdown");
        if (me == null || looter == null) {
            return;
        }
        check(count(me.getInventory(), Items.GOLD_INGOT) == 0, "stolen gold was returned to the victim");
        check(me.getInventory().getItem(6).getCount() == 3, "apples not restored to slot 6");
        check(count(looter.getInventory(), Items.GOLD_INGOT) == 10, "looter does not have the stolen gold");
        ItemStack back = AttachmentUtils.getWearingBackpack(me);
        BackpackContainerContents contents = back.get(ModDataComponents.BACKPACK_CONTAINER.get());
        check(!back.isEmpty() && contents != null && contents.getItems().get(0).is(Items.DIAMOND), "worn backpack or its contents not restored");
        InventoryStealer.LOGGER.info("[SMOKE] restoration verified on server: alive={}, gold={}, apples={}, backpackWorn={}",
                me.isAlive(), count(me.getInventory(), Items.GOLD_INGOT), me.getInventory().getItem(6).getCount(), !back.isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static ServerPlayer joinMock(MinecraftServer server, String name, Vec3 pos) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        ServerPlayer player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        player.teleportTo(server.overworld(), pos.x, pos.y, pos.z, 180.0F, 0.0F);
        return player;
    }

    private static void wear(ServerPlayer player, ItemStack backpack) {
        if (TravelersBackpack.enableIntegration()) {
            CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("back", 0, backpack);
        } else {
            AttachmentUtils.equipBackpack(player, backpack);
        }
    }

    private static void fill(ItemStack backpack, Item[] items, int slots) {
        int size = backpack.get(ModDataComponents.STORAGE_SLOTS.get());
        NonNullList<ItemStack> storage = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(slots, size); i++) {
            storage.set(i, new ItemStack(items[i % items.length], 1 + (i * 7) % 64));
        }
        backpack.set(ModDataComponents.BACKPACK_CONTAINER.get(), BackpackContainerContents.fromItems(size, storage));
    }

    private static void fillTools(ItemStack backpack) {
        int size = backpack.get(ModDataComponents.TOOL_SLOTS.get());
        NonNullList<ItemStack> tools = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int i = 0; i < size; i++) {
            tools.set(i, new ItemStack(i % 2 == 0 ? Items.IRON_PICKAXE : Items.IRON_AXE));
        }
        backpack.set(ModDataComponents.TOOLS_CONTAINER.get(), BackpackContainerContents.fromItems(size, tools));
    }

    private static int count(Inventory inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(item)) {
                total += inventory.getItem(i).getCount();
            }
        }
        return total;
    }

    private static void select(Minecraft mc, CorpseMenu menu, int view) {
        menu.selectViewClient(view);
        mc.gameMode.handleInventoryButtonClick(menu.containerId, view);
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), message -> InventoryStealer.LOGGER.info("[SMOKE] screenshot {}", name));
    }

    private static void next(int ticks) {
        step++;
        wait = ticks;
        elapsed = 0;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            fail(message);
        }
    }

    private static synchronized void fail(String message) {
        failures.add(message);
        InventoryStealer.LOGGER.error("[SMOKE] check failed: {}", message);
    }
}
