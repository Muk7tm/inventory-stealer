package com.inventorystealer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * SERVER config: {@code config/inventorystealer-server.toml} (NeoForge 21.1 keeps server configs in the global config folder).
 * NeoForge reloads the file when it changes on disk; new values apply to deaths that happen afterwards.
 */
public final class InventoryStealerConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue LOOT_DURATION_SECONDS;
    public static final ModConfigSpec.DoubleValue MAX_LOOT_DISTANCE;
    public static final ModConfigSpec.BooleanValue PET_KILLS_COUNT;
    public static final ModConfigSpec.BooleanValue INDIRECT_KILLS_COUNT;
    public static final ModConfigSpec.BooleanValue CHECKPOINT_SAVES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("inventoryStealer");
        ENABLED = builder
                .comment("Master switch. When false, every death behaves exactly like vanilla/the modpack.")
                .define("enabled", true);
        LOOT_DURATION_SECONDS = builder
                .comment("How long (in seconds) a PvP corpse can be looted. The victim respawns automatically when it ends.",
                        "The same deadline is used for corpse removal, menu closure and the respawn. 1 second = 20 server ticks.")
                .defineInRange("lootDurationSeconds", 5, 1, 300);
        MAX_LOOT_DISTANCE = builder
                .comment("Maximum distance (blocks) between a looter and the corpse while the loot menu is open.")
                .defineInRange("maxLootDistance", 6.0D, 2.0D, 16.0D);
        builder.pop();

        builder.push("attribution");
        PET_KILLS_COUNT = builder
                .comment("If true, a kill by a tamed animal (wolf, cat, ...) counts as a PvP kill by its owner.")
                .define("petKillsCountAsPvp", false);
        INDIRECT_KILLS_COUNT = builder
                .comment("If true, deaths without a direct attacker (fall, lava, void, ...) shortly after being hit by a player",
                        "count as PvP kills by that player (uses Minecraft's kill-credit tracking).")
                .define("indirectKillsCountAsPvp", false);
        builder.pop();

        builder.push("safety");
        CHECKPOINT_SAVES = builder
                .comment("Save player data and Inventory Stealer data when a loot session starts and ends.",
                        "Keeps inventories consistent if the server crashes during or right after a loot session.")
                .define("checkpointSaves", true);
        builder.pop();

        SPEC = builder.build();
    }

    private InventoryStealerConfig() {
    }

    public static boolean isEnabled() {
        return SPEC.isLoaded() ? ENABLED.get() : ENABLED.getDefault();
    }

    public static int lootDurationTicks() {
        int seconds = SPEC.isLoaded() ? LOOT_DURATION_SECONDS.get() : LOOT_DURATION_SECONDS.getDefault();
        return seconds * 20;
    }

    public static double maxLootDistance() {
        return SPEC.isLoaded() ? MAX_LOOT_DISTANCE.get() : MAX_LOOT_DISTANCE.getDefault();
    }

    public static boolean petKillsCount() {
        return SPEC.isLoaded() ? PET_KILLS_COUNT.get() : PET_KILLS_COUNT.getDefault();
    }

    public static boolean indirectKillsCount() {
        return SPEC.isLoaded() ? INDIRECT_KILLS_COUNT.get() : INDIRECT_KILLS_COUNT.getDefault();
    }

    public static boolean checkpointSaves() {
        return SPEC.isLoaded() ? CHECKPOINT_SAVES.get() : CHECKPOINT_SAVES.getDefault();
    }
}
