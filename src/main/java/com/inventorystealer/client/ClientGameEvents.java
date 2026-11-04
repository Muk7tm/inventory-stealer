package com.inventorystealer.client;

import com.inventorystealer.InventoryStealer;
import com.inventorystealer.corpse.CorpseEntity;
import java.util.UUID;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = InventoryStealer.MOD_ID, value = Dist.CLIENT)
public final class ClientGameEvents {
    private ClientGameEvents() {
    }

    /** Swap the vanilla death screen (and its respawn button) for the countdown while our loot window runs. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (ClientDeathState.isActive() && event.getNewScreen() instanceof DeathScreen && !(event.getNewScreen() instanceof LootedDeathScreen)) {
            event.setNewScreen(new LootedDeathScreen());
        }
    }

    /**
     * Vanilla keeps drawing a dead player's own model (fallen over, red tint) until it despawns. While our corpse
     * stands in for that body it would look like a second corpse, so the vanilla body is hidden.
     */
    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        Player player = event.getEntity();
        if (!player.isDeadOrDying()) {
            return;
        }
        UUID id = player.getUUID();
        AABB area = player.getBoundingBox().inflate(16.0D);
        if (!player.level().getEntitiesOfClass(CorpseEntity.class, area, corpse -> corpse.victimId().filter(id::equals).isPresent()).isEmpty()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientDeathState.tick();
    }

    @SubscribeEvent
    public static void onRespawn(ClientPlayerNetworkEvent.Clone event) {
        ClientDeathState.clear();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientDeathState.clear();
    }
}
