package com.inventorystealer;

import com.inventorystealer.config.InventoryStealerConfig;
import com.inventorystealer.network.ModNetworking;
import com.inventorystealer.registry.ModEntities;
import com.inventorystealer.registry.ModMenus;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(InventoryStealer.MOD_ID)
public final class InventoryStealer {
    public static final String MOD_ID = "inventorystealer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public InventoryStealer(IEventBus modBus, ModContainer container) {
        ModEntities.ENTITY_TYPES.register(modBus);
        ModMenus.MENU_TYPES.register(modBus);
        modBus.addListener(ModNetworking::register);
        container.registerConfig(ModConfig.Type.SERVER, InventoryStealerConfig.SPEC);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
