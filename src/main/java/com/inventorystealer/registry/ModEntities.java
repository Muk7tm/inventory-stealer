package com.inventorystealer.registry;

import com.inventorystealer.InventoryStealer;
import com.inventorystealer.corpse.CorpseEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, InventoryStealer.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<CorpseEntity>> CORPSE = ENTITY_TYPES.register("corpse",
            () -> EntityType.Builder.<CorpseEntity>of(CorpseEntity::new, MobCategory.MISC)
                    .sized(1.4F, 0.45F)
                    .fireImmune()
                    .noSummon()
                    .clientTrackingRange(8)
                    .updateInterval(10)
                    .build(InventoryStealer.id("corpse").toString()));

    private ModEntities() {
    }
}
