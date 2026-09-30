# MobWalk

A client-side Fabric mod that shows where a chosen mob can safely stand and walk.

Right-click a block with the wand and MobWalk paints every surface the chosen mob
could reach from there, and marks inescapable holes and hazards such as soul sand.

![img1](https://raw.githubusercontent.com/colinsmao/mobwalk/main/images/img1.png)
![img2](https://raw.githubusercontent.com/colinsmao/mobwalk/main/images/img2.png)

Every color, and whether each hazard is marked at all, is configurable.

Introduction video (YouTube):
[![Watch the video](https://img.youtube.com/vi/mWUKJwls9XM/maxresdefault.jpg)](https://youtu.be/mWUKJwls9XM)

## Requirements

- Minecraft `26.3` with [Fabric Loader](https://fabricmc.net/use/installer/)
- [Fabric API](https://modrinth.com/mod/fabric-api), [MaLiLib](https://modrinth.com/mod/malilib), and [ModMenu](https://modrinth.com/mod/modmenu)

MobWalk is purely client-side.

## Controls

The wand is a stick by default, and you can set it to any item.

- **Right-click a block** with the wand: paint the surfaces around it.
- **Right-click air**: clear the selection.
- **Sneak + scroll** while holding the wand: grow or shrink how far it checks.
- **Sneak + right-click air**: switch to the next mob.
- **Sneak**: see the painted surfaces through walls.
- **Auto Update**: Automatically recalculates around the selection or your location.

Some mobs are built in (eg Player, Ravager, Warden), and you can add your own with a width, height, and vertical reach.
Surfaces show while you hold the wand, or can be set to always show.

More details of each setting may be found in the menu tooltips.

## Known limitations

- Classification near the edge of the flood fill may be inaccurate, due to missing neighbors.
- An unreachable ledge between two reachable surfaces may be marked as a "hole", since technically it is unreachable from both directions.
- Single tick floods over large areas can stutter. Multi-ticks flood may desync.
- The visual vs collision hitbox of honey is inconsistent horizontally as well as vertically; this is not adjusted for, since usually it is within the dilated radius, so is not visible.
- Reachability assumes zero horizontal velocity. Otherwise this becomes modeling all of parkour. Most mobs do not make horizontal jumps, so the surfaces found are generally accurate.
- Soul sand slows through a slab; currently this is not taken into account. (Same with honey etc)

## AI usage

The code was written with Cursor, primarily using Opus and Grok. Mainly because
it saves me having to learn the Fabric API. I also used this project to learn how to
use coding agents. Planning artefacts can be found in git history.

90%+ of the actual design and scope is mine: the walkability geometry and logic,
what gets drawn and how, all the settings options, etc. After all, that's the fun part.
Also, even Opus still sucks at abstract planning.

## Building and contributing

`./gradlew build` puts the jar in `build/libs/`. [`AGENTS.md`](AGENTS.md) holds the
working rules and [`docs/project.md`](docs/project.md) the project facts.

Released under MIT.
