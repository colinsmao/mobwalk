package dev.kelianmao.mobwalk.client.surface;

import dev.kelianmao.mobwalk.client.config.Configs;
import dev.kelianmao.mobwalk.client.config.Configs.AutoUpdate;
import dev.kelianmao.mobwalk.client.config.Configs.ShowSurfaces;
import dev.kelianmao.mobwalk.client.overlay.OverlayManager;
import dev.kelianmao.mobwalk.client.overlay.WorldOverlay;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.joml.Matrix4fc;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;

/**
 * Input/lifecycle driver for standable-surface selection: wand select/clear,
 * radius/profile re-flood, publish into {@code volatile} snapshots, and
 * visibility. Geometry emission is delegated to {@link SurfaceEmitter}.
 *
 * <p>The wand is a <b>trigger</b>: right-clicking floods the selection from
 * the block under the crosshair (resolved downward to the first non-empty
 * collision shape) — that block's raw dilated collision footprints are the
 * non-emitted click origin — outward across walkable, footprint-adjacent
 * surfaces (height steps within the profile's reach) up to a BFS depth limit
 * (adjustable via shift+scroll), into a persistent {@link SurfaceSelection},
 * replacing any previous selection; right-clicking nothing clears it. The
 * surfaces are occlusion-aware (computed in {@link SurfaceSelection#select}):
 * only tops not covered by something directly above are painted, so e.g. a
 * stair yields its exposed L and a fully occluded short top (Ravager on soul
 * sand) still originates a flood of neighbouring standable floor. By default
 * tops (and their skirts/beams) draw on each block's
 * <b>visible face</b> ({@code visualTopY}); Appearance {@code drawOnVisibleFace}
 * switches to the collision height, which re-floods since the visible top is
 * gathered compute-side (gated on the flag; see {@code WorldGeometry.visibleTop}).
 *
 * <p>A wand action or auto-update tick <b>arms</b> the flood and {@link #extract}
 * expands it under
 * the General flood budget ({@link #advanceFlood}), publishing the finished
 * selection into a {@code volatile} snapshot ({@link #publish}) on the frame it
 * completes; the previous selection stays drawn until that swap, and a new
 * trigger cancels whatever was in flight. A budget of {@code 0} means unlimited,
 * which finishes the flood on the frame after the click. Driving it from the
 * frame spends the budget evenly across frames, so a flood costs a uniform
 * frame-rate dip; {@link #emit} forwards the snapshot to {@link SurfaceEmitter}.
 */
public final class CollisionSurfaceOverlay implements WorldOverlay {
  // Cap the downward walk so looking at tall grass over a hole can't scan into
  // the void; resolution also stops at world min-Y.
  private static final int MAX_DOWNWARD_STEPS = 64;

  // Flood radius lives in Configs.Generic.FLOOD_RADIUS (slider + shift+scroll).
  // Past this the scroll steps by 2, keeping the high end usable.
  private static final int COARSE_RADIUS = 10;

  // General floodBudgetMs is in milliseconds; the job measures in nanoseconds.
  private static final long NANOS_PER_MS = 1_000_000L;

  // General floodBudgetMs uses 0 as the sentinel for "no limit" — the opposite of
  // SurfaceSelection.advance's 0, which buys the minimum of one step.
  private static final int UNLIMITED_BUDGET_MS = 0;

  // The computed surfaces, recomputed from scratch on each wand action
  // (onUseItem (re)selects/clears/cycles; radius/profile callbacks re-flood).
  private final SurfaceSelection cache = new SurfaceSelection();

  // Last resolved seed block so a radius/profile change can re-flood from the
  // same origin. Touched only on the client thread; null when nothing is selected.
  private BlockPos lastSeed;

  // Ticks since the last auto-update fire (or since the timer last reset).
  // Counts only while auto-update is eligible and no flood is in flight.
  private int idleTicks;

  // Active mob profile comes from Configs.mobProfile() (settings source of
  // truth). Sneak+air-click may cycle it when Debug crouchCycleProfile is on.

  // Last level seen on the extraction thread. A change (world unload, dimension
  // switch, disconnect/reconnect) empties the in-memory selection — a
  // self-contained alternative to a manager-side world-unload hook.
  private Level lastLevel;

  // Written on the extraction path, read on the render thread, so volatile.
  // Full draw gate (showSurfaces + profile + wand + non-empty snapshot) sampled
  // once per extract; isVisible() is a field read.
  private volatile boolean visible = false;
  // The opaque per-rect outlines are a debugging aid (they separate adjacent
  // surfaces and sub-rects) but clutter the normal view, so they only draw while
  // crouching. Sampled on the extraction thread, read in emit.
  private volatile boolean crouching = false;
  // The drawn selection: everything the last select produced, handed to the render
  // thread as one immutable object so a publish is a single reference write and emit
  // can never see new rects beside the previous selection's spans.
  private volatile SelectionSnapshot published = SelectionSnapshot.EMPTY;

  @Override
  public String id() {
    return "collision_surface";
  }

  @Override
  public void extract(LevelExtractionContext context) {
    Minecraft client = Minecraft.getInstance();
    Player player = client.player;
    Level level = client.level;

    // Extract owns the flood: a level-identity change (world unload, dimension
    // switch, reconnect) drops both the job and the drawn selection, then this
    // frame's slice of the budget goes to whatever is still in flight. Advancing
    // before the visibility sample lets a flood that completes here draw here.
    if (level != lastLevel) {
      cache.clear();
      lastSeed = null;
      lastLevel = level;
      published = SelectionSnapshot.EMPTY;
      idleTicks = 0;
    }
    advanceFlood();

    visible = surfacesShowable(player) && !published.isEmpty();
    // Through-walls tops + crouch borders share this flag; gated by Debug setting.
    crouching = player != null
      && player.isShiftKeyDown()
      && Configs.crouchSeeThroughWalls();
  }

  // Publish the current selection into the volatile snapshot emit() reads. Called
  // when a flood completes and after every clear, so per-frame work is nil.
  // Auto-update re-arms on its interval so world edits show without a re-click.
  private void publish() {
    published = cache.snapshot();
  }

  /**
   * Spend this frame's slice of the General flood budget on the selection in
   * flight, publishing it on the frame it completes (the previous one stays
   * drawn until then). Harmless with nothing armed.
   *
   * <p>Reading the option live each frame is what lets a budget change apply to
   * the flood already running, and it is the only place the setting's
   * {@code 0}-means-unlimited sentinel is translated for
   * {@link SurfaceSelection#advance}.
   *
   * <p>The same pass feeds the crosshair progress ring, so every way a flood ends
   * — completion, a re-arm, a clear, a level change — reaches the HUD through the
   * one null idle reading.
   */
  private void advanceFlood() {
    int budgetMs = Configs.floodBudgetMs();
    long budgetNanos = budgetMs == UNLIMITED_BUDGET_MS
      ? Long.MAX_VALUE
      : budgetMs * NANOS_PER_MS;
    if (cache.advance(budgetNanos)) {
      publish();
    }
    SurfaceSelection.FloodProgress progress = cache.progress();
    if (progress == null) {
      OverlayManager.floodProgress().hide();
    } else {
      OverlayManager.floodProgress().update(progress.expansion(), progress.passes());
    }
  }

  // Arm a flood from `start`; the frame driver expands it from there. At an
  // unlimited budget that is the next frame, which is the first one that could
  // have drawn it anyway, so the selection still lands in one step.
  private void armFlood(Level level, BlockPos start, EntityProfile profile) {
    cache.select(level, start, Configs.floodRadius(), profile, Configs.drawOnVisibleFace(),
      Configs.swimmableFluids(), Configs.fluidEscapeHeight());
  }

  // Walk down from the targeted block until a non-empty collision shape is
  // found, so pass-through blocks (tall grass, flowers) resolve to the solid
  // block beneath them. Capped and floored at world min-Y.
  private static BlockPos resolveDownward(Level level, BlockPos start) {
    BlockPos.MutableBlockPos cursor = start.mutable();
    int minY = level.getMinY();
    for (int step = 0; step < MAX_DOWNWARD_STEPS && cursor.getY() >= minY; step++) {
      if (!level.getBlockState(cursor).getCollisionShape(level, cursor, CollisionContext.empty()).isEmpty()) {
        return cursor.immutable();
      }
      cursor.move(0, -1, 0);
    }
    return null;
  }

  @Override
  public boolean isVisible() {
    return visible;
  }

  @Override
  public void onClientTick(Minecraft client) {
    if (Configs.autoUpdate() == AutoUpdate.DISABLED
        || !surfacesShowable(client.player)) {
      idleTicks = 0;
      return;
    }
    if (cache.isFlooding()) {
      idleTicks = 0;
      return;
    }
    Player player = client.player;
    Level level = client.level;
    if (player == null || level == null) {
      idleTicks = 0;
      return;
    }
    BlockPos seed = autoUpdateSeed(level, player);
    if (seed == null) {
      idleTicks = 0;
      return;
    }
    idleTicks++;
    int intervalTicks = Math.max(1, (int) Math.round(Configs.autoUpdateIntervalSeconds() * 20.0));
    if (idleTicks >= intervalTicks) {
      var profile = Configs.mobProfile();
      if (profile.isPresent()) {
        armFlood(level, seed, profile.get());
        lastSeed = seed;
      }
      idleTicks = 0;
    }
  }

  // Anchor reuses the last wand seed; Follow Player walks down from the feet
  // block (same resolveDownward as a click) so pass-through blocks land on
  // the collision underneath.
  private BlockPos autoUpdateSeed(Level level, Player player) {
    return switch (Configs.autoUpdate()) {
      case FROM_SEED -> lastSeed;
      case FROM_FEET -> resolveDownward(level, player.blockPosition());
      case DISABLED -> null;
    };
  }

  // Same showSurfaces + wand + profile tests as isVisible(), without requiring
  // a published selection, so Follow Player can self-start with an empty snapshot.
  private static boolean surfacesShowable(Player player) {
    Item wand = Configs.wandItem();
    boolean holding = player != null
      && (player.getMainHandItem().is(wand) || player.getOffhandItem().is(wand));
    ShowSurfaces mode = Configs.showSurfaces();
    return mode != ShowSurfaces.NEVER
      && Configs.hasEnabledProfile()
      && (mode == ShowSurfaces.ALWAYS || holding);
  }

  /**
   * Live apply from MaLiLib mob-profile / flood-radius / visible-face controls:
   * re-flood an active selection so the new size or radius shows immediately.
   */
  public void reselectWithMobProfile() {
    Level level = Minecraft.getInstance().level;
    var profile = Configs.mobProfile();
    if (level != null && lastSeed != null && profile.isPresent()) {
      armFlood(level, lastSeed, profile.get());
    }
  }

  /**
   * Drop any selection and the flood behind it, so draw stays off: used by a
   * soft-disabled roster and on disconnect.
   */
  public void clearSelection() {
    cache.clear();
    lastSeed = null;
    publish();
  }

  @Override
  public void onUseItem(Player player) {
    // Right-click with the wand floods the selection from the targeted block
    // (resolved downward; that block's raw dilated footprints are the click
    // origin) across connected neighbors, replacing the previous selection;
    // right-clicking nothing clears it.
    //
    // The wand may be in either hand. Pick the acting hand main-first, falling
    // back to the off hand ONLY when the main hand is empty (or also a wand):
    // a non-empty main hand is assumed to consume the right-click (place/use), so
    // an off-hand wand doesn't also fire — approximating vanilla's "main acts
    // first, off hand only if main did nothing" without an interaction-result
    // mixin (this is edge-detected off the use key, so the true result is unseen).
    Item wand = Configs.wandItem();
    InteractionHand hand;
    if (player.getMainHandItem().is(wand)) {
      hand = InteractionHand.MAIN_HAND;
    } else if (player.getOffhandItem().is(wand) && player.getMainHandItem().isEmpty()) {
      hand = InteractionHand.OFF_HAND;
    } else {
      return;
    }
    idleTicks = 0;

    Minecraft client = Minecraft.getInstance();
    Level level = client.level;
    BlockPos start = null;
    if (level != null) {
      HitResult hit = client.hitResult;
      if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
        start = resolveDownward(level, blockHit.getBlockPos());
      }
    }

    if (start != null) {
      if (!Configs.hasEnabledProfile()) {
        OverlayManager.radiusIndicator().showNoProfiles();
      } else {
        var profile = Configs.mobProfile();
        if (profile.isPresent()) {
          armFlood(level, start, profile.get());
          lastSeed = start;
        }
      }
    } else {
      // Right-click at nothing clears. Soft-disabled: HUD "no profiles active".
      // Otherwise when Debug crouchCycleProfile is on, sneaking advances the
      // roster profile and pings the HUD; lastSeed stays null so there is no
      // re-flood — the new profile takes effect on the next select.
      cache.clear();
      lastSeed = null;
      if (!Configs.hasEnabledProfile()) {
        OverlayManager.radiusIndicator().showNoProfiles();
      } else if (player.isShiftKeyDown() && Configs.crouchCycleProfile()) {
        if (Configs.cycleMobProfile().isPresent()) {
          OverlayManager.radiusIndicator().showProfile(
            Configs.profileDisplayLabel(Configs.activeProfileId())
          );
        }
      }
    }
    publish();
    // Play the default arm swing on this client. false skips the self-targeted swing packet.
    player.swing(hand, SwingAnimation.DEFAULT, false);
  }

  // True when scroll should retarget the flood radius instead of switching the
  // hotbar: Debug "crouch to scroll radius" enabled, holding the wand (either
  // hand), and crouching. When the option is off the feature is inactive — scroll
  // always leaves the hotbar alone to vanilla.
  public boolean wantsRadiusScroll() {
    if (!Configs.crouchScrollRadius()) {
      return false;
    }
    Player player = Minecraft.getInstance().player;
    Item wand = Configs.wandItem();
    return player != null
      && (player.getMainHandItem().is(wand) || player.getOffhandItem().is(wand))
      && player.isShiftKeyDown();
  }

  // Change the flood radius by delta (clamped) by writing Configs.FLOOD_RADIUS
  // (same option as the settings slider). The value-change callback re-floods.
  // Returns the new radius (for the on-screen indicator).
  public int adjustRadius(int delta) {
    // delta is a scroll direction (+/-1). Step by 2 in the coarse range so the
    // sequence is 0..10 by 1 then 12,14,..,20 (up: coarse once we reach the
    // threshold; down: coarse only while strictly above it, so 12->10->9).
    int current = Configs.floodRadius();
    int dir = Integer.signum(delta);
    int step = (dir > 0 ? current >= COARSE_RADIUS : current > COARSE_RADIUS) ? 2 : 1;
    int updated = current + dir * step;
    Configs.setFloodRadius(updated);
    return Configs.floodRadius();
  }

  /**
   * Flood geometry dump for {@code /mobwalk dump}: a pure read of the last
   * completed selection, logged with the parameters that produced it. Returns
   * null if there is no selection; otherwise the counts for the chat summary.
   */
  public FloodDebugCounts dumpFloodDebug() {
    SelectionSnapshot dumped = cache.snapshot();
    if (dumped.isEmpty()) {
      return null;
    }
    cache.dumpLastSelection();
    return new FloodDebugCounts(
      dumped.rects().size(),
      dumped.occluders().size(),
      dumped.downSkirts().size(),
      dumped.holes().size(),
      dumped.hazards().size());
  }

  /** Counts returned by {@link #dumpFloodDebug} for the chat status line. */
  public record FloodDebugCounts(int merged, int occluders, int skirts, int holes, int hazards) {
  }

  @Override
  public void emit(Matrix4fc positionMatrix, BufferBuilder fillBuffer, BufferBuilder skirtBuffer,
      BufferBuilder beamBuffer) {
    SurfaceEmitter.emit(positionMatrix, fillBuffer, skirtBuffer, beamBuffer,
      published, crouching);
  }
}
