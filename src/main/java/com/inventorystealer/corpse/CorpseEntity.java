package com.inventorystealer.corpse;

import com.inventorystealer.registry.ModEntities;
import com.inventorystealer.session.DeathLootSession;
import com.inventorystealer.session.SessionManager;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;

/**
 * A fallen-player body that opens the loot menu of its {@link DeathLootSession}.
 *
 * <p>The corpse is only a <em>view</em>: it owns no items and is never saved to disk. If it is unloaded, killed by
 * a command or otherwise removed, the session (and its items) is unaffected and the corpse is recreated.
 * It cannot be damaged, pushed, ridden or picked up.
 */
public class CorpseEntity extends Entity {
    private static final EntityDataAccessor<Optional<UUID>> VICTIM_ID =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<String> VICTIM_NAME =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> SECONDS_LEFT =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.INT);

    @Nullable
    private UUID sessionId;

    public CorpseEntity(EntityType<? extends CorpseEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvulnerable(true);
    }

    public static CorpseEntity create(ServerLevel level, DeathLootSession session) {
        CorpseEntity corpse = new CorpseEntity(ModEntities.CORPSE.get(), level);
        corpse.sessionId = session.id();
        corpse.moveTo(session.position().x, session.position().y, session.position().z, level.random.nextFloat() * 360.0F, 0.0F);
        corpse.entityData.set(VICTIM_ID, Optional.of(session.victimId()));
        corpse.entityData.set(VICTIM_NAME, session.victimName());
        corpse.entityData.set(SECONDS_LEFT, secondsLeft(session));
        return corpse;
    }

    private static int secondsLeft(DeathLootSession session) {
        return (session.remainingTicks(SessionManager.now()) + 19) / 20;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(VICTIM_ID, Optional.empty());
        builder.define(VICTIM_NAME, "");
        builder.define(SECONDS_LEFT, 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        DeathLootSession session = SessionManager.get(sessionId);
        if (session == null || !session.isActive() || !getUUID().equals(session.corpseId())) {
            discard();
            return;
        }
        entityData.set(SECONDS_LEFT, secondsLeft(session));
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        return SessionManager.tryOpen(serverPlayer, this) ? InteractionResult.CONSUME : InteractionResult.FAIL;
    }

    @Nullable
    public UUID sessionId() {
        return sessionId;
    }

    public Optional<UUID> victimId() {
        return entityData.get(VICTIM_ID);
    }

    public String victimName() {
        return entityData.get(VICTIM_NAME);
    }

    public int secondsLeft() {
        return entityData.get(SECONDS_LEFT);
    }

    // ---------------------------------------------------------------- an inert, indestructible view

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        return true;
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return true;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    protected boolean canRide(Entity vehicle) {
        return false;
    }

    @Override
    public boolean canChangeDimensions(Level from, Level to) {
        return false;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    public void push(Entity entity) {
        // Never moved by entity collisions.
    }
}
