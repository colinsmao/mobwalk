# Settings (technical)

**This is the technical reference** for agents and maintainers working on the
config stack (MaLiLib registration, JSON, option types, how to add options).
**Player-facing help lives in [`../README.md`](../README.md)** (what the mod does,
install, controls) and in the per-option `comment.*` tooltips; read this file when
you need implementation detail.

General project facts live in `[project.md](project.md)`; rules in
`[../AGENTS.md](../AGENTS.md)`. How overlays are *drawn* lives in
`[rendering.md](rendering.md)`.

> Version facts below target Minecraft `26.3` and MaLiLib `0.30.2`. Verify
> class names against the resolved jars when upgrading.



## Stack

```
Pause → Mods (ModMenu) → Configure → GuiConfigs (MaLiLib GuiConfigsBase)
  → Configs (IConfigHandler) → config/mobwalk.json on screen close
  → also Configs.saveToDisk() on ClientPlayConnectionEvents.DISCONNECT
```

- **MaLiLib** `0.30.2` (`fi.dy.masa.malilib.`*) is the settings UI and JSON
persistence stack. Loom uses plain `implementation` (non-remapping); ModMenu is
`compileOnly` + `localRuntime` so Configure appears in `runClient` without
bundling ModMenu into the jar.
- `MobWalkClient` registers `InitHandler` via MaLiLib’s `InitializationHandler`.
`InitHandler` registers the `IConfigHandler` with `ConfigManager` and the
config-screen factory with `Registry.CONFIG_SCREEN` (`ModInfo` → `GuiConfigs`).
- ModMenu’s Configure button is wired by `MobWalkModMenuIntegration` (Fabric
entrypoint `modmenu`).
- Widgets write option values immediately; JSON is written on config-screen close
through `IConfigHandler.save()` → `config/mobwalk.json` under the game config
directory, and again on play **disconnect** (Save and Quit to Title) via
`Configs.saveToDisk()` so in-game flood gestures (shift+scroll radius, profile
cycle) land on disk without opening Configure. Load runs at init via
`IConfigHandler.load()`.

Key files: `InitHandler.java`, `Configs.java`, `GuiConfigs.java`,
`MobWalkModMenuIntegration.java`, lang `assets/mobwalk/lang/en_us.json`.

## Live Generic options

Category key prefix: `mobwalk.config.generic` (`.apply(GENERIC_KEY)`). JSON
category name: `"Generic"`. Screen title lang: `mobwalk.gui.title.configs`.

| Option | Class | Default | Behavior |
| --- | --- | --- | --- |
| `showSurfaces` | `ConfigOptionList` | `While Holding Wand` (`Configs.ShowSurfaces`) | Gates `CollisionSurfaceOverlay.isVisible()` each frame: **Never**, **While Holding Wand** (draw only while the wand is held in either hand), **Always** (draw whenever a selection exists). Snapshot stays; mode alone does not re-flood. Cycle labels from lang `showSurfaces.*`; tooltip lists all three modes. |
| `wandItem` | `ConfigString` | `minecraft:stick` | Item used as the wand (select / clear / crouch gestures). Current resolved `Item` is refreshed on change/load via `WandItem` against `BuiltInRegistries.ITEM`; malformed or unknown ids fall back to stick while the typed string stays in the field. Row uses `ItemIdConfigOption` (live invalid hover tooltip). |
| `mobProfile` | `ConfigOptionList` | `Player` (`RosterProfileOption`) | Cycles **enabled** roster ids (builtins then customs, table order). Value-change callback clamps to an enabled id via `resolveActiveId`, then `reselectWithMobProfile` when a selection is active. |
| `builtinProfiles` | `ConfigTable` (UI only) | nine builtin seed rows | Same instance as `Configs.Profiles.BUILTIN_PROFILES`; shown on General. Opens `BuiltinProfilesTableEdit` (button `Edit Built-in Profiles`). Persisted slim under `"Profiles"` — see Profiles. |
| `customProfiles` | `ConfigTable` | empty | Same instance as `Configs.Profiles.CUSTOM_PROFILES`; shown on General. Opens `CustomProfilesTableEdit` (button `Edit Custom Profiles`). Full table JSON under `"Profiles"` — see Profiles. |
| `floodRadius` | `ConfigInteger` | `20` (min `0`, max `30`, slider) | Flood steps from the seed; world reach scales with mob width. Slider and shift+scroll both write this option (`Configs.setFloodRadius` / MaLiLib set). `setValueChangeCallback` → `CollisionSurfaceOverlay.reselectWithMobProfile` (re-floods an active selection). Persisted on config-screen close and on play disconnect. |
| `floodBudgetMs` | `ConfigInteger` | `10` (min `0`, max `50`, slider) | Wall time a flood may spend per frame (`CollisionSurfaceOverlay.advanceFlood` → `SurfaceSelection.advance`). Sits beside `floodRadius`: that sets how much work a flood is, this sets how fast it is paid for. A flood in progress adds this to every frame, so it costs an even frame-rate dip and lands after `total / (budget * fps)` seconds. `0` means unlimited, which finishes the flood on the frame after the click — the first frame that could have drawn it, so the selection still appears in one step. Read live each frame, so a change applies to the flood already running. |
| `showFloodProgress` | `ConfigBoolean` | `true` | Draws a ring around the crosshair while a flood is calculating (`FloodProgressOverlay`). `isVisible()` reads it live, so turning it off hides the ring on the next HUD frame without stopping the flood. |
| `autoUpdate` | `ConfigOptionList` | `Disabled` (`Configs.AutoUpdate`) | When the flood re-runs on a timer: **Disabled**, **Anchor** (re-floods the last clicked block), **Follow Player** (self-starts from `resolveDownward` of `player.blockPosition()`). Runs only when surfaces would paint (`showSurfaces` + wand + enabled profile). Idle ticks count after each flood finishes. Cycle labels from lang `autoUpdate.*`. |
| `autoUpdateInterval` | `ConfigDouble` | `0.5` (min `0.5`, max `10.0`, slider) | Seconds to wait after a fire before the next one, converted to ticks as `max(1, round(seconds * 20))`. The slider snaps in 0.5 s steps; typing the field keeps any precision in range. Read live each tick. |
| `swimmableFluids` | `ConfigBoolean` | `true` | When on, vanilla water and lava (`FluidTags`) emit non-occluding fluid surfaces in the flood (see [geometry.md](geometry.md) → Fluid surfaces). Off restores pre-fluid hole beams on pools. `setValueChangeCallback` → `CollisionSurfaceOverlay.reselectWithMobProfile`. Persisted on config-screen close and on play disconnect. |
| `fluidEscapeHeight` | `ConfigDouble` | `0.375` (min `0`, max `2`, slider) | Rim height above the fluid **block** top that a fluid→non-fluid climb may clear (`ClimbRule`; see [geometry.md](geometry.md) Escape cap). Default matches mob pathing (`6/16`); `0.875` reaches soul sand. At or above the active profile's vertical reach, leaving fluid matches jumping on land. `setValueChangeCallback` → `CollisionSurfaceOverlay.reselectWithMobProfile`. Persisted on config-screen close and on play disconnect. |

Helpers: `Configs.showSurfaces()`, `Configs.autoUpdate()`, `Configs.autoUpdateIntervalSeconds()`, `Configs.wandItem()`, `Configs.mobProfile()`, `Configs.cycleMobProfile()`, `Configs.floodRadius()`,
`Configs.setFloodRadius()`, `Configs.floodBudgetMs()`, `Configs.showFloodProgress()`, `Configs.swimmableFluids()`, `Configs.fluidEscapeHeight()`, `Configs.saveToDisk()`, `Configs.roster()`, `Configs.hasEnabledProfile()`.

Lang: player-facing `comment.*` tooltip for every option. Row labels use the option
id when `name.*` is omitted (`Configs.refreshDisplayNames`); an optional `name.*`
entry still overrides. No `prettyName.*` — MaLiLib falls back to `splitCamelCase`
for toggle messages. Profiles button labels:
`mobwalk.config.profiles.button.builtinProfiles` /
`mobwalk.config.profiles.button.customProfiles`.

`config_version` (`3`) is written beside the category objects in `mobwalk.json`.

## Profiles (General popups)

JSON category `"Profiles"` holds slim `builtinProfiles` (ordered `{"id","enabled"}`;
geometry is code-owned) and the full `customProfiles` ConfigTable dump. Both General
row buttons open `ProfilesTableEdit` dialogs (`BuiltinProfilesTableEdit` /
`CustomProfilesTableEdit`) with always-disabled per-row RESET. List-level
Cancel|Confirm RESET on General is unchanged. `Configs.syncRosterFromTables`
(via table value-change callbacks) runs `ProfileRoster.sanitize` on both tables,
then clamps / soft-disables / reselects.

### Built-in Profiles

On/Off + locked Name / Width / Height / Vertical Reach for Player, Ravager,
Warden, Zombie/Witch, Skeleton, Cow, Sheep, Pig — rebuilt from
`ProfileRoster.BUILTIN_SEEDS` plus slim JSON on load.

- **Roster order** follows current table row order (reorder is real for cycle /
fallback). Sanitize keeps known rows in table order and appends any missing seeds.
- **Default enables:** Player / Ravager / Warden / Zombie/Witch On; Skeleton / Cow /
Sheep / Pig Off. Geometry is code-owned (seed sizes); only enables and order are
player-editable.
- **Hand-edit recovery:** unknown ids dropped; missing seed ids re-appended with
default enables on load/sync.
- **Debug Point:** Point is seed index 0, and `ProfileRoster.firstSeed` starts a seed
walk there while Debug `showPointProfile` is on (at index 1 otherwise).

### Custom Profiles

On/Off + editable Name (STRING) / Width / Height / Vertical Reach (DOUBLE).
`allowNewEntry=true`; row count is uncapped. ADD clones the **clicked** row
(enabled + name + sizes) and inserts the copy **below** it; with no source row
(empty table / trailing dummy) it falls back to the active profile (`Configs.mobProfile()`,
or Player when soft-disabled) On. REMOVE deletes a row. List-level Confirm RESET
clears the table to empty. Width is clamped to `[0, 4]` (Ghast-scale flood guard);
height/reach only repair non-finite → Player sizes and negatives → `0`. Blank names
are not kept: sanitize strips trailing spaces, restores the previous non-empty name
at that index (else `"Custom"`), then rewrites **new or renamed** colliding customs
to `Name (1)`, `Name (2)`, … (builtins count, including disabled). Existing names
that still appear are left unchanged. An open `CustomProfilesTableEdit` rebuilds so
repaired fields show without closing. Custom ids are `custom0`, `custom1`, … in
table order.

### Shared roster behavior

- **Cycle order:** enabled builtins (table order), then enabled customs.
New/renamed colliding names are uniquified into the stored Name (ids `custom0`,
`custom1`, …); existing names are not reindexed. `Configs.profileDisplayLabel` /
settings button use that stored name. Cycle still skips disabled.
- **Soft-disable:** every profile Off → `hasEnabledProfile()` false;
overlay select floods stay off; wand air- or block-click pings HUD
`no profiles active`; `showSurfaces` is unchanged. Shift+scroll radius still works.
- **ConfigTable RESET enable:** MaLiLib `ConfigTable.isModified()` compares defaults
to a stale `lastTable` after popup edits. Use `Configs.configTableIsModified(table)`
(live rows vs defaults) for RESET enable on any ConfigTable. Both
`Edit Built-in Profiles` and `Edit Custom Profiles` rows use Cancel (same spot) +
Confirm (to the right) via `ConfirmResetConfigOption` (factory selects that subclass
only for those two tables; other rows use stock `WidgetConfigOption`; `wandItem` uses
`ItemIdConfigOption`); Confirm calls
`resetToDefault()` then `Configs.syncAfterProfilesTableReset()`. Builtin RESET
restores seed enables/order; custom RESET clears the table to empty.

Key types: `ProfileRoster`, `RosterProfileOption`, `Configs.Profiles`.

## Screen layout

`GuiConfigs` implements `IConfigGuiAllTab` with filter buttons (not inline LABELs):

- **All** — General → Appearance → Debug (default tab)
- **General** — `Configs.Generic.OPTIONS` (JSON category stays `"Generic"`; includes
`Edit Built-in Profiles` and `Edit Custom Profiles`)
- **Appearance** — `Configs.Appearance.OPTIONS`
- **Debug** — `Configs.Debug.OPTIONS`

Tab button lang: `mobwalk.gui.button.config_gui.general` / `.appearance` / `.debug`
(All uses MaLiLib’s `IConfigGuiAllTab` key). ConfigTable RESET Cancel/Confirm:
`mobwalk.gui.button.reset_cancel` / `.reset_confirm`.

## Live Appearance options

Category key prefix: `mobwalk.config.appearance` (`.apply(APPEARANCE_KEY)`). JSON
category name: `"Appearance"`.

| Option | Class | Default | Behavior |
| --- | --- | --- | --- |
| `walkableColor` | `ConfigColor` | `#7F55AA55` (green, ~50% alpha) | Default RGB+alpha for tops/skirts when no higher precedence applies (see [rendering.md](rendering.md) fill precedence). Read live in `SurfaceEmitter` / `Palette.FillColors`. |
| `showBeamsThroughWalls` | `ConfigBoolean` | `true` | When on: beams go to the depth-off beam layer (visible through terrain). When off: beams go to the depth-tested skirt layer (occluded by blocks). Shared by all beam types (`HOLE`, water, lava, soul sand, magma). |
| `showHoleBeams` | `ConfigBoolean` | `true` | When on: `SurfaceEmitter` draws hole (`HazardClass.HOLE`) beams at trap rims via `emitBeam`. When off: hole beams are skipped; hazard perimeter beams still follow their own show flags. |
| `holeBeamColor` | `ConfigColor` | `#7FF2261A` (red, ~50% alpha) | RGB + alpha for hole beams (uniform along the beam). |
| `showWaterHazard` | `ConfigBoolean` | `true` | When on: water tops/skirts and water perimeter beams use `waterHazardColor`. When off: water fill uses `walkableColor` and water beams are skipped (`HazardClass` unchanged). |
| `waterHazardColor` | `ConfigColor` | `#7F3A9AE0` (blue, ~50% alpha) | RGB+alpha for water hazard fill and water perimeter beams when `showWaterHazard` is on. |
| `showLavaHazard` | `ConfigBoolean` | `true` | When on: lava tops/skirts and lava perimeter beams use `lavaHazardColor`. When off: lava fill uses `walkableColor` and lava beams are skipped. |
| `lavaHazardColor` | `ConfigColor` | `#7FE07020` (orange, ~50% alpha) | RGB+alpha for lava hazard fill and lava perimeter beams when `showLavaHazard` is on. |
| `showSoulSandHazard` | `ConfigBoolean` | `true` | When on: soul-sand tops/skirts and soul-sand perimeter beams use `soulSandHazardColor`. When off: fill uses `walkableColor` and soul-sand beams are skipped. |
| `soulSandHazardColor` | `ConfigColor` | `#7F8B5A2B` (brown, ~50% alpha) | RGB+alpha for soul-sand hazard fill and perimeter beams when `showSoulSandHazard` is on. |
| `showMagmaHazard` | `ConfigBoolean` | `true` | When on: magma tops/skirts and magma perimeter beams use `magmaHazardColor`. When off: fill uses `walkableColor` and magma beams are skipped. |
| `magmaHazardColor` | `ConfigColor` | `#7FE0C028` (orange-gold, ~50% alpha) | RGB+alpha for magma hazard fill and perimeter beams when `showMagmaHazard` is on. |
| `downSkirtHeight` | `ConfigDouble` | `2.0` (min `0`, max `4`, slider) | Draw depth of downward drop skirts. `0` skips draw. Read live in `SurfaceEmitter`. |
| `upwardSkirtHeight` | `ConfigDouble` | `0.25` (min `0`, max `4`, slider) | Draw height of upward wall-edge markers, clamped to available wall. `0` skips draw. Read live in `SurfaceEmitter`. |
| `drawOnVisibleFace` | `ConfigBoolean` | `true` | When on: standable tops of taller-than-collision blocks (soul sand, mud) draw on the visible block face; when off, at the collision height. **Compute-side** — passed into `select` as `computeVisualTop`, so a value-change callback re-floods (the one Appearance option that touches compute). See `[geometry.md](geometry.md)` / `[rendering.md](rendering.md)`. |

Helpers: `Configs.walkableColor()`, `Configs.waterHazardColor()`, `Configs.lavaHazardColor()`,
`Configs.soulSandHazardColor()`, `Configs.magmaHazardColor()`,
`Configs.holeBeamColor()` → `Color4f`; `Configs.showWaterHazard()`, `Configs.showLavaHazard()`,
`Configs.showSoulSandHazard()`, `Configs.showMagmaHazard()`,
`Configs.showBeamsThroughWalls()`, `Configs.showHoleBeams()`,
`Configs.downSkirtHeight()`, `Configs.upwardSkirtHeight()`,
`Configs.drawOnVisibleFace()`.

## Live Debug options

Category key prefix: `mobwalk.config.debug` (`.apply(DEBUG_KEY)`). JSON category
name: `"Debug"`.

| Option | Class | Default | Behavior |
| --- | --- | --- | --- |
| `crouchSeeThroughWalls` | `ConfigBoolean` | `true` | When on: crouching routes tops + rect borders into the depth-off layer. When off: tops stay depth-tested and crouch borders stay off. Skirts stay depth-tested either way. |
| `crouchScrollRadius` | `ConfigBoolean` | `true` | When on: wand + crouch + scroll adjusts flood radius (`wantsRadiusScroll` → `Configs.setFloodRadius`). When off: that gesture is inactive — scroll never changes the radius. |
| `crouchCycleProfile` | `ConfigBoolean` | `true` | When on: wand + crouch + right-click air advances `Configs.MOB_PROFILE` and pings the HUD. When off: air-click still clears the selection; the profile stays put. |
| `shadeByDepth` | `ConfigBoolean` | `false` | When on: tops/skirts use the cyclic BFS-depth hue (`Palette` / `depthColor`). When off: fill precedence continues to hazard color / `walkableColor`. Cutoff ring (when shown) still greys via frontier greying. |
| `showCutoffRing` | `ConfigBoolean` | `true` | When on: draw the merge frontier band fully grey (`Palette.colorAtDepth` when `frontier`). When off: frontier tops are not drawn. |
| `showPointProfile` | `ConfigBoolean` | `false` | When on: the Point builtin (zero width/height) is listed in Edit Built-in Profiles, inserted Off as the first row, and can be enabled like any builtin. When off: Point is dropped from the roster and table, and an active Point falls back to the next enabled profile. Has a value-change callback (`refreshBuiltinTableDefaults` + `onProfilesChanged`) so the table, roster, cycle, current flood, and open settings screen update live. |

Helpers: `Configs.crouchScrollRadius()`, `Configs.crouchSeeThroughWalls()`,
`Configs.crouchCycleProfile()`, `Configs.shadeByDepth()`,
`Configs.showCutoffRing()` — read live each use (no
value-change callbacks).
`Configs.showPointProfile()` feeds `ProfileRoster.firstSeed` / `sanitize` and the
builtin table defaults.

## Lang convention (`name.*` / `comment.*`)

- `name.<id>` — optional override for the row label. If missing,
  `Configs.refreshDisplayNames()` (on config screen open) sets the display name to
  the option id (same as the last segment of the apply() key), so the GUI does not
  show a raw translation key.
- `comment.<id>` — tooltip. Required for every option (missing → raw key).
  Player-facing only: help the player decide or use the option. Skip implementation
  jargon. Prefer short copy; extra length only when useful. A later UX pass may
  refine tone. Existing `comment.*` / `name.*` strings in `en_us.json` are the
  desired copy — agents add new keys and leave existing phrasing alone unless
  asked to change it or the option’s meaning changed.
- Bool `comment.*` lead with `If enabled,`. Options are either **mod actions**
  (what the mod does/looks like) or **player actions** (how the player interacts).
  Prefer active third person when the feature is the actor (`draws beams`,
  `colors surfaces`, `shows …`). Prefer a full indicative clause when the player
  is the actor (`scrolling changes …`, `right-clicking air cycles …`). Do not name
  “the mod” / “the overlay” as subject. **Debug** is the odd tab for debug aids;
  it is not required to be a pure mod-action or player-action group.
- Do not ship `prettyName.*` unless a toggle message needs custom phrasing.

Messages outside the config screen are lang entries too: the HUD readout under
`mobwalk.hud.*` (`flood_radius`, `profile`, `no_profiles_active`, formatted with
`%s`) and client-command chat under `mobwalk.command.*`. `RadiusIndicatorOverlay`
resolves its own text through `StringUtils.translate`; `/mobwalk dump` sends
`Component.translatable`. `LangKeysTest` pins that those keys exist and keep their
placeholders. Profile names stay stored strings — built-in names double as Name
cells in the Profiles table and custom names are player-typed.

## Adding an option

Match the existing Generic / Debug pattern in `Configs.java`:

1. Declare a `public static final` config option on the category nested class
  (`Configs.Generic`, `Configs.Appearance`, or `Configs.Debug`).
2. Add it to that class’s `OPTIONS` list.
3. Call `.apply(…_KEY)` so translated name/comment resolve under
  `mobwalk.config.generic.*`, `…appearance.*`, or `…debug.*`.
4. Add a player-facing `comment.*` in `en_us.json` (required). Optional `name.*`
  only when the row label should differ from the option id.
5. Wire live apply with `setValueChangeCallback` when changing the value should
  update an overlay immediately (see `FLOOD_RADIUS`); otherwise read the
  option live from a `Configs.*()` helper.
  (`Configs` ↔ `CollisionSurfaceOverlay`/`SurfaceEmitter` is a package cycle today;
  a live-apply listener would invert the config→overlay edge, low priority.)
6. Persistence is automatic via `ConfigUtils.readConfigBase` /
  `writeConfigBase` over that category’s `OPTIONS` — keep the option on that list.

New categories: add another nested class + OPTIONS list, another JSON category
string in load/save, and a matching filter tab in `GuiConfigs`.

## MaLiLib config types

From MaLiLib’s `ConfigType` enum. Use these classes under
`fi.dy.masa.malilib.config.options` (and `…options.table` for tables).

### Scalars


| Type        | Class              | GUI                                | Role                            |
| ----------- | ------------------ | ---------------------------------- | ------------------------------- |
| BOOLEAN     | `ConfigBoolean`    | On/Off                             | Feature flags                   |
| INTEGER     | `ConfigInteger`    | Field or slider (optional min/max) | Counts, radius, ticks           |
| DOUBLE      | `ConfigDouble`     | Field or slider                    | Alphas, scales, timeouts        |
| FLOAT       | `ConfigFloat`      | Field or slider                    | Same as double, `float` storage |
| COLOR       | `ConfigColor`      | Color picker (`0xAARRGGBB`)        | Overlay / HUD tint              |
| STRING      | `ConfigString`     | Text field                         | Names, free-form ids            |
| BLOCK_STATE | `ConfigBlockState` | Block-state picker                 | A specific block state          |




### Lists and enums


| Type          | Class                   | GUI                                   | Role                       |
| ------------- | ----------------------- | ------------------------------------- | -------------------------- |
| STRING_LIST   | `ConfigStringList`      | Editable string list                  | Id lists, allow/deny lists |
| COLOR_LIST    | `ConfigColorList`       | List of colors                        | Multi-color palettes       |
| OPTION_LIST   | `ConfigOptionList`      | Cycle `IConfigOptionListEntry` values | Discrete modes             |
| OPTION_VALUES | (option-values helpers) | Multi-select style                    | Several discrete values    |
| LOCKED_LIST   | `ConfigLockedList`      | Ordered fixed membership              | Reorder-only lists         |
| TABLE         | `ConfigTable`           | Multi-column table                    | Structured rows            |




### Input and composites


| Type          | Class                   | GUI                        | Role                             |
| ------------- | ----------------------- | -------------------------- | -------------------------------- |
| HOTKEY        | `ConfigHotkey`          | Keybind button (multi-key) | Open config, cycle profile, etc. |
| *(composite)* | `ConfigBooleanHotkeyed` | Boolean + hotkey           | Toggleable flag with a bound key |


`ConfigBooleanHotkeyed` extends `ConfigBoolean` and stores both the boolean and  
its keybind (serialized as an object with `enabled` + `hotkey`).

