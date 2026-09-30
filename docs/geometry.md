# Surface / collision geometry

How standable surfaces are represented, made entity-size aware (width dilation),
connected (reachability), and computed (the output-sensitive flood). This is the
**math layer** under the
block-hitbox overlay; how those surfaces are *drawn* lives in
[`rendering.md`](rendering.md). Surface/collision geometry stays in **rect/double
space**, never a pixel raster (a raster rewrite was prototyped and rejected — see
[Appendix A](#appendix-a-rejected-pixel-raster)).

**The core model, in one paragraph.** A standable surface is a rect at a collision
height. Two surfaces are **connected** when their footprints touch and the **lower one
can climb to the higher** under `ClimbRule` (land budget `reach`, with a fluid→non-fluid
escape cap — see [Reachability model](#reachability-model)): a climb test over an
*unordered* pair yielding **one undirected edge**. The selection is everything connected to the
clicked seed, so **reachable is escapable** — every edge is climbable in the up direction,
therefore every path is walkable backwards. Falling is a separate subject with its own
home, [hole classification](#hole-classification), and is unbounded there.

**World read vs flood math.** `WorldGeometry` is the adapter over the
`ColumnBoxes` port: it translates Minecraft block/fluid state into domain
`WorldBox` / `HazardClass` (including fluid-surface emission via
`fluidSurfaceHeight`, solid-hazard stamps for soul sand / magma, and
scaffolding non-occlusion).
`SurfaceSelection` holds the flood, merge, and publish;
`DownSkirts`, `OccluderSkirts`, and `HoleBeams` are the peer compute passes.
World access stays on that port (`WorldSurfaceIndex`, `HoleBeams.gatherLedgesFrom`,
`OccluderSkirts.computeFrom`).

## Representation: rect/double space

A standable surface is a
`StandableRect(double minX, minZ, maxX, maxZ, collisionTopY, visualTopY)` in absolute world
coordinates (the owning `BlockPos` folded in). `collisionTopY` is the **collision** top
walkability is keyed on; `visualTopY` is the visible/outline top when that sits above
collision (see [Visible-face top vs collision
top](#visible-face-top-vs-collision-top)), else equals `collisionTopY`. Merge ownership
and paint-side skirts/occluders may key on `visualTopY`. Coordinates are
**doubles**, not quantized to a `1/16` grid; edge/overlap compares are
epsilon-tolerant (`RectMath.EPS = 1e-6`, shared with `SurfaceSelection`). Quantizing is skipped on purpose: width dilation
(below) expands surfaces by entity-dependent amounts that are not `1/16`-aligned,
so precision is left to the math rather than baked into a grid. Minecraft
hitboxes are axis-aligned squares that never rotate, so the Minkowski sum of two
axis-aligned rects is again an axis-aligned rect — dilation is **exact and closed
over rectangles**, no rounding and no new primitive.

**Merge contract — partition key, priority class, and aggregate metadata.** The merge
(`RectMath.mergeCoplanarSplitFrontier`) emits a **non-overlapping XZ partition per
collision height**, and every downstream pass (occluders, down-skirts, holes) relies on
it. The contract has two tiers.

**Durable invariants** (they hold across every future milestone):

1. **Non-overlap per collision height.** Any two output rects that share a `collisionTopY`
   (within `EPS`) have disjoint XZ interiors — at most one rect per `(x,z)` per collision
   height. Two rects may share XZ only at **different** collision heights (a floor under a
   bridge), which is legitimate stacking.
2. **Coverage preserved.** The output covers exactly the union of the input footprints, per
   collision height.
3. **`collisionTopY` fidelity.** Each output rect's `collisionTopY` equals that of the nodes
   it covers.

**Partition key.** `collisionTopY` is the sole coexistence key: distinct collision heights
are distinct surfaces that can occupy the same XZ (a floor under a bridge). Every
walkability computation (reach, occlusion, drop/hole) is keyed on it, so the merge applies
the three invariants independently to each collision-height band.

**Ownership priority.** Within one collision band, overlapping rects compete for one
owner. The ownership class is the lexicographic product
`(radiusTier, surfaceClass)`. `radiusTier` is `INNER` for nodes with
`depth < limit` and `FRONTIER` for nodes at the cutoff; `INNER` orders before
`FRONTIER`, so inner geometry owns their overlap. Surface priority orders within
each tier. The merge treats the resulting sequence as one highest-first priority
partition: union rects **within one ownership class**, then subtract geometry
already claimed by higher classes before emitting that class. Different ownership
classes that abut remain separate; equal-class neighbours may strip-merge. The
full ownership class is therefore also the post-resolution homogeneity rule.

Today the surface class is `(hazardPriority, visualTopY)`, ordered by
`HazardClass.priority()` then highest visible shell: a hazardous class claims
overlap from a benign class in the same radius tier, then visual height orders
otherwise equal hazard classes. Water and lava arrive as `HazardClass` values from
vanilla fluid tags at world read; soul sand and magma stamp from solid block
identity (`WorldGeometry.solidHazardClass`). Hazard classes go through the same
priority-partition algorithm and durable invariants as every other class.

**Aggregate metadata.** Exact `depth` is traversal metadata rather than an ownership
axis. An inner winner's depth aggregates by minimum over every covering inner node,
independent of surface class; a frontier winner uses the depth limit and sets
`frontier = true` on the published rect. How draw uses those fields lives in
[`rendering.md`](rendering.md).

## What the selection is (the computed result)

`SurfaceSelection.select(level, seed, radius, profile)` arms a `FloodJob`; the
published set is the `SelectionSnapshot` swapped when `advance` finishes —
**dilated, occlusion-aware standable tops reachable from the seed**, merged into
maximal rectangles for drawing. Independent of how it is computed, the result is
defined by four rules:

1. **Occlusion-aware tops, with entity-height headroom.** A collision sub-box's top
   at height `T` is standable only over the footprint that is *not* occluded. A box is
   an occluder iff it **rises above `T`** (`yMax > T`) **and** either is **buried**
   over the top (reaches down to/below the surface, `yMin <= T` — a box resting
   directly on the top has `yMin == T`, a box straddling it has `yMin < T`) **or**
   floats within the entity's **standing column** as a headroom ceiling
   (`yMin < T+H`, `H = profile.height()`). The buried term is the `H = 0` base case
   (`minY <= T < maxY`), so a box directly on the surface buries the top and
   **embedded/stacked tops are removed**; without it (`yMin < T+H`
   alone) a directly-on-top box at `H = 0` would have `yMin == T` ≮ `T`, so every
   embedded top would leak. **Point (`H = 0`) uses the buried term alone.** The `yMax > T` bound is
   strict so the box being stood on never self-occludes, and a ceiling bottom exactly
   at `T+H` (`H > 0`) is just-enough clearance (neither term fires). The covering
   box's own top is itself a (higher) standable surface, so one rule both removes the
   buried/headroom-robbed lower top and supplies the higher one; a **ceiling** robs
   headroom from the floor **below** it (the removal zone extends downward by `H`, it
   is not "extend occluders upward"). A partial overhang yields a **partial** surface
   via the same guillotine subtract. Non-burying overlaps (an air gap larger than `H`
   between two tops) stay **distinct levels** — never collapsed to `max collisionTopY` — so
   stacked surfaces (overhangs, spiral staircases) are preserved.
2. **Entity-width dilation** (see [below](#entity-width-dilation)): every footprint
   is grown by the profile half-width `W/2` before the spans-above test, so gaps and
   walls eat into the standable area by the entity's size.
3. **Merge.** Coplanar (`|dTopY| < EPS`) rects are partitioned and merged
   (`RectMath.mergeCoplanarSplitFrontier`: group by the **partition key `collisionTopY`**,
   see [Merge contract](#representation-rectdouble-space) — form composite
   `(radiusTier, surfaceClass)` ownership classes, process them in lexicographic priority
   order, union within each class and subtract higher-priority claimed geometry, then
   greedy strip-merge equal-class abutting rects along X then Z to a fixpoint).
   A flat floor collapses
   from a grid of unit cells to one rect → clean skirts, fewer quads. Greedy is not a
   minimal partition, but a missed merge only costs an extra interior skirt,
   **never reachability**.
4. **Flood / reachability.** The click defines a **non-emitted origin**: the raw
   pre-occlusion dilated footprints of the clicked block's collision boxes (carrying
   `HazardClass`). An exposed top is in the initial wave when it is footprint-adjacent
   to that origin and `ClimbRule.climbs` — depth **0** when its source is the clicked
   block, depth **1** otherwise. From there a surface is reached iff it connects to an
   already-reached surface by **one geometric adjacency rule**: the footprints share an
   edge with positive overlap (or overlap with positive area), `footprintAdjacent`,
   **and** `ClimbRule.climbs` — the lower of the two can climb to the higher, one
   undirected edge (see [Reachability model](#reachability-model)). This one rule covers
   same-block, own-column, and neighbour-column connections: a glass pane on a block
   connects to that block's exposed ring because their footprints abut at the hole
   edges — no special case. Only exposed tops are painted; a fully occluded click
   cell (e.g. Ravager on soul sand beside full blocks) stays unpainted while its
   origin still enters the surrounding standable graph at depth 1.

**Radius is a BFS depth limit** (max hop-count from the click origin), not a spatial X/Z
window: horizontal reach is unbounded; termination comes from the hop-count cap plus
a Y band of about `oy ± radius`. Connectivity gates reach on top of that (a drop
`> reach` or a disconnected patch is never reached). A fully occluded click spends one hop
entering the exposed graph, so its frontier sits one layer closer than a click on
an exposed surface at the same radius.

A **Point** profile (`W = 0`) reproduces the original zero-width point-walker
exactly (dilation is a no-op, tops only abut).

## Entity-width dilation

Treat the entity as a point and pre-grow the world by its half-width `W/2`:

- **The forbidden region grows, not just the supports.** Dilation distributes over
  a union (`dilate(A ∪ B) = dilate(A) ∪ dilate(B)`), so growing each support rect by
  `W/2` and unioning is equivalent to shrinking each gap by `W/2` per side. A gap of
  width `g` flanked by support: `g <= W` bridges (no hole), `g > W` leaves a hole —
  exactly "can't fall into a hole smaller than yourself". Plateau edges overhang by
  `~W/2`; a wall pulls the standable region back `~W/2` from its base (the entity
  perches onto the wall top) as a free byproduct.
- **Occlusion is the same pass.** The spans-above test (rule 1 above) runs on the
  *dilated* footprints, so cutting a buried lower top and supplying the higher one
  is one operation, not a separate subtract.
- **`exposeBox` is the unit op.** It grows one box's footprint by `W/2`, then
  subtracts (guillotine `RectMath.subtractRects`) every *dilated* box that occludes its top
  (`yMax > T && (yMin <= T || yMin < T+H)` — the buried-or-headroom test, rule 1),
  yielding 0..N surviving top rects. The boxes it must subtract live in a bounded
  column window — see `occluderColumns` below.

Locality is bounded (growth is `< 1` block even for the Ravager), which is what
makes the output-sensitive flood possible.

## Reachability model

Reach is a **climb test over an unordered pair**, implemented as
`ClimbRule.climbs(a, b)`: of two surfaces, it asks whether the **lower can climb
to the higher**. The land budget is `lower.collisionTopY() + profile.reach()`
(`reach` = `max(jump, step)`). When the lower `HazardClass.isFluid()` and the
higher is not, that budget is further capped at
`lower.collisionTopY() + FLUID_SURFACE_DROP + fluidEscape`
(`FLUID_SURFACE_DROP = 1/9`; `fluidEscape` from Generic `fluidEscapeHeight`) so
leaving fluid onto a non-fluid rim is never easier than jumping on land. The pair
is unordered — same verdict either argument order — and one passing pair yields
**one undirected edge** that the flood walks in either direction. A rim whose
rise exceeds the budget yields no edge at all: the ground under it enters the
reached set only by another route (a staircase, a slope), if at all.

The collect window stays `[h - reach, h + reach]` (a valid **superset** of the
climb predicate); `ClimbRule` is the real edge filter.

That single property carries the model's central guarantee. Every edge is climbable in the
up direction, so every path is walkable backwards, and **membership in the reached set
means the entity can be there *and get back out*** — reachable is escapable. This is the
guarantee [hole classification](#hole-classification) spends: asking "is a reached surface
below this rim?" is asking "can it climb out again?", and the answer holds only because
the edge is mutual.

Falling belongs to [hole classification](#hole-classification), and is unbounded there:
`classifyDrop` accepts the topmost reached surface below the rim at **any** depth. So the
two vertical questions in this codebase have separate homes — connectivity is gated by the
climb, and the physics of descent live in the drop classifier.

**Player** and **Ravager** use **`1.2522`** (documented living-entity jump peak from
the discrete tick loop on impulse `0.42` / gravity / drag). **Point** uses
**`1.0`**. That value is the profile’s fixed vertical threshold only — the flood
does not model horizontal jump distance (parkour), and it does not apply block or
effect modifiers to jump height (honey, slime-block bounce, Jump Boost, and
similar). Reachability is plain yes/no — a location is reachable from the seed or it is not —
so anything not connected to the seed is simply **unreachable** (a hole/gap), modulo
the radius budget, which can cut off a very long winding path. So a shallow
(`<= reach` deep) trench is reachable and its floor *is* painted (not a hole); to see
the width rule you need a gap that is **deeper than `reach` or over the void**.
**Entity-height headroom** is
modelled (rule 1: a top survives only where `(T, T+H]` is clear),
so a floor under a low ceiling drops out for a tall profile. Explicit hole detection /
classification builds on this (below): the flood still only paints
*coverage*, and a separate predicate reads that coverage to label each drop edge.

## Entity profiles (size + headroom)

`EntityProfile(name, width, height, reach)` selects the entity the flood is computed
for. The profile is chosen live via the `mobProfile` setting (see
[`settings.md`](settings.md)); the shipped builtin sizes live on `EntityProfile`
constants, with enables/order in `ProfileRoster`.

Each field drives one part of the math: `W` drives dilation (above), `H` drives
headroom (rule 1), and `reach` is the climb threshold — `max(jump, step)`, applied as
the [climb test](#reachability-model) above. Skirt
*draw* heights are Appearance `downSkirtHeight` / `upwardSkirtHeight` (not `reach`).
Widths and heights are the
vanilla hitbox sizes: doubles, not `1/16`-aligned, consistent with the rect-space
model. Two examples anchor the range:

- **Player** — `W = 0.6`, `H = 1.8`, `reach = 1.2522` (the documented living-entity
  jump peak, shared by the larger Ravager).
- **Point** — `W = 0`, `H = 0`, `reach = 1.0`: zero width makes dilation a no-op and
  zero height reduces headroom to the buried test, so Point stays the pure
  point-walker and geometric baseline.

## Fluid surfaces

Water and lava are **non-occluding support surfaces**: each fluid cell can emit a
standable surface that travels the same path as a solid top (dilation, clip, flood,
merge, skirts, holes), while contributing no collision volume to occlusion.

- **Occlusion is a volume property.** Burial and headroom ask which boxes span
  above a top. Only `WorldBox`es with `occludes=true` participate in that clip;
  fluid surfaces and scaffolding collision boxes set `occludes=false`, so every
  solid top under them survives, and solids still clip those tops the same way
  they clip any other top. The same rule gates up-skirts:
  `OccluderSkirts.wallOccluder` requires `occludes`, and
  `OccluderSkirts.compute` reads through the shared `ColumnBoxes` port
  (`WorldGeometry.levelColumnBoxes`) so only occluding boxes mark wall faces.
- **Emission (`WorldGeometry.levelColumnBoxes`).** When Generic `swimmableFluids` is
  on, a cell whose fluid state is in `FluidTags.WATER` or `FluidTags.LAVA` emits one
  full-footprint fluid surface (`yMin = y`, `yMax = y + fluidSurfaceHeight`). Height
  is `FluidState.getHeight` when that value is above `0.4` (vanilla fluid-jump
  threshold), otherwise `0` (thin sheet seated on the cell floor, coplanar with
  the solid underfoot). Falling fluid uses the same path as still fluid
  (`getHeight == 1.0`). The fluid surface carries a `HazardClass` stamp (`WATER` /
  `LAVA`) for merge ownership / seating / draw. Ordinary solids stay `NONE`; soul
  sand and magma stamp solid hazards (see [Solid hazards](#solid-hazards-soul-sand--magma)).
  Geometry for fluids only needs “emit or not.” Thin sheets at height `0` share
  `collisionTopY` with the solid underfoot; merge ownership lets the fluid's higher
  `HazardClass.priority()` claim that overlap.
- **Column continuity.** Because a fluid surface sets `occludes=false`, every cell's
  fluid surface in a fluid column survives exposure. Consecutive tops differ by at
  most `1.0` (plane to plane exactly `1.0`; lowest fluid surface one block above the
  floor), so the [climb test](#reachability-model) connects surface, intermediates,
  and floor — including a waterfall column. Fluid→fluid pairs use plain
  `profile.reach()` (the escape cap does not bind), so the column stays connected
  at every `fluidEscapeHeight` setting.
- **Escape cap.** Leaving fluid onto a non-fluid rim uses Generic
  `fluidEscapeHeight`: a rim height measured from the fluid **block** top, converted
  to a height above the plane by `+ 1/9` (`ClimbRule.FLUID_SURFACE_DROP`). The
  predicate keys on `HazardClass.isFluid()` (water/lava only), so solid hazards
  (soul sand / magma) do not inherit the cap. Contract: `FluidEscapeTest`.
- **Shore complementarity.** A solid shore dilates over adjacent water by `W/2`;
  the fluid top is clipped by that solid the same amount. At a flush pond rim the
  surviving fluid edge meets the shore's dilated footprint, so the drop classifier
  finds the kept floor (or the plane one cell down) as a landing. Edge marking stays
  with the occluder / drop passes.
- **Perimeter beams (`HazardBeams`).** Each non-frontier fluid rect edge minus
  sub-spans covered by an abutting neighbour with the same `HazardClass` and equal
  `collisionTopY` (interior pool seams) publishes a `BeamSpan` stamped `WATER` /
  `LAVA`. No occluder subtract — water|lava abutting edges keep both kinds.
  Solid hazards share this path on post-punch footprints (see
  [Solid hazards](#solid-hazards-soul-sand--magma)). `HazardClass.HOLE` is
  beam-marker identity only (trap drops from `HoleBeams`); it is never stamped on
  standable surfaces or world boxes. Draw path: [`rendering.md`](rendering.md) beams.

Contracts: `FluidPlaneTest` (existence, thin-at-0, column spacing, hazard tag
coverage), `FluidClipContractTest` (standable top, no clip from fluid, shore
complementarity), `MergeContractTest` (hazard ownership fidelity / mixed-identity
disjoint rects), `FluidEscapeTest` (escape budget, clamp, symmetry, column ladder),
`HazardBeamsTest` (perimeter seams, water|lava abut, frontier skip).

## Scaffolding

Scaffolding collision boxes are **non-occluding support surfaces**:
`Blocks.SCAFFOLDING` emits each vanilla collision AABB (thin top / bottom via
`CollisionContext.empty()`) with `occludes=false` and `HazardClass.NONE`.
Tops still dilate, flood, merge, and accept solid clip; stacked lids and floors
under a platform keep their headroom. Contract: `ScaffoldingOcclusionTest`.

## Solid hazards (soul sand / magma)

Walkability and vanilla solid block effects are different questions. Walkability still
dilates supports by `W/2`.

**Truth (vanilla-shaped):** among coplanar supports that cover a point under the
entity’s dilated footprint, the effect block is the **Euclidean nearest** undilated
support to the entity center (cliff with no coplanar rival keeps the support you
stand on).

**Heuristic (what we paint):** axis-aligned only —
**perpendicular bisector (face midplane)** between face-separated rivals, plus a
**conservative square in each contested corner** (side `g(√2−1)`, under-approximates
stone so true magma is never painted as safe). Not full fluid-style dilation and not
a pure undilated core.

- **World read.** `WorldGeometry.solidHazardClass` stamps `SOUL_SAND` /
  `MAGMA` on solid collision `WorldBox`es from `Blocks.SOUL_SAND` /
  `Blocks.MAGMA_BLOCK`. Collision, outline, and `occludes=true` are those of any
  other solid box.
  Priorities: `SOUL_SAND(3)`, `MAGMA(4)`; `isFluid()` stays false; climb ignores them.
- **Expose then coplanar-rival punch.** `exposeBox` dilates and occludes as for any
  solid top. For each post-occlusion piece `E` of a solid-hazard target, each
  coplanar rival `B` (same `collisionTopY`, different hazard — stone/`NONE` and
  other rivals; same class such as magma|magma do not punch each other):
  - **Face-aligned** (separated on one axis with positive undilated overlap on
    the other, **or** flush on one axis and gapped on the other): form the
    contested subspan `E ∩ B+ ∩ faceBand`. Classic face: `faceBand` = undilated
    perp-overlap ± `halfW`. Flush + gap: `faceBand` = rival’s undilated extent
    on the flush axis. Punch the rival’s side of the equidistant midplane inside
    that subspan.
  - **Corner squares** on each rival corner whose outward orthant reaches the
    target: punch `E ∩` a square of side `g(√2-1)` (`g` = min of positive axis
    gaps; flush on one axis still sizes by the other). Runs **in addition to**
    face midplanes so face magmas cannot refill a bite.
  - **No separation / nested:** punch `E ∩` undilated(`B`) only when neither face
    nor corner square applied.
  Occlusion runs first (soul sand beside a taller full block is clipped before punch).
- **Face seams match the truth midplane; corners are AABB-conservative** (crescent
  of true stone may stay magma). Ring stress locked by `SolidHazardPunchTest`.
- **Cliff non-compete.** A void overhang has no coplanar competitor, so the dilated
  lip over empty space stays hazard. Magma flush with stone seams at the **block**
  edge on the stone side.
- **No `UNDILATED_NONE`.** Paint is the explicit punch, then ordinary same-`HazardClass`
  merge — not a merge-only ownership axis for undilated cores.
- **Fluids stay fully dilated.** Standing on the fluid top *is* the fluid condition.
- **Draw.** Fill and perimeter beams use the post-punch footprint (Appearance
  show/color per kind; same `HazardBeams` abut-suppress as fluids). Crouch borders
  follow those rects. See [`rendering.md`](rendering.md).

Contract: `SolidHazardPunchTest` (magma|stone, cliff, Point, soul sand|wall, 2×2,
air-gap midplane, checkerboard, `.S./.../MMM` corner bite, magma-ring corner square).

## How it is computed: the output-sensitive (lazy) flood

`select` arms **`Bfs`**: a surface BFS that exposes geometry only as it
reaches it, so cost tracks the reachable set (and its occluder shells) rather
than the window volume — a large win in caves / against walls, and asymptotically
on open ground. Adjacency uses `RectMath.footprintAdjacent`; reach is the
profile height window in `Bfs` (see [`project.md`](project.md)
milestones).

- **Resumable, one depth ring at a time.** `Bfs` is a stateful object holding the
  whole flood — the `WorldSurfaceIndex`, the `hopCount` visited map, the current and
  next depth ring, and the `reached` node list — as fields, so a flood can span
  calls. `seed()` arms it from the click origin (ring 0 from the seed block's own
  tops, ring 1 pre-seeded from the abutting ones), and each `stepRing()` expands
  exactly the current depth. Call `k` completes ring `k-1`, so after `k` steps
  `reached` holds exactly the surfaces at depth `<= k-1` — itself a valid flood at
  that radius, which is what lets a caller yield between rings. Rings reproduce the
  order a single FIFO queue gave: BFS over unit-weight edges leaves a queue in
  nondecreasing depth and, within a depth, in discovery order. That order is worth
  keeping because the greedy strip merge is order-sensitive.
- **`Bfs` owns every piece of expansion state**, which makes its lifetime the unit of
  consistency: dropping the reference releases the flood whole, index included. Its
  parameters (origin, depth limit, `halfW`, reach, `ClimbRule`, the `ColumnBoxes`
  port) are captured at construction, so a flood in flight is immutable with respect
  to settings and a radius/profile change is answered by a fresh `Bfs`. Contract:
  `FloodRingContractTest` (ring-per-step membership, slice-granularity invariance,
  a partly advanced flood draining to the same result, stepping past completion, an
  origin over nothing) — all driven through `stepRing` with no clock, since where a
  driver yields is what must not matter.
- **Spread across frames, expansion and finalize alike.** `select` arms a `FloodJob`
  and returns; each frame spends the General `floodBudgetMs` budget on it
  (`SurfaceSelection.advance`), and the step that finishes the last pass swaps the
  published snapshot. `FloodJob` walks one cursor through the whole pipeline — a
  depth ring per step while expanding, then one whole pass each for the merge, the
  collision occluders, the drop edges, the paint rim when raises are active, the
  holes and the hazards — so the finalize costs frames rather than one hitch at the
  end. A step is always a whole unit of work, which is what the passes require: they
  read the complete reached set, and each feeds the next. The expansion stays one
  monotonic queue (no ring is ever re-expanded). Where the yields fall never changes
  the output for a fixed world; a world edit mid-flood is the one divergence, since
  the append-only index keeps whatever a column held when it was first scanned.
  Holding the expansion and the passes behind that one `FloodJob` reference is also
  what makes a cancel total — see [`rendering.md`](rendering.md) for the driver and
  lifetime rules.
- **Cost is dominated by the expansion.** A radius-30 Ravager flood over broken
  terrain measures ~269 ms total: ~193 ms of BFS, then ~26 ms merge, ~16 ms holes,
  ~14 ms for each occluder rim computed, and under 3 ms each for the drop edges and
  hazards. So the merge is the largest single finalize step, and a selection with no
  raised visuals saves a whole occluder pass. `/mobwalk dump` prints this split as
  its `phases` line.

- **Nodes are raw per-box dilated tops** (`exposeBox` output, *pre-merge*), each
  tagged with its source cell (`CellSurface`). The union/merge runs **after** the
  flood, on the reached set only (area-preserving, so connectivity is identical with
  or without an early merge).
- **Neighbour search is column-local.** From an expanded surface in cell `c`, candidate
  cells are within Chebyshev `floor(W) + 1` of `c` (`1` for Point/Player, `2` for
  Ravager). This is the cell distance at which two dilated tops can still bridge or
  abut (raw gap `Δ-1 <= W`). It is **not** `ceil(W)`: that is `0` for Point and would
  never connect adjacent floor tiles. The same `floor(W)+1` is the occluder-shell
  reach used when exposing neighbours — one reach everywhere.
- **Lazy in XZ.** A cell's collision boxes are queried on first touch and cached
  per-column, so a column is exposed at most once and only reachable columns (plus
  the occluder shells around them) are ever touched.
- **Lazy in Y (`ensureRows`).** A column is scanned only over the narrow block-row
  windows the flood needs near its current height — never the full `[oy-radius-1,
  oy+radius+1]` band. A neighbour cell is scanned for tops within one `reach` step of
  the expanded surface (`collect(cx, cz, h-reach, h+reach)`); each candidate box's
  occluder shell is scanned over the rows from `floor(yMax)-1` up to
  `floor(yMax+H)+1` — the upper bound **extended by the headroom `H`** so the
  ceilings/overhangs in the standing column `(T, T+H]` are exposed before
  `exposeBox` runs (not just the box's own buried shell); `H = 0` collapses it back
  to `floor(yMax)±1`. `exposeBox` is memoized per box (and `H` is fixed per
  `select`, so the box alone is the memo key). A `BitSet` per column records
  scanned rows so each `(column,row)` is queried at most once. The flood boots from
  **non-emitted origin probes** — each clicked-block box's raw dilated footprint at
  its `collisionTopY` (with `HazardClass`) — then assigns initial depths via
  `assignOriginWave` (depth 0 on the seed block's exposed tops, depth 1 on other
  tops adjacent to a probe and climbable under `ClimbRule`). Later hops use the
  same `ClimbRule.climbs` + footprint adjacency on exposed tops only (candidates
  still gathered in `[h-reach, h+reach]`). The flood front moves `<= reach` per
  hop, so by induction everything reachable is found near an already-reached
  height. This drops the
  per-column vertical factor from `O(radius)` to `O(heights the flood actually
  traverses there)`, so open ground goes `~radius³ → ~radius²`.
- **`occluderColumns` (the occluder shell).** For `exposeBox` to trim a box's
  dilated top correctly it needs every box that can span above that top. A box in
  column `(cx,*)` spans `[cx, cx+1]`, dilated to `[cx-W/2, cx+1+W/2]`, which overlaps
  the target's dilated footprint iff `floor(min - W) <= cx <= ceil(max + W) - 1` per
  axis. This is the exact (full-block-conservative) window — for Point (`W=0`) it
  collapses to the box's own column. It is shared by `exposeBox` and
  `WorldSurfaceIndex.ensureRows` (the flood and the ledge gather both sit on that one
  index), keeping the index and the scan in lock-step. Tightening
  it is **result-preserving**: the dropped columns abut with zero overlap and
  trimmed nothing.

Net cost ≈ `columns × rows-per-column`. The XZ laziness + `occluderColumns` trim the
first factor; lazy-Y trims the second — orthogonal, and they compose.

## Hole classification

Hole classification **reads the flood output** to label the
**drop edges** of the selection (the `openSpans` edges that are neither equal-height
merge seams nor wall/ceiling up-skirts). One pure predicate,
`HoleBeams.classifyDrop`, classifies a **homogeneous** drop sub-span as `HOLE` or
`BENIGN` in two steps:

1. **Is there a reached surface strictly below the edge, across the fall column?** If
   **no** &rarr; `HOLE` (the void, or unreached ground the mob cannot climb out of). This
   is the trivial definition of a hole: *not reachable*. Reachability is exactly
   **reached-set membership** — the flood already computed it for this entity (width,
   occlusion, reach all accounted for), so nothing is re-derived here.
2. **If yes** (a reached floor at `landY`), **is there a standable ledge between the edge
   and that floor?** A ledge is a dilated standable surface with top strictly in
   `(landY, T)` crossing the column. If one exists &rarr; `HOLE` (the mob lands on
   the ledge and is trapped above the reachable floor). Otherwise &rarr; `BENIGN`, fall
   distance `T − landY`.

Step 1 is pure rect/double work against the reached `StandableRect`s. Step 2 is the one
world read: `HoleBeams.gatherLedges` finds candidate boxes with tops in `(landY, T)` in the columns
around the fall column and exposes each through **`WorldSurfaceIndex.tops`, the same
per-box primitive the flood uses** (dilate by `W/2`, cut by the box's occluder shell) —
so only flood-standable fragments count as ledges, and a wide hitbox is handled the same
way the flood handles it. The ledge test reuses the flood's own standability.

### The fall column

Both steps ask the same question — *what is under the falling mob* — and the answer is
`FallColumn(alongX, maxSide, line, lo, hi)`: **the rim line itself**, over the drop
span's interval. Surfaces are already dilated by `W/2`, so a rim is exactly the line
where a point-walker loses support and drops straight down it, and the entity's width is
already carried by the rects being tested against it. A surface catches that fall iff it
**crosses the line** — `RectMath.crossesLine(rect, alongX, maxSide, line)`: the rect
starts at or before the line and reaches strictly past it on the drop side, plus
positive overlap along the edge (`FallColumn.crosses`). Sub-spans narrow `[lo,hi]` and
keep the line (`clampedTo`).

The **drop side** is part of the query: only geometry reaching onto the far side of the
rim can catch the fall. The dropping surface's own edge lies exactly on the line, so the
crossing test reads it as behind the rim and a drop stays a drop (a symmetric "contains
the line" test would turn every drop into a step-up). Ground that sits *beside* the rim
— a terrace across a gap, a floating block a block out, a ledge clear of the cliff face
— is passed on the way down, so such a rim stays a hole.

**Exposure-agreement contract.** The invariant step 2 relies on: **a candidate ledge is
exposed by `HoleBeams.gatherLedges` iff the flood would expose it.** It holds **by construction**:
both callers sit on one `WorldSurfaceIndex` (the shared lazy box index + memoized
`tops()`), so the occluder shell has a single definition and cannot drift between them.
`exposeBox` subtracts only occluders present in the index, and `tops()` populates that
index over the exact shell a candidate top `L` needs, per axis:

- **Y:** rows `floor(L) - 1` up through `floor(L + height) + 1`. The upper bound is the
  **headroom** extension: a ceiling in the standing column `(L, L + H]` buries `L`, and
  these ceilings sit **above** the rim, so a rim-height cap (`ceil(collisionTopY)`) would
  drop them and re-expose a ledge the flood buried. A shape rising from the block row
  below reaches `L` from within `floor(L) - 1` (see occluders-from-below, below).
- **XZ:** the `occluderColumns` window (expanded by the full width `2 · halfW`), so a
  wide entity's occluders one or two columns out still participate.

Gather calls `tops()` rather than re-deriving this shell, so ledges are exposed over the
same window the flood uses; a narrower one (Y capped at the rim, XZ at `ceil(halfW)`)
would re-expose buried ledges as false `HOLE`s. The **candidate** window on top of that shell is derived from the fall
column itself — the rim column, the span's own columns, each widened by `ceil(halfW) + 1`
for dilation — so it stays a function of the edge being classified rather than of however
wide the classification region happens to be.

Near the radius the selection is incomplete, but a drop there is still classified
normally — a genuine deep drop reads HOLE. The **outermost edge**
of the selection (at `ringEnd`) is suppressed entirely render-side: skirts and hole beams
there are artifacts of the radius cutoff. Interior border uncertainty is a render concern:
a hole beam in the grey ring is blended toward grey by the same distance falloff that
greys tops/skirts (see [`rendering.md`](rendering.md)), signalling "raise the radius".

The candidate drop spans are the compute-side down `SkirtSpan`s (every genuine drop
edge). `HoleBeams.compute` walks them once per select: for each it takes the **fall column**,
gathers ledges, and classifies. Because **one edge can span reached and unreached
ground**, `HoleBeams.holeSubSpans` subdivides the edge at the `[lo,hi]` of the reached rects that
cross the line (`spanBreakpoints`) into homogeneous sub-spans — so a hole span's bounds
are the geometry justifying it — classifies each via `HoleBeams.classifyDrop`, and publishes
the contiguous `HOLE` pieces (coalesced) as `BeamSpan`s with `hazard = HOLE`.
`BENIGN` sub-spans keep their ordinary down-skirt. Each `BeamSpan` is drawn as its
own through-walls beam at the rim (a long dangerous rim reads as a row of beams
clearly marking every unsafe edge). Hazard perimeters (fluids and solid hazards)
use the same `BeamSpan` type via `HazardBeams` (see [Fluid surfaces](#fluid-surfaces)
and [Solid hazards](#solid-hazards-soul-sand--magma)).

**Ledge gather occluders from below.** `HoleBeams.gatherLedges` exposes each candidate box (top in
`(landY, collisionTopY)`) via `WorldSurfaceIndex.tops`, whose occluder shell starts one
row below the candidate top (`floor(L) - 1`), so collision that *lives in the block row
below* `L` and rises into the standing column (vanilla walls/fences at height 1.5) still
participates in burial. Those occluders-from-below keep burial complete for rising shapes.
Example: a lantern on a wall. Under Ravager dilation the lantern body (wider than its
cap) would leave a `7/16` ring with `fall = 0.0625`; the wall box below is in the shell
and buries it.

**Assumption:** one block row below the candidate top is enough — the occluding shapes
that matter extend at most ~1.5 upward from their block Y, so they sit in
`floor(L) - 1` when `L` is a full-block top. Deeper scan if a single
block's collision grows past that, or if a multi-block pillar's lowest piece sits
further below.

## Visible-face top vs collision top

Everything above derives from `getCollisionShape`, so `StandableRect.collisionTopY` is the
collision `yMax`. A handful of blocks **render taller than they collide** — soul
sand collides at `14/16` (`0.875`) but outlines as a full cube, mud at ~`0.9` — so a
marker drawn at the collision top sits **buried inside the visible block**. The fix
is a second height, `StandableRect.visualTopY`, so paint can sit on the visible face
while **walkability stays on `collisionTopY`**. Merge ownership and paint-side
skirts/occluders may also key on `visualTopY`.

- **Source data (paid once per state, no heuristic).** `WorldBox` carries the source
  block's whole-shape `blockCollisionTop` (`y + collisionShape.max(Y)`) and
  `blockOutlineTop`. The outline read (`getShape(...)`) is a real cost, but **every
  block state is checked** — no "renders taller ⟹ collides below `1.0`" shortcut,
  which a mod or future block would break. Instead `visibleTop` memoizes the block's
  outline top per `BlockState` in a static cache (`OUTLINE_TOP_REL`, `NaN` = "no
  separate outline"), so the shape is read **at most once per distinct state ever
  seen** and every later occurrence is a map lookup. It is gated by the render
  toggle (`computeVisualTop`, threaded from `Configs.drawOnVisibleFace()` into
  `select`): off ⇒ `blockOutlineTop = blockCollisionTop`, nothing lifts and no lookup
  is done. Because the flag gates the compute, **toggling the Appearance setting
  re-floods** from the last seed via `reselectWithMobProfile` (cheap: toggling is
  rare). This is the one Appearance option that touches compute, an accepted exception
  to "Appearance is draw-only" given the raise is inherently a compute-side read (it
  joins `floodRadius` / profile changes, which already re-flood). The memo treats the
  property as position-independent (keyed by state only); the few context-dependent
  blocks never have a neighbour-varying *top* raise, so the first-seen value is safe.
  Occluder and ledge scans never raise: both read through
  `WorldGeometry.levelColumnBoxes(level, false, …)` so both tops collapse to the
  collision top. The flood's own scan producer
  (`WorldGeometry.levelColumnBoxes(level, computeVisualTop, …)`, feeding
  `WorldSurfaceIndex`) computes both tops at the node-producing site.
- **The raise rule (`exposeBox`).**
  `visualTopY = (|collisionTopY − blockCollisionTop| ≤ EPS ∧ blockOutlineTop > collisionTopY) ?
  blockOutlineTop : collisionTopY`. Gating on *"this sub-box is the block's topmost collision
  surface"* is what leaves **stair treads, bottom slabs, and fences** untouched (a
  stair's lower tread is not the block's top; a fence's outline is *shorter* than its
  `1.5` collision top, so `blockOutlineTop > collisionTopY` is false). Only full-render-but-
  short-collision tops lift.
- **Through merge.** `visualTopY` is today's [priority
  class](#representation-rectdouble-space): raised geometry claims any overlap with
  flush geometry, while abutting raised and flush regions stay separate rects. The
  raised paint therefore keeps its own height and the flush-only region stays flush.
  Up and down `SkirtSpan`s carry a `visualBaseY` (the source rect's `visualTopY`)
  alongside the collision `baseY`. Published `BeamSpan`s carry only the draw foot
  (`visualBaseY`) plus the rim interval.
- **Skirts are a render pass, holes a geometry pass.** Downs and occluder (UP) skirts
  share a dual rim: **`collisionTopY`** for hole / collision-drop coverage, **`visualTopY`**
  for paint. `DownSkirts.compute` / `OccluderSkirts.wallOccluder` take that key; `maxExtent` for UPs is
  `wallTop − rim` (positive when emitted). On the visual pass, an abutting neighbour with
  **equal or higher** `visualTopY` covers the edge; a **lower** neighbour is a land stop
  (`maxExtent = rimKey − neighbourKey`; open drops unlimited). A visible step at the same
  `collisionTopY` but different `visualTopY` (path lip on soul sand) skirts only from the
  high side; flush remnant↔lip gets none, remnant↔flush-path wings get a short DOWN.
  `select` always builds collision-rim occluders + `dropEdges`; when any rect is raised it
  also builds the visual-rim occluder/downskirt pair and publishes that UP list. Both
  heights on a span are load-bearing: collision `baseY` for holes, `visualBaseY` for draw.
- **Neighbour-overlap raise (a priority-class split).** A dilated rect owned by block A
  can extend across the top of a touching raised-outline block B — a path lip (`15/16`)
  reaching over soul sand (`14/16` collision, full-cube outline). The rect is a genuine
  A surface at A's `collisionTopY`, but where it overlaps B's footprint the paint
  would sit **inside B's taller mesh**. The raise lifts `visualTopY` to B's outline top
  **only on that intersection**, splitting the one rect into two pieces:
  `(collisionTopY_A, B.outlineTop)` over B and `(collisionTopY_A, collisionTopY_A)`
  elsewhere. The pieces enter different priority classes; raised claims overlapping
  flush geometry at merge, and their abutting boundary remains a class boundary.
  `collisionTopY` (hence all walkability) is untouched, and B's own exposed remnant
  keeps its own raise, so the covered face reads at one height.

### Known limitation — horizontal inset (not fixed)

Some blocks are inset **horizontally** while still rendering full width — notably
**honey** (`box(1,0,1,15,15,15)`, a `[1/16,15/16]` footprint under a full-cube
render), so its edges/skirts sit ~1px inside the visible face. Honey also has
**two height layers** (collision top `15/16`, full-height translucent/outline
body): with `visualTopY` the paint sits on the outer shell and the fill+honey
body read as stacked layers — see [`rendering.md`](rendering.md). The horizontal
inset is left **unfixed on purpose** because it is **Point-only**: a footprint
dilates by `W/2` per side, so the dilated edge clears the block face whenever
`W ≥ 2/16 = 0.125`, and
every real entity is far past that (smallest vanilla mobs ~`0.4`; all shipped
profiles `≥ 0.6`). Only the zero-width **Point** profile (debug)
keeps the inset footprint. Fixing it would need a full **visual footprint** (four
extra `StandableRect` coords + skirt re-draw + merge-seam handling) for a ~1px border
on a few blocks — poor cost/benefit. The `visualTopY` mechanism generalizes to a
visual footprint if it ever becomes worth it.

### Known limitation — Point profile (not fixed)

The zero-width **Point** profile (debug) has a few other edge-case
draw bugs that do not show up on the shipped dilated profiles. Left unfixed on
purpose due to low ROI. Is a zero-width point supposed to fit through a zero
size gap? Because a 4.0 wide ghast fits into a 4 block gap.

## Appendix A: rejected pixel raster

A pixel-mask rewrite (rebuild the per-block surface compute and the flood on an
integer `1/16`, then `1/32`-for-odd-width-parity grid, making dilation a
morphological op) was prototyped and **rejected**. Because hitboxes are
non-rotating axis-aligned squares, rect math already gives exact, closed-form
dilation (`W/2` is a clean fraction even for odd pixel widths, e.g. Ravager `31/16
→ 31/32`), so the raster added resampling cost, ring/seam artifacts, and a slower
flood (it stuttered on click) for **no accuracy gain**. The dead-end prototype lives
in branch history if ever needed. Keep surface/collision geometry in rect/double
space.
