package com.inventorystealer.session;

import com.inventorystealer.InventoryStealer;
import com.inventorystealer.corpse.CorpseEntity;
import com.inventorystealer.menu.CorpseMenu;
import com.inventorystealer.menu.CorpseSlot;
import com.mojang.authlib.GameProfile;
import com.tiviacz.travelersbackpack.TravelersBackpack;
import com.tiviacz.travelersbackpack.capability.AttachmentUtils;
import com.tiviacz.travelersbackpack.components.BackpackContainerContents;
import com.tiviacz.travelersbackpack.init.ModDataComponents;
import com.tiviacz.travelersbackpack.init.ModItems;
import com.tiviacz.travelersbackpack.inventory.BackpackWrapper;
import com.tiviacz.travelersbackpack.inventory.Tiers;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import top.theillusivec4.curios.api.CuriosApi;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Runs real deaths, menus, ticks and respawns on a NeoForge GameTest server with Traveler's Backpack 10.1.35 and
 * Curios 9.5.1 loaded. Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(InventoryStealer.MOD_ID)
@PrefixGameTestTemplate(false)
public class InventoryStealerGameTests {
    private static final String ARENA = "arena";
    /** Default lootDurationSeconds (5s) plus margin. */
    private static final int AFTER_EXPIRY = 110;

    // ------------------------------------------------------------------ helpers

    private static ServerPlayer spawn(GameTestHelper helper, double x, double z) {
        UUID id = UUID.randomUUID();
        ServerPlayer player = join(helper, new GameProfile(id, "p" + id.toString().substring(0, 8)));
        player.setGameMode(GameType.SURVIVAL);
        Vec3 pos = helper.absoluteVec(new Vec3(x, 1.0, z));
        player.teleportTo(helper.getLevel(), pos.x, pos.y, pos.z, 0.0F, 0.0F);
        return player;
    }

    /** A real ServerPlayer on an in-memory connection whose modded payload channels are negotiated like a client's. */
    private static ServerPlayer join(GameTestHelper helper, GameProfile profile) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static void kill(ServerPlayer victim, DamageSource source) {
        victim.setHealth(0.0F);
        victim.die(source);
    }

    private static PlayerList players(GameTestHelper helper) {
        return helper.getLevel().getServer().getPlayerList();
    }

    private static void cleanup(GameTestHelper helper, ServerPlayer... created) {
        for (ServerPlayer player : created) {
            ServerPlayer current = players(helper).getPlayer(player.getUUID());
            if (current != null) {
                players(helper).remove(current);
            }
        }
    }

    private static List<CorpseEntity> corpses(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(CorpseEntity.class, helper.getBounds());
    }

    private static CorpseMenu open(GameTestHelper helper, ServerPlayer looter) {
        List<CorpseEntity> found = corpses(helper);
        helper.assertTrue(found.size() == 1, "expected exactly one corpse, found " + found.size());
        helper.assertTrue(SessionManager.tryOpen(looter, found.get(0)), "looter could not open the corpse");
        helper.assertTrue(looter.containerMenu instanceof CorpseMenu, "corpse menu not open");
        return (CorpseMenu) looter.containerMenu;
    }

    /** Menu slot index of a corpse slot by view and container slot. */
    private static int slot(CorpseMenu menu, int view, int containerSlot) {
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i) instanceof CorpseSlot corpseSlot && corpseSlot.view() == view && corpseSlot.getContainerSlot() == containerSlot) {
                return i;
            }
        }
        throw new IllegalStateException("no slot " + containerSlot + " in view " + view);
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

    private static ItemStack backpack(int storageSlot, ItemStack content) {
        ItemStack backpack = new ItemStack(ModItems.STANDARD_TRAVELERS_BACKPACK.get());
        BackpackWrapper.initializeSize(backpack);
        int size = backpack.get(ModDataComponents.STORAGE_SLOTS.get());
        NonNullList<ItemStack> storage = NonNullList.withSize(size, ItemStack.EMPTY);
        storage.set(storageSlot, content);
        backpack.set(ModDataComponents.BACKPACK_CONTAINER.get(), BackpackContainerContents.fromItems(size, storage));
        return backpack;
    }

    private static ItemStack storageSlot(ItemStack backpack, int slot) {
        BackpackContainerContents contents = backpack.get(ModDataComponents.BACKPACK_CONTAINER.get());
        return contents == null || slot >= contents.getItems().size() ? ItemStack.EMPTY : contents.getItems().get(slot);
    }

    private static void wear(ServerPlayer player, ItemStack backpack) {
        if (TravelersBackpack.enableIntegration()) {
            CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("back", 0, backpack);
        } else {
            AttachmentUtils.equipBackpack(player, backpack);
        }
    }

    private static ItemStack worn(ServerPlayer player) {
        return AttachmentUtils.getWearingBackpack(player);
    }

    // ------------------------------------------------------------------ attribution

    @GameTest(template = ARENA, timeoutTicks = 300)
    public static void pvpMeleeDeathCreatesOneCorpseAndHoldsRespawn(GameTestHelper helper) {
        ServerPlayer victim = spawn(helper, 5, 5);
        ServerPlayer killer = spawn(helper, 6, 5);
        victim.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 32));
        victim.getInventory().setItem(39, new ItemStack(Items.DIAMOND_HELMET));
        victim.getInventory().setItem(40, new ItemStack(Items.SHIELD));
        UUID victimId = victim.getUUID();

        kill(victim, victim.damageSources().playerAttack(killer));

        DeathLootSession session = SessionManager.getActiveForVictim(victimId);
        helper.assertTrue(session != null, "no session after PvP death");
        helper.assertTrue(victim.getInventory().isEmpty(), "victim inventory should be owned by the session");
        helper.assertTrue(session.victimContainer().getItem(0).getCount() == 32, "diamonds not captured");
        helper.assertTrue(session.victimContainer().getItem(39).is(Items.DIAMOND_HELMET), "helmet not captured");
        helper.assertTrue(session.victimContainer().getItem(40).is(Items.SHIELD), "offhand not captured");
        helper.assertTrue(corpses(helper).size() == 1, "expected exactly one corpse");
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds()).isEmpty(), "nothing may be dropped");

        // A modified client asking to respawn early must be ignored.
        victim.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        helper.assertTrue(players(helper).getPlayer(victimId) == victim && victim.isDeadOrDying(), "early respawn was not blocked");

        helper.startSequence()
                .thenExecuteAfter(AFTER_EXPIRY, () -> {
                    ServerPlayer respawned = players(helper).getPlayer(victimId);
                    helper.assertTrue(respawned != null && respawned != victim && respawned.isAlive(), "victim was not respawned automatically");
                    helper.assertTrue(respawned.getInventory().getItem(0).getCount() == 32, "diamonds not returned to hotbar slot 0");
                    helper.assertTrue(respawned.getInventory().getItem(39).is(Items.DIAMOND_HELMET), "helmet not returned to head slot");
                    helper.assertTrue(respawned.getInventory().getItem(40).is(Items.SHIELD), "shield not returned to offhand");
                    helper.assertTrue(corpses(helper).isEmpty(), "corpse not removed");
                    helper.assertTrue(SessionManager.getActiveForVictim(victimId) == null, "session not finalized");
                    cleanup(helper, respawned, killer);
                })
                .thenSucceed();
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void projectileKillCounts(GameTestHelper helper) {
        ServerPlayer victim = spawn(helper, 5, 5);
        ServerPlayer shooter = spawn(helper, 2, 2);
        victim.getInventory().setItem(3, new ItemStack(Items.GOLD_INGOT, 5));
        Arrow arrow = new Arrow(helper.getLevel(), shooter, new ItemStack(Items.ARROW), null);
        kill(victim, victim.damageSources().arrow(arrow, shooter));
        helper.assertTrue(SessionManager.getActiveForVictim(victim.getUUID()) != null, "arrow kill by a player must count");
        helper.succeedWhen(() -> {
            DeathLootSession session = SessionManager.getActiveForVictim(victim.getUUID());
            if (session != null) {
                SessionManager.finalizeSession(session, "test cleanup");
            }
            cleanup(helper, victim, shooter);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void nonPvpDeathsKeepNormalBehaviour(GameTestHelper helper) {
        ServerPlayer fallVictim = spawn(helper, 3, 3);
        ServerPlayer mobVictim = spawn(helper, 7, 7);
        ServerPlayer selfVictim = spawn(helper, 3, 7);
        ServerPlayer fakeVictim = spawn(helper, 7, 3);
        ServerPlayer commandVictim = spawn(helper, 5, 5);
        List<ServerPlayer> victims = List.of(fallVictim, mobVictim, selfVictim, fakeVictim, commandVictim);
        for (ServerPlayer p : victims) {
            p.getInventory().setItem(0, new ItemStack(Items.EMERALD, 7));
        }
        Zombie zombie = new Zombie(EntityType.ZOMBIE, helper.getLevel());
        kill(fallVictim, fallVictim.damageSources().fall());
        kill(mobVictim, mobVictim.damageSources().mobAttack(zombie));
        kill(selfVictim, selfVictim.damageSources().playerAttack(selfVictim));
        kill(fakeVictim, fakeVictim.damageSources().playerAttack(FakePlayerFactory.getMinecraft(helper.getLevel())));
        kill(commandVictim, commandVictim.damageSources().genericKill());
        for (ServerPlayer p : victims) {
            helper.assertTrue(SessionManager.getActiveForVictim(p.getUUID()) == null, "non-PvP death created a session");
        }
        helper.assertTrue(corpses(helper).isEmpty(), "non-PvP death created a corpse");
        boolean keepInventory = helper.getLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
        if (!keepInventory) {
            int dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds()).stream()
                    .filter(e -> e.getItem().is(Items.EMERALD)).mapToInt(e -> e.getItem().getCount()).sum();
            helper.assertTrue(dropped == 35, "vanilla drops should be untouched, got " + dropped);
        }
        cleanup(helper, fallVictim, mobVictim, selfVictim, fakeVictim, commandVictim);
        helper.succeed();
    }

    // ------------------------------------------------------------------ looting

    @GameTest(template = ARENA, timeoutTicks = 300)
    public static void anyPlayerCanLootAndStacksCannotBeDuplicated(GameTestHelper helper) {
        ServerPlayer victim = spawn(helper, 5, 5);
        ServerPlayer killer = spawn(helper, 6, 5);
        ServerPlayer looterC = spawn(helper, 4, 5);
        ServerPlayer looterD = spawn(helper, 5, 6);
        victim.getInventory().setItem(12, new ItemStack(Items.DIAMOND, 32));
        victim.getInventory().setItem(13, new ItemStack(Items.IRON_INGOT, 10));
        UUID victimId = victim.getUUID();
        kill(victim, victim.damageSources().playerAttack(killer));

        CorpseMenu menuC = open(helper, looterC);
        CorpseMenu menuD = open(helper, looterD);
        CorpseMenu menuA = open(helper, killer);
        int diamonds = slot(menuC, 0, 12);
        helper.assertTrue(menuD.getSlot(diamonds).getItem().getCount() == 32, "D should see the diamonds");

        menuC.clicked(diamonds, 0, ClickType.QUICK_MOVE, looterC);
        helper.assertTrue(count(looterC.getInventory(), Items.DIAMOND) == 32, "C did not receive the diamonds");
        helper.assertTrue(menuD.getSlot(diamonds).getItem().isEmpty(), "D's view still shows the taken diamonds");
        menuD.clicked(diamonds, 0, ClickType.QUICK_MOVE, looterD);
        menuD.clicked(diamonds, 0, ClickType.PICKUP, looterD);
        helper.assertTrue(count(looterD.getInventory(), Items.DIAMOND) == 0 && menuD.getCarried().isEmpty(), "D duplicated the diamonds");

        // Killer has no special rights but the same access; takes half the iron with a right click.
        int iron = slot(menuA, 0, 13);
        menuA.clicked(iron, 1, ClickType.PICKUP, killer);
        helper.assertTrue(menuA.getCarried().getCount() == 5, "right-click should take half the stack");
        // Corpses are take-only: dropping the carried iron into an empty corpse slot (left or right click) is refused.
        int empty = slot(menuA, 0, 20);
        menuA.clicked(empty, 0, ClickType.PICKUP, killer);
        menuA.clicked(empty, 1, ClickType.PICKUP, killer);
        helper.assertTrue(menuA.getCarried().getCount() == 5 && menuA.getSlot(empty).getItem().isEmpty(),
                "items must not be inserted into a corpse");
        // Shift-clicking one's own items does not move them into the corpse either.
        killer.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 3));
        for (int i = 0; i < menuA.slots.size(); i++) {
            if (!(menuA.slots.get(i) instanceof CorpseSlot) && menuA.slots.get(i).getItem().is(Items.COBBLESTONE)) {
                menuA.clicked(i, 0, ClickType.QUICK_MOVE, killer);
            }
        }
        helper.assertTrue(count(killer.getInventory(), Items.COBBLESTONE) == 3, "own items were moved into the corpse");

        helper.startSequence()
                .thenExecuteAfter(AFTER_EXPIRY, () -> {
                    ServerPlayer respawned = players(helper).getPlayer(victimId);
                    helper.assertTrue(respawned != null && respawned.isAlive(), "victim not respawned");
                    helper.assertTrue(count(respawned.getInventory(), Items.DIAMOND) == 0, "victim got stolen diamonds back");
                    helper.assertTrue(count(respawned.getInventory(), Items.IRON_INGOT) == 5, "victim should get the remaining 5 iron");
                    helper.assertTrue(count(killer.getInventory(), Items.IRON_INGOT) == 5, "killer's carried iron must end up in their inventory");
                    for (ServerPlayer looter : List.of(killer, looterC, looterD)) {
                        helper.assertTrue(looter.containerMenu == looter.inventoryMenu, "corpse menu still open after expiry");
                    }
                    int totalDiamonds = count(respawned.getInventory(), Items.DIAMOND) + count(looterC.getInventory(), Items.DIAMOND)
                            + count(looterD.getInventory(), Items.DIAMOND) + count(killer.getInventory(), Items.DIAMOND);
                    helper.assertTrue(totalDiamonds == 32, "diamond count not conserved: " + totalDiamonds);
                    cleanup(helper, respawned, killer, looterC, looterD);
                })
                .thenSucceed();
    }

    @GameTest(template = ARENA, timeoutTicks = 300)
    public static void backpackTabsUseRealSeparateStorage(GameTestHelper helper) {
        ServerPlayer victim = spawn(helper, 5, 5);
        ServerPlayer killer = spawn(helper, 6, 5);
        ServerPlayer looter = spawn(helper, 4, 5);
        ItemStack equipped = backpack(5, new ItemStack(Items.DIAMOND, 20));
        ItemStack carried = backpack(5, new ItemStack(Items.EMERALD, 9));
        wear(victim, equipped);
        victim.getInventory().setItem(2, carried);
        helper.assertTrue(!worn(victim).isEmpty(), "test setup: backpack not worn");
        UUID victimId = victim.getUUID();

        kill(victim, victim.damageSources().playerAttack(killer));
        DeathLootSession session = SessionManager.getActiveForVictim(victimId);
        helper.assertTrue(session != null && session.backpacks().size() == 2, "expected two backpack tabs");
        helper.assertTrue(worn(victim).isEmpty(), "worn backpack must be moved into the session");
        helper.assertTrue(session.backpacks().get(0).backpack() == equipped, "tab 1 must be the worn backpack");
        helper.assertTrue(session.backpacks().get(1).backpack() == carried, "tab 2 must be the hotbar backpack");

        CorpseMenu menu = open(helper, looter);
        int view = menu.firstViewOf(0);
        menu.clickMenuButton(looter, view);
        menu.broadcastChanges();
        helper.assertTrue(menu.getActiveView() == view, "tab switch failed");
        int diamondSlot = slot(menu, view, 5);
        helper.assertTrue(menu.getSlot(diamondSlot).getItem().is(Items.DIAMOND), "tab shows wrong contents");
        // A slot of another tab cannot be used while it is hidden.
        menu.clicked(slot(menu, menu.firstViewOf(1), 5), 0, ClickType.QUICK_MOVE, looter);
        helper.assertTrue(count(looter.getInventory(), Items.EMERALD) == 0, "hidden tab slot was usable");

        menu.clicked(diamondSlot, 0, ClickType.QUICK_MOVE, looter);
        helper.assertTrue(count(looter.getInventory(), Items.DIAMOND) == 20, "looter did not get the backpack diamonds");
        helper.assertTrue(storageSlot(equipped, 5).isEmpty(), "diamonds still inside the backpack component");
        helper.assertTrue(storageSlot(carried, 5).getCount() == 9, "other backpack was modified");

        helper.startSequence()
                .thenExecuteAfter(AFTER_EXPIRY, () -> {
                    ServerPlayer respawned = players(helper).getPlayer(victimId);
                    helper.assertTrue(respawned != null && respawned.isAlive(), "victim not respawned");
                    ItemStack back = worn(respawned);
                    helper.assertTrue(!back.isEmpty(), "worn backpack not re-equipped");
                    helper.assertTrue(storageSlot(back, 5).isEmpty(), "stolen diamonds came back");
                    ItemStack hotbar = respawned.getInventory().getItem(2);
                    helper.assertTrue(hotbar.is(ModItems.STANDARD_TRAVELERS_BACKPACK.get()) && storageSlot(hotbar, 5).getCount() == 9,
                            "hotbar backpack or its emeralds not returned");
                    helper.assertTrue(count(looter.getInventory(), Items.DIAMOND) == 20, "looter lost the diamonds");
                    cleanup(helper, respawned, killer, looter);
                })
                .thenSucceed();
    }

    @GameTest(template = ARENA, timeoutTicks = 300)
    public static void stealingWholeBackpackInvalidatesOtherViewers(GameTestHelper helper) {
        ServerPlayer victim = spawn(helper, 5, 5);
        ServerPlayer killer = spawn(helper, 6, 5);
        ServerPlayer viewer = spawn(helper, 4, 5);
        ServerPlayer thief = spawn(helper, 5, 4);
        wear(victim, backpack(0, new ItemStack(Items.GOLD_BLOCK, 3)));
        UUID victimId = victim.getUUID();
        kill(victim, victim.damageSources().playerAttack(killer));
        DeathLootSession session = SessionManager.getActiveForVictim(victimId);
        helper.assertTrue(session != null && session.backpacks().size() == 1, "expected one backpack tab");
        int backpackVictimSlot = session.backpacks().get(0).victimSlot();

        CorpseMenu viewerMenu = open(helper, viewer);
        int view = viewerMenu.firstViewOf(0);
        viewerMenu.clickMenuButton(viewer, view);
        CorpseMenu thiefMenu = open(helper, thief);
        thiefMenu.clicked(slot(thiefMenu, 0, backpackVictimSlot), 0, ClickType.QUICK_MOVE, thief);

        ItemStack stolen = ItemStack.EMPTY;
        for (int i = 0; i < thief.getInventory().getContainerSize(); i++) {
            if (thief.getInventory().getItem(i).is(ModItems.STANDARD_TRAVELERS_BACKPACK.get())) {
                stolen = thief.getInventory().getItem(i);
            }
        }
        helper.assertTrue(!stolen.isEmpty() && storageSlot(stolen, 0).getCount() == 3, "stolen backpack lost its contents");

        viewerMenu.broadcastChanges();
        helper.assertTrue(!viewerMenu.isTabValid(0), "viewer's backpack tab still valid");
        helper.assertTrue(viewerMenu.getActiveView() == 0, "viewer not moved back to the inventory tab");
        viewerMenu.clicked(slot(viewerMenu, view, 0), 0, ClickType.QUICK_MOVE, viewer);
        viewerMenu.clicked(slot(viewerMenu, view, 0), 0, ClickType.PICKUP, viewer);
        helper.assertTrue(count(viewer.getInventory(), Items.GOLD_BLOCK) == 0 && viewerMenu.getCarried().isEmpty(),
                "gold duplicated through a stale backpack tab");

        helper.startSequence()
                .thenExecuteAfter(AFTER_EXPIRY, () -> {
                    ServerPlayer respawned = players(helper).getPlayer(victimId);
                    helper.assertTrue(respawned != null && respawned.isAlive(), "victim not respawned");
                    helper.assertTrue(worn(respawned).isEmpty(), "victim got the stolen backpack back");
                    helper.assertTrue(count(respawned.getInventory(), ModItems.STANDARD_TRAVELERS_BACKPACK.get().asItem()) == 0, "backpack duplicated");
                    cleanup(helper, respawned, killer, viewer, thief);
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------ lifecycle & safety

    @GameTest(template = ARENA, timeoutTicks = 300)
    public static void disconnectedVictimGetsItemsAfterReconnect(GameTestHelper helper) {
        ServerPlayer victim = spawn(helper, 5, 5);
        ServerPlayer killer = spawn(helper, 6, 5);
        victim.getInventory().setItem(20, new ItemStack(Items.NETHERITE_INGOT, 4));
        GameProfile profile = victim.getGameProfile();
        kill(victim, victim.damageSources().playerAttack(killer));
        players(helper).remove(victim);

        helper.startSequence()
                .thenExecuteAfter(AFTER_EXPIRY, () -> {
                    helper.assertTrue(SessionManager.getActiveForVictim(profile.getId()) == null, "session should have expired");
                    helper.assertTrue(SessionManager.pendingCount(profile.getId()) == 1, "offline victim's items not stored");
                    ServerPlayer back = join(helper, profile);
                    helper.assertTrue(back.isDeadOrDying(), "reconnected victim should still be dead");
                    // Not locked any more: the normal respawn button works and delivers the stored items.
                    back.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    ServerPlayer respawned = players(helper).getPlayer(profile.getId());
                    helper.assertTrue(respawned != null && respawned.isAlive(), "reconnected victim could not respawn");
                    helper.assertTrue(respawned.getInventory().getItem(20).getCount() == 4, "stored items not restored on respawn");
                    helper.assertTrue(SessionManager.pendingCount(profile.getId()) == 0, "pending entry not consumed");
                    cleanup(helper, respawned, killer);
                })
                .thenSucceed();
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void cancelledDeathRollsBack(GameTestHelper helper) {
        ServerPlayer victim = spawn(helper, 5, 5);
        ServerPlayer killer = spawn(helper, 6, 5);
        victim.getInventory().setItem(8, new ItemStack(Items.TNT, 16));
        // Capture happens, but the player never actually dies (as if a later listener cancelled the death).
        SessionManager.onPlayerDeath(victim, victim.damageSources().playerAttack(killer));
        helper.assertTrue(victim.getInventory().getItem(8).isEmpty(), "capture did not happen");
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(SessionManager.getActiveForVictim(victim.getUUID()) == null, "session not rolled back");
                    helper.assertTrue(victim.getInventory().getItem(8).getCount() == 16, "items not returned after cancelled death");
                    helper.assertTrue(corpses(helper).isEmpty(), "corpse not removed on rollback");
                    cleanup(helper, victim, killer);
                })
                .thenSucceed();
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void savedSessionReloadsAsPendingRestoration(GameTestHelper helper) {
        ServerPlayer victim = spawn(helper, 5, 5);
        ServerPlayer killer = spawn(helper, 6, 5);
        ItemStack pack = backpack(7, new ItemStack(Items.ENDER_PEARL, 16));
        victim.getInventory().setItem(30, pack);
        victim.getInventory().setItem(1, new ItemStack(Items.COOKED_BEEF, 12));
        kill(victim, victim.damageSources().playerAttack(killer));

        SessionSavedData live = SessionManager.savedData();
        helper.assertTrue(live != null, "no saved data");
        CompoundTag saved = live.save(new CompoundTag(), helper.getLevel().registryAccess());
        SessionSavedData reloaded = SessionSavedData.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.pendingCount(victim.getUUID()) == 1, "active session not persisted as a restoration");
        helper.assertTrue(reloaded.activeSessions().isEmpty(), "a reloaded session must never become lootable again");
        PendingRestoration restoration = reloaded.takePending(victim.getUUID()).get(0);
        helper.assertTrue(restoration.items().get(1).getCount() == 12, "beef not persisted");
        helper.assertTrue(storageSlot(restoration.items().get(30), 7).getCount() == 16, "backpack contents not persisted");

        DeathLootSession session = SessionManager.getActiveForVictim(victim.getUUID());
        SessionManager.finalizeSession(session, "test cleanup");
        helper.succeedWhen(() -> cleanup(helper, players(helper).getPlayer(victim.getUUID()), killer));
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void vanishingItemsFollowVanillaWithoutKeepInventory(GameTestHelper helper) {
        if (helper.getLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            helper.succeed();
            return;
        }
        ServerPlayer victim = spawn(helper, 5, 5);
        ServerPlayer killer = spawn(helper, 6, 5);
        ItemStack cursed = new ItemStack(Items.GOLDEN_SWORD);
        cursed.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.VANISHING_CURSE), 1);
        victim.getInventory().setItem(4, cursed);
        kill(victim, victim.damageSources().playerAttack(killer));
        DeathLootSession session = SessionManager.getActiveForVictim(victim.getUUID());
        helper.assertTrue(session != null && session.victimContainer().getItem(4).isEmpty(), "vanishing item must not be lootable");
        helper.assertTrue(victim.getInventory().isEmpty(), "vanishing item should be destroyed by vanilla");
        SessionManager.finalizeSession(session, "test cleanup");
        helper.succeedWhen(() -> cleanup(helper, players(helper).getPlayer(victim.getUUID()), killer));
    }

    /**
     * Every Traveler's Backpack tier (leather/iron/gold/diamond/netherite, sizes from TB's server config) must map
     * onto the paged corpse menu: each storage and tool slot exactly once, no overlap, inside the grid, and lootable.
     */
    @GameTest(template = ARENA, timeoutTicks = 300)
    public static void everyBackpackTierFitsTheMenu(GameTestHelper helper) {
        ServerPlayer victim = spawn(helper, 5, 5);
        ServerPlayer killer = spawn(helper, 6, 5);
        ServerPlayer looter = spawn(helper, 4, 5);
        int[] storageSizes = new int[5];
        int[] toolSizes = new int[5];
        for (int tier = 0; tier < 5; tier++) {
            ItemStack pack = new ItemStack(ModItems.STANDARD_TRAVELERS_BACKPACK.get());
            pack.set(ModDataComponents.TIER.get(), tier);
            BackpackWrapper.initializeSize(pack);
            storageSizes[tier] = pack.get(ModDataComponents.STORAGE_SLOTS.get());
            toolSizes[tier] = pack.get(ModDataComponents.TOOL_SLOTS.get());
            helper.assertTrue(storageSizes[tier] == Tiers.of(tier).getStorageSlots() && toolSizes[tier] == Tiers.of(tier).getToolSlots(),
                    "test setup: tier " + tier + " size mismatch");
            NonNullList<ItemStack> storage = NonNullList.withSize(storageSizes[tier], ItemStack.EMPTY);
            for (int s = 0; s < storage.size(); s++) {
                storage.set(s, new ItemStack(Items.COBBLESTONE, 1 + s % 64));
            }
            pack.set(ModDataComponents.BACKPACK_CONTAINER.get(), BackpackContainerContents.fromItems(storage.size(), storage));
            NonNullList<ItemStack> tools = NonNullList.withSize(toolSizes[tier], ItemStack.EMPTY);
            for (int t = 0; t < tools.size(); t++) {
                tools.set(t, new ItemStack(Items.IRON_PICKAXE));
            }
            pack.set(ModDataComponents.TOOLS_CONTAINER.get(), BackpackContainerContents.fromItems(tools.size(), tools));
            victim.getInventory().setItem(tier, pack);
        }
        kill(victim, victim.damageSources().playerAttack(killer));
        DeathLootSession session = SessionManager.getActiveForVictim(victim.getUUID());
        helper.assertTrue(session != null && session.backpacks().size() == 5, "expected 5 backpack tabs");
        CorpseMenu menu = open(helper, looter);

        for (int tier = 0; tier < 5; tier++) {
            int size = storageSizes[tier] + toolSizes[tier];
            int expectedPages = Math.max(1, Math.max((storageSizes[tier] + CorpseMenu.PAGE_SIZE - 1) / CorpseMenu.PAGE_SIZE,
                    (toolSizes[tier] + CorpseMenu.TOOLS_PER_PAGE - 1) / CorpseMenu.TOOLS_PER_PAGE));
            int[] seen = new int[size];
            int pages = 0;
            for (int view = 0; view < menu.views().size(); view++) {
                if (menu.views().get(view).tab() != tier) {
                    continue;
                }
                pages++;
                helper.assertTrue(menu.views().get(view).pageCount() == expectedPages, "tier " + tier + ": wrong page count");
                java.util.Set<Long> positions = new java.util.HashSet<>();
                for (net.minecraft.world.inventory.Slot slot : menu.slots) {
                    if (!(slot instanceof CorpseSlot corpseSlot) || corpseSlot.view() != view) {
                        continue;
                    }
                    seen[slot.getContainerSlot()]++;
                    helper.assertTrue(positions.add(((long) slot.x << 32) | slot.y), "tier " + tier + ": overlapping slots in view " + view);
                    helper.assertTrue(slot.x >= CorpseMenu.COLUMN_X && slot.x + 16 <= CorpseMenu.GRID_RIGHT
                                    && slot.y >= CorpseMenu.GRID_Y && slot.y + 16 <= CorpseMenu.GRID_Y + CorpseMenu.GRID_ROWS * 18,
                            "tier " + tier + ": slot outside the corpse grid at " + slot.x + "," + slot.y);
                    boolean tool = slot.getContainerSlot() >= storageSizes[tier];
                    helper.assertTrue(tool == (slot.x == CorpseMenu.COLUMN_X), "tier " + tier + ": tool/storage slot in the wrong column");
                }
            }
            helper.assertTrue(pages == expectedPages, "tier " + tier + ": expected " + expectedPages + " pages, got " + pages);
            for (int i = 0; i < size; i++) {
                helper.assertTrue(seen[i] == 1, "tier " + tier + ": backpack slot " + i + " shown " + seen[i] + " times");
            }

            // The very last storage slot (on the last page) and the last tool slot can be looted.
            ItemStack pack = session.backpacks().get(tier).backpack();
            for (int containerSlot : new int[]{storageSizes[tier] - 1, size - 1}) {
                int view = -1;
                for (net.minecraft.world.inventory.Slot slot : menu.slots) {
                    if (slot instanceof CorpseSlot corpseSlot && menu.views().get(corpseSlot.view()).tab() == tier && slot.getContainerSlot() == containerSlot) {
                        view = corpseSlot.view();
                    }
                }
                menu.clickMenuButton(looter, view);
                menu.clicked(slot(menu, view, containerSlot), 0, ClickType.QUICK_MOVE, looter);
            }
            helper.assertTrue(storageSlot(pack, storageSizes[tier] - 1).isEmpty(), "tier " + tier + ": last storage slot not looted");
            BackpackContainerContents tools = pack.get(ModDataComponents.TOOLS_CONTAINER.get());
            helper.assertTrue(tools.getItems().get(toolSizes[tier] - 1).isEmpty(), "tier " + tier + ": last tool slot not looted");
        }
        helper.assertTrue(count(looter.getInventory(), Items.IRON_PICKAXE) == 5, "looter should hold one pickaxe per tier");
        SessionManager.finalizeSession(session, "test cleanup");
        helper.succeedWhen(() -> cleanup(helper, players(helper).getPlayer(victim.getUUID()), killer, looter));
    }

    @GameTest(template = ARENA, timeoutTicks = 300, batch = "keepInventory")
    public static void keepInventoryTrueDoesNotDuplicate(GameTestHelper helper) {
        GameRules.BooleanValue rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean previous = rule.get();
        rule.set(true, helper.getLevel().getServer());
        ServerPlayer victim = spawn(helper, 5, 5);
        ServerPlayer killer = spawn(helper, 6, 5);
        ServerPlayer looter = spawn(helper, 4, 5);
        victim.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 32));
        victim.getInventory().setItem(1, new ItemStack(Items.GOLD_INGOT, 8));
        wear(victim, backpack(3, new ItemStack(Items.EMERALD, 5)));
        UUID victimId = victim.getUUID();
        kill(victim, victim.damageSources().playerAttack(killer));

        CorpseMenu menu = open(helper, looter);
        menu.clicked(slot(menu, 0, 1), 0, ClickType.QUICK_MOVE, looter);

        helper.startSequence()
                .thenExecuteAfter(AFTER_EXPIRY, () -> {
                    ServerPlayer respawned = players(helper).getPlayer(victimId);
                    helper.assertTrue(respawned != null && respawned.isAlive(), "victim not respawned");
                    helper.assertTrue(count(respawned.getInventory(), Items.DIAMOND) == 32, "diamonds duplicated or lost with keepInventory");
                    helper.assertTrue(count(respawned.getInventory(), Items.GOLD_INGOT) == 0, "stolen gold restored by keepInventory");
                    helper.assertTrue(count(looter.getInventory(), Items.GOLD_INGOT) == 8, "looter lost the gold");
                    ItemStack back = worn(respawned);
                    helper.assertTrue(!back.isEmpty() && storageSlot(back, 3).getCount() == 5, "worn backpack not restored once");
                    rule.set(previous, helper.getLevel().getServer());
                    cleanup(helper, respawned, killer, looter);
                })
                .thenSucceed();
    }
}
