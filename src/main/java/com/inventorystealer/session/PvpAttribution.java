package com.inventorystealer.session;

import com.inventorystealer.config.InventoryStealerConfig;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Decides whether a death was caused by another real player.
 *
 * <ul>
 *   <li>{@link DamageSource#getEntity()} is the <em>responsible</em> entity: the shooter of an arrow/trident/bullet,
 *       the igniter of TNT, the attacker of a melee hit. It is used instead of the direct entity so projectiles count.</li>
 *   <li>Tamed pets only count when {@code petKillsCountAsPvp} is enabled (credited to the owner).</li>
 *   <li>Deaths without a responsible entity (fall, lava, void, drowning, /kill) only count when
 *       {@code indirectKillsCountAsPvp} is enabled and Minecraft's kill-credit tracking names a player.</li>
 *   <li>Self-inflicted damage and {@link FakePlayer}s (e.g. Create deployers) never count.</li>
 * </ul>
 */
public final class PvpAttribution {
    private PvpAttribution() {
    }

    @Nullable
    public static ServerPlayer findKiller(ServerPlayer victim, DamageSource source) {
        Entity responsible = source.getEntity();
        if (responsible != null && !(responsible instanceof Player)
                && responsible instanceof OwnableEntity ownable && InventoryStealerConfig.petKillsCount()) {
            responsible = ownable.getOwner();
        }
        if (responsible == null && InventoryStealerConfig.indirectKillsCount()) {
            responsible = victim.getKillCredit();
        }
        if (responsible instanceof ServerPlayer killer && !(killer instanceof FakePlayer)
                && !killer.getUUID().equals(victim.getUUID())) {
            return killer;
        }
        return null;
    }
}
