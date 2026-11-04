package com.inventorystealer.network;

import com.inventorystealer.client.ClientDeathState;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Only one custom packet is needed: the victim's death countdown. Corpse menus, slot contents, tab selection
 * (container button clicks), the menu timer (data slots) and forced closing all use vanilla menu synchronisation.
 */
public final class ModNetworking {
    private ModNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        // The lambda body (and therefore the client class) is only resolved when a packet arrives on a client.
        registrar.playToClient(DeathCountdownPayload.TYPE, DeathCountdownPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientDeathState.handle(payload)));
    }
}
