package com.inventorystealer.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Replaces the vanilla death screen during a PvP loot window: no respawn button, a countdown instead.
 * Extends {@link DeathScreen} so vanilla closes it when the server's respawn packet arrives.
 */
public class LootedDeathScreen extends DeathScreen {
    public LootedDeathScreen() {
        super(null, false);
    }

    @Override
    protected void init() {
        // No respawn button: the server respawns the player when the loot window closes.
        addRenderableWidget(Button.builder(Component.translatable("deathScreen.titleScreen"), button -> exitToTitle())
                .bounds(width / 2 - 100, height / 4 + 110, 200, 20)
                .build());
    }

    private void exitToTitle() {
        if (minecraft.level != null) {
            minecraft.level.disconnect();
        }
        minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        minecraft.setScreen(new TitleScreen());
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft.player != null && !minecraft.player.isDeadOrDying() && !ClientDeathState.isActive()) {
            onClose();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        for (Renderable renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }

        graphics.pose().pushPose();
        graphics.pose().scale(2.0F, 2.0F, 2.0F);
        graphics.drawCenteredString(font, Component.translatable("inventorystealer.death.title"), width / 4, 30, 0xFF5555);
        graphics.pose().popPose();

        String killer = ClientDeathState.killerName();
        if (!killer.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("inventorystealer.death.killed_by",
                    Component.literal(killer).withStyle(ChatFormatting.YELLOW)), width / 2, 85, 0xFFFFFF);
        }

        boolean waiting = ClientDeathState.isActive();
        int seconds = Mth.ceil(ClientDeathState.remainingTicks() / 20.0F);
        Component status = waiting
                ? Component.translatable("inventorystealer.death.looting", Component.literal(seconds + "s").withStyle(ChatFormatting.GOLD))
                : Component.translatable("inventorystealer.death.respawning");
        graphics.drawCenteredString(font, status, width / 2, 104, 0xE0E0E0);

        // Progress bar for the remaining loot window.
        int barWidth = 182;
        int barX = width / 2 - barWidth / 2;
        int barY = 120;
        float fraction = waiting ? Mth.clamp(ClientDeathState.remainingTicks() / (float) ClientDeathState.totalTicks(), 0.0F, 1.0F) : 0.0F;
        graphics.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + 6, 0xFF000000);
        graphics.fill(barX, barY, barX + barWidth, barY + 5, 0xFF3A1A1A);
        graphics.fill(barX, barY, barX + Math.round(barWidth * fraction), barY + 5, 0xFFD23C3C);

        graphics.drawCenteredString(font, Component.translatable("inventorystealer.death.auto_respawn"), width / 2, 134, 0xA0A0A0);
    }
}
