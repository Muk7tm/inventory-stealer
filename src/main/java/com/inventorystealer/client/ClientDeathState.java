package com.inventorystealer.client;

import com.inventorystealer.network.DeathCountdownPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;

/** Client mirror of the victim's loot countdown. Only touched on the client thread. */
public final class ClientDeathState {
    private static boolean active;
    private static int remainingTicks;
    private static int totalTicks;
    private static String killerName = "";

    private ClientDeathState() {
    }

    public static void handle(DeathCountdownPayload payload) {
        if (!payload.active()) {
            clear();
            return;
        }
        active = true;
        remainingTicks = payload.remainingTicks();
        totalTicks = Math.max(1, payload.totalTicks());
        killerName = payload.killerName();
        Minecraft minecraft = Minecraft.getInstance();
        // Show our screen right away: covers doImmediateRespawn (no vanilla death screen) and reconnects.
        if (!(minecraft.screen instanceof LootedDeathScreen) && (minecraft.screen == null || minecraft.screen instanceof DeathScreen)) {
            minecraft.setScreen(new LootedDeathScreen());
        }
    }

    public static void tick() {
        if (active && remainingTicks > 0) {
            remainingTicks--;
        }
    }

    public static void clear() {
        active = false;
        remainingTicks = 0;
    }

    public static boolean isActive() {
        return active;
    }

    public static int remainingTicks() {
        return remainingTicks;
    }

    public static int totalTicks() {
        return totalTicks;
    }

    public static String killerName() {
        return killerName;
    }
}
