# Project context

Reference/background for this repo. `AGENTS.md` **holds the rules you must
follow; this file holds the project facts they operate on.** Read this when you
need orientation (what the project is, where code lives, which versions to use);
read the subsystem guides (`[rendering.md](rendering.md)`,
`[geometry.md](geometry.md)`, `[settings.md](settings.md)`) before touching
those areas.

## What this project is

A minimal, **client-side graphics overlay** mod for Minecraft, built on the
[Fabric](https://fabricmc.net/) toolchain. It renders both on the HUD and **in the
world**, with small parallel frameworks so more overlay widgets are easy to add.

## What the mod does

The mod visualizes **where a chosen entity can stand and walk**. Two small frameworks
host every widget — a HUD overlay (`Overlay` / `OverlayManager`) and an in-world
overlay (`WorldOverlay` / `WorldOverlayManager`); how they draw is in
`[rendering.md](rendering.md)`.

The main widget is the **standable-surface selection** (`CollisionSurfaceOverlay` +
`SurfaceSelection` + `SurfaceEmitter`): right-click a block with the wand (default
stick; configurable item id in settings) and the mod floods outward over
walkable terrain within a BFS hop-count flood radius, painting every surface the chosen entity
could stand on. The flood is **entity-size aware** — width dilation closes gaps
smaller than the entity, and height headroom drops floors under low ceilings — for the
profile chosen in settings (builtin roster: Player / Ravager / Warden /
Zombie-Witch / Skeleton / Cow / Sheep / Pig, plus the debug-only Point behind Debug
`showPointProfile`, enable toggles, and uncapped custom profiles). The
geometry and the output-sensitive flood live in `[geometry.md](geometry.md)`.

Each reached surface draws as a filled top quad colored at draw with edge markers that read the
terrain:

- **Skirts** — an **upward** wall face where an edge meets a wall or ceiling, a
**downward** skirt on a genuine drop (interior seams between equal-height pieces
stay clean).
- **Hole beams** — a through-walls beam at a drop a mob leaving the edge could not
climb back out of; a drop onto reachable ground keeps its plain skirt.
- **Cutoff ring** — surfaces near the radius limit fade toward grey, so an incomplete
selection reads differently from a real boundary.
- **Visible-face height** — blocks that render taller than they collide (soul sand,
mud) paint on the visible top face while all walkability math stays on the collision
top.

Interaction runs through the wand and crouch gestures: **shift+scroll** adjusts the
radius, **sneak + right-click at nothing** cycles the profile, **crouch** reveals
surfaces through walls, and `/mobwalk dump` writes a one-shot geometry dump. A HUD
readout shows the flood radius after a change. Behavior is configured from a MaLiLib
settings screen (General / Appearance / Debug) reachable via ModMenu → Configure and
persisted to `config/mobwalk.json`; General’s `Edit Built-in Profiles` and
`Edit Custom Profiles` buttons edit the roster; see `[settings.md](settings.md)`.

## Status & milestones

Milestones 1–11 are merged. The repo is a
client-only Fabric Gradle project generated from `FabricMC/fabric-example-mod` and
trimmed to client-only (see **Repository layout** below). `./gradlew build` passes
(produces `build/libs/mobwalk-1.1.1.jar`). Per-area detail lives in the subsystem
guides; in-game validation is per plan step plus the cross-cutting gates in
`AGENTS.md` (**Stage-gating**). The delivery history:

- **M1 — HUD framework** (`Overlay` / `OverlayManager`) with the transient
flood-radius readout (`RadiusIndicatorOverlay`).
- **M2 — in-world framework** (`WorldOverlay` / `WorldOverlayManager`), extract/draw
split over `LevelRenderEvents`.
- **M3 — stick surface selection**: a walkable flood from the clicked block, drawn as
fill + outline, with shift+scroll radius.
- **M4 — entity-size awareness**: config-space width dilation and the
output-sensitive lazy flood (adjacency via `footprintAdjacent`, reach via height window).
- **M4.5 — occluder-aware skirts + entity-height headroom**: upward skirts for
walls/ceilings, and the `(T, T+H]` standing-column headroom test. First unit tests
land here.
- **M5 — hole detection**: trapped-drop beams vs benign drops, classified
compute-side and subdivided so only the unsafe portion of an edge beams.
- **M6 / 6.5 — bug fixes**: 2-block grey cutoff ring, visible-face surface height, flood seeded from the clicked block's tops, the documented jump reach, and `/mobwalk dump`.
- **M7 — settings**: MaLiLib + ModMenu config screen with
General / Appearance / Debug tabs, live apply, save-on-close, and Profiles roster; see `[settings.md](settings.md)`.
- **M8 — surface/overlay pipeline refactor**: extract `RectMath` / `SurfaceEmitter`,
split client into `config` / `overlay` / `surface`, skirt domain cleanup, hygiene.
- **M9 — fluid hazards:** swimmable water/lava surfaces, fluid escape height, hazard
fill colors, and perimeter beams (`HazardBeams`).
- **M10 — solid hazards:** soul sand / magma hazard marking, similar to fluids.
- **M11 — chunked flood + auto-update:** budgeted multi-tick flood, progress ring,
  and General auto-update (**Anchor** keeps a clicked selection current; **Follow Player**
  paints around you on the interval).

## Repository layout

Client-only Fabric mod. Loom's `splitEnvironmentSourceSets()` keeps client code
in the `client` source set so it can never load on a dedicated server; only the
shared `MobWalk` (mod id + logger) lives in `main` so both source sets share
it.

```
.
├── build.gradle / gradle.properties / settings.gradle
├── gradlew / gradlew.bat / gradle/wrapper/...
└── src
  ├── main/java/dev/kelianmao/mobwalk/
  │   └── MobWalk.java                     # shared constants (MOD_ID, logger)
  ├── main/resources/fabric.mod.json       # client-only; client + modmenu entrypoints
  ├── client/java/dev/kelianmao/mobwalk/client/
  │   ├── MobWalkClient.java               # ClientModInitializer (+ debug keybinds, /mobwalk dump)
  │   ├── InitHandler.java                 # MaLiLib config + screen registration
  │   ├── config/                          # settings, roster, ModMenu
  │   │   ├── Configs.java                 # IConfigHandler → config/mobwalk.json
  │   │   ├── GuiConfigs.java              # MaLiLib GuiConfigsBase settings screen
  │   │   ├── MobWalkModMenuIntegration.java
  │   │   ├── WandItem.java / ProfileRoster.java / RosterProfileOption.java
  │   │   └── *ProfilesTable* / CustomProfileTableRows.java
  │   ├── overlay/                         # HUD + in-world overlay frameworks
  │   │   ├── Overlay.java / OverlayManager.java / RadiusIndicatorOverlay.java
  │   │   ├── FloodProgressOverlay.java
  │   │   └── WorldOverlay.java / WorldOverlayManager.java
  │   └── surface/                         # compute + selection widget + emit
  │       ├── EntityProfile / StandableRect / SkirtSpan / BeamSpan / FallColumn / ClimbRule
  │       ├── SelectionSnapshot.java        # the five published outputs, as one record
  │       ├── HazardClass.java / RectMath.java / SurfaceSelection.java / WorldGeometry.java
  │       ├── HoleBeams.java / HazardBeams.java / DownSkirts.java / OccluderSkirts.java
  │       └── CollisionSurfaceOverlay.java / SurfaceEmitter.java
  ├── client/resources/assets/mobwalk/lang/en_us.json
  └── test/java/dev/kelianmao/mobwalk/client/{config,surface}/  # mirrors source packages
```

`fabric.mod.json` sets `"environment": "client"`, declares a `client` entrypoint
(`dev.kelianmao.mobwalk.client.MobWalkClient`) and a `modmenu` entrypoint
(`dev.kelianmao.mobwalk.client.config.MobWalkModMenuIntegration`), points `icon`
at `assets/mobwalk/icon.png`, and depends on `fabricloader >=0.19.5`,
`minecraft ~26.3`, `java >=25`, `fabric-api`, and `malilib`; it suggests
`modmenu`.

## Target versions

Versions move fast for Fabric. Always re-check [https://fabricmc.net/develop](https://fabricmc.net/develop)
and the latest `FabricMC/fabric-example-mod` tag before building. (The *rules*
about these versions — year-based scheme, don't trust training data, authoritative
sources — live in `AGENTS.md` under **Key constraints**.)


| Component     | Version          |
| ------------- | ---------------- |
| Minecraft     | `26.3`           |
| Fabric Loader | `0.19.5`         |
| Fabric Loom   | `1.18-SNAPSHOT`  |
| Fabric API    | `0.161.0+26.3`   |
| MaLiLib       | `0.30.2`         |
| ModMenu       | `21.0.0` (dev)   |
| JDK           | `25`             |


Authoritative sources (pin the version selector to `26.3`):

- Guides: [https://docs.fabricmc.net/develop](https://docs.fabricmc.net/develop) — e.g. "Drawing to the GUI" and
"Rendering in the World".
- Fabric API javadocs: [https://maven.fabricmc.net/docs](https://maven.fabricmc.net/docs).
- Version numbers / template: [https://fabricmc.net/develop](https://fabricmc.net/develop).



## Manual install into a real launcher

1. `./gradlew build`, then grab `build/libs/mobwalk-1.1.1.jar` (ignore
  any `*-sources.jar`).
2. Install **Fabric Loader** for Minecraft `26.3` via the official installer
  ([https://fabricmc.net/use/installer/](https://fabricmc.net/use/installer/)).
3. Download **Fabric API** `0.161.0+26.3` from Modrinth/CurseForge.
4. Drop both the Fabric API jar and the `mobwalk` jar into the `mods/`
  folder of the relevant `.minecraft` profile, then launch that Fabric profile.



## Subsystem guides

Subsystem-specific depth lives in its own doc so `AGENTS.md` stays lean. Read the
relevant guide **before** touching that area; add a new guide as the project grows.

- **Rendering (HUD + in-world):** `[rendering.md](rendering.md)` — the HUD/world
render APIs, the `Overlay` / `WorldOverlay` frameworks, `26.3` rendering class
names, and pointers to the file-specific gotchas in the code.
- **Surface / collision geometry:** `[geometry.md](geometry.md)` — the
`StandableRect` representation, the rect/double-space (not pixel-raster)
decision, the entity-width dilation model, and the entity-height headroom rule.
- **Settings (MaLiLib config):** `[settings.md](settings.md)` — technical
reference for the config stack, live Generic/Debug options, screen layout
(flat list + LABEL sections), and MaLiLib option types.

Player-facing copy lives in `[../README.md](../README.md)` — what the mod does,
requirements, install, and the wand controls, written for someone playing the
game. Per-option help lives in the `comment.*` tooltips in
`assets/mobwalk/lang/en_us.json`, so the README stays short.

