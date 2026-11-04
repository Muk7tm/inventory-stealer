package com.inventorystealer.mixin;

import com.inventorystealer.session.SessionManager;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why a mixin: NeoForge 21.1 has no cancellable event before a client-requested respawn
 * ({@code PlayerRespawnPositionEvent} and {@code PlayerRespawnEvent} only fire inside/after PlayerList#respawn).
 * Without this, a modified client could skip the loot countdown by sending PERFORM_RESPAWN.
 *
 * <p>Injected after {@code ensureRunningOnSameThread}, so the check runs on the server thread.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleClientCommand",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;resetLastActionTime()V"),
            cancellable = true)
    private void inventorystealer$holdRespawnDuringLooting(ServerboundClientCommandPacket packet, CallbackInfo ci) {
        if (packet.getAction() == ServerboundClientCommandPacket.Action.PERFORM_RESPAWN
                && !this.player.wonGame
                && SessionManager.isRespawnLocked(this.player)) {
            ci.cancel();
        }
    }
}
