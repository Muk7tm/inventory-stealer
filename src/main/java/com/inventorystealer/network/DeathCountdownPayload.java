package com.inventorystealer.network;

import com.inventorystealer.InventoryStealer;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → victim. {@code active=true} starts/refreshes the "you are being looted" death screen;
 * {@code active=false} ends it. Idempotent: the client just mirrors the latest state.
 */
public record DeathCountdownPayload(boolean active, int remainingTicks, int totalTicks, String killerName) implements CustomPacketPayload {
    public static final Type<DeathCountdownPayload> TYPE = new Type<>(InventoryStealer.id("death_countdown"));

    public static final StreamCodec<ByteBuf, DeathCountdownPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, DeathCountdownPayload::active,
            ByteBufCodecs.VAR_INT, DeathCountdownPayload::remainingTicks,
            ByteBufCodecs.VAR_INT, DeathCountdownPayload::totalTicks,
            ByteBufCodecs.stringUtf8(64), DeathCountdownPayload::killerName,
            DeathCountdownPayload::new);

    public static DeathCountdownPayload start(int remainingTicks, int totalTicks, String killerName) {
        return new DeathCountdownPayload(true, remainingTicks, totalTicks, killerName);
    }

    public static DeathCountdownPayload end() {
        return new DeathCountdownPayload(false, 0, 0, "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
