# Inventory Stealer

A NeoForge 1.21.1 mod for PvP servers. When a player is killed by another player:

1. A corpse of the victim (their own skin, lying on the ground) appears where they died.
2. **Any** nearby living player — not just the killer — can right-click it and loot it through a tabbed GUI:
   the victim's inventory, hotbar, armor, offhand, and one tab per Traveler's Backpack they carried.
3. After the loot window (default **5 seconds**) the corpse disappears, every open loot menu is closed, and the
   victim respawns automatically (bed / respawn anchor / world spawn as usual) with everything that was not taken.

Deaths that were not caused by a player (mobs, fall, lava, void, /kill, …) are completely unaffected.

| | |
|---|---|
| Mod ID | `inventorystealer` |
| Minecraft / Loader | 1.21.1 / NeoForge 21.1 (built against 21.1.235) |
| Java | 21 |
| Sides | **Required on the server and on every client** (it adds an entity, a menu and a network packet) |
| Optional integrations | Traveler's Backpack 10.1.35+, Curios 9.x (back-slot backpacks) |

## Installation

1. Build or take `build/libs/inventorystealer-neoforge-1.21.1-1.0.0.jar`.
2. Copy it into the `mods/` folder of the **server** and of **every client**.
3. Start the server once; the config file appears at `config/inventorystealer-server.toml` (NeoForge 21.1 keeps
   SERVER configs in the global `config/` folder, next to `travelersbackpack-server.toml`).

> **Dedicated servers:** keep client-only mods (rendering, minimaps' client parts, schematic tools, …) out of the
> server's `mods/` folder. Some of them crash or hang a dedicated server on their own, independent of this mod.

No other mod needs to be changed. Other backpack mods are not integrated; their backpacks are looted as ordinary items.

## Configuration

`config/inventorystealer-server.toml` on the **server** (a NeoForge SERVER config — clients do not need to edit it).
This is the file exactly as the mod generates it:

```toml
[inventoryStealer]
	#Master switch. When false, every death behaves exactly like vanilla/the modpack.
	enabled = true
	#How long (in seconds) a PvP corpse can be looted. The victim respawns automatically when it ends.
	#The same deadline is used for corpse removal, menu closure and the respawn. 1 second = 20 server ticks.
	# Default: 5
	# Range: 1 ~ 300
	lootDurationSeconds = 5
	#Maximum distance (blocks) between a looter and the corpse while the loot menu is open.
	# Default: 6.0
	# Range: 2.0 ~ 16.0
	maxLootDistance = 6.0

[attribution]
	#If true, a kill by a tamed animal (wolf, cat, ...) counts as a PvP kill by its owner.
	petKillsCountAsPvp = false
	#If true, deaths without a direct attacker (fall, lava, void, ...) shortly after being hit by a player
	#count as PvP kills by that player (uses Minecraft's kill-credit tracking).
	indirectKillsCountAsPvp = false

[safety]
	#Save player data and Inventory Stealer data when a loot session starts and ends.
	#Keeps inventories consistent if the server crashes during or right after a loot session.
	checkpointSaves = true
```

* **Changing the 5-second window:** edit `lootDurationSeconds` (whole seconds, 20 ticks each). NeoForge reloads
  the file automatically when it is saved — no restart needed. The new value applies to deaths that happen after
  the change; running loot windows keep the deadline they started with. Values outside the range are
  corrected by NeoForge when the file is loaded.
* The server's values are authoritative; clients receive the remaining time from the server.

## What counts as a PvP kill

The killer is `DamageSource#getEntity()` — the *responsible* entity — so melee hits, arrows, tridents,
modded gun bullets, player-ignited TNT and other projectiles owned by a player all count. Not counted:
mobs, environment, `/kill`, self-damage, and fake players (e.g. Create deployers). Pet kills and "knocked off a
cliff" kills are configurable (off by default). The killer gets no special looting rights.

## How items are kept safe

* **One owner at a time.** At death, Inventory Stealer *moves* the victim's items (inventory, armor, offhand,
  cursor, 2×2 crafting grid, worn backpack in the Curios `back` slot or TB's attachment) into a server-side loot
  session. Nothing is left on the player, so vanilla drops, `keepInventory` cloning, Curios and Traveler's Backpack
  death handling cannot duplicate anything.
* **Shared containers.** Every viewer's menu wraps the same session containers, so a stack can only be taken
  once and all viewers see changes immediately. Corpses are take-only; every click is validated on the server
  (session active, viewer alive, in range, same dimension, tab visible, backpack still present).
* **All backpack tiers.** Each backpack tab shows that backpack's storage in pages of 9×6 slots, with its tool slots
  in the left column. With Traveler's Backpack's default tier sizes: leather 27 + 2 tools (1 page), iron 45 + 3 (1 page), gold 63 + 4,
  diamond 81 + 5 and netherite 99 + 6 (2 pages each). Sizes are read from the backpack itself, so custom TB tier sizes
  work too. Upgrades and fluid tanks are not individually lootable; they stay inside the backpack.
* **Backpacks.** Traveler's Backpack keeps its contents in the backpack item's own data components. A backpack tab
  edits those components on that exact backpack ItemStack. Taking the whole backpack moves it with its remaining
  contents and immediately disables its tab for everyone else.
* **Exactly-once return.** When the window ends the session is marked finished first (rejecting further clicks),
  all menus are closed (cursor items go back to the looter), the corpse is removed, and the remaining items are
  handed out once — into the respawned victim's original slots where possible.
* **Disconnects and crashes.** If the victim is offline at the end, their items are stored in
  `<world>/data/inventorystealer_sessions.dat` and returned when they next respawn/log in. Sessions running during
  a stop or crash are never resumed as lootable; their items are returned the same way. Checkpoint saves at session
  start/end keep player files and this data consistent.
* **No early respawn.** The respawn button is replaced by a countdown, and the server ignores respawn requests
  while the window runs (one small mixin, because NeoForge has no cancellable pre-respawn event).

## Building and testing

```bash
./gradlew build                 # -> build/libs/inventorystealer-neoforge-1.21.1-1.0.0.jar
./gradlew runGameTestServer     # automated in-game tests (TB 10.1.35 + Curios 9.5.1 loaded)
./gradlew runGameTestModpack -PmodpackTestDir=/path/to/dir   # same tests with a modpack's mods in <dir>/mods
./gradlew runClientSmokeTest    # real client run with screenshots (needs run/client-smoke/saves/smoketest)
./gradlew runClient / runServer # normal development runs
```

Tests live in the separate `src/gametest` source set and are not included in the release jar.

The automated tests cover PvP vs. non-PvP attribution, concurrent looting, every Traveler's Backpack tier,
whole-backpack theft, disconnects, `keepInventory` on/off, cancelled deaths and save/reload recovery.

## Project layout

```
src/main/java/com/inventorystealer/
├── InventoryStealer.java          mod entry point (registries, config, networking)
├── config/                        SERVER config
├── registry/                      entity type + menu type
├── event/ServerEvents.java        death (LOWEST), server tick, login/logout, respawn, server start/stop
├── session/                       authoritative loot sessions
│   ├── SessionManager.java        lifecycle: capture → tick → finalize → respawn/restore
│   ├── DeathLootSession.java      captured items, backpack tabs, access rules
│   ├── VictimContainer.java       take-only container over the captured items
│   ├── BackpackStorageContainer   one backpack's storage/tools, bound to that ItemStack instance
│   ├── PendingRestoration.java    items owed to a victim (also the on-disk form)
│   ├── SessionSavedData.java      world-save persistence
│   └── PvpAttribution.java        who counts as the killer
├── integration/                   Traveler's Backpack + Curios (only loaded when installed)
├── corpse/CorpseEntity.java       inert, indestructible corpse (a view of the session, never saved)
├── menu/                          CorpseMenu (tabs/pages), CorpseSlot, open-data
├── network/                       death countdown payload
├── mixin/                         respawn-request guard
└── client/                        corpse renderer, loot screen, countdown death screen
```

## License

[MIT](LICENSE) © 2026 muk7tm.

Inventory Stealer is an independent mod. Traveler's Backpack (LGPL-3.0) and Curios API are optional runtime
integrations; they are compiled against as `compileOnly` dependencies and are **not** bundled in the jar.
