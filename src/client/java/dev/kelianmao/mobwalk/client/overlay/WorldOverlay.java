package dev.kelianmao.mobwalk.client.overlay;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.joml.Matrix4fc;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;

/**
 * A drawable element rendered in the world (not the HUD).
 *
 * <p>Rendering in {@code 26.3} is split into an "extraction" phase (gather
 * immutable, thread-safe state) and a "drawing" phase (emit geometry). Widgets
 * read mutable game state in {@link #extract} and stash whatever they need,
 * then emit vertices in {@link #emit}. {@link WorldOverlayManager} owns the
 * shared render pipeline and GPU plumbing so widgets only describe geometry.
 */
public interface WorldOverlay {
  String id();

  /** Extraction phase: read game state and store an immutable snapshot. */
  void extract(LevelExtractionContext context);

  /**
   * Drawing phase: append quads to the shared buffers.
   *
   * <p>{@link WorldOverlayManager} maintains three {@code POSITION_COLOR} /
   * {@code QUADS} layers: {@code fillBuffer} is <b>depth-disabled</b> (tops /
   * borders through walls), {@code skirtBuffer} is <b>depth-tested</b>
   * (occluded by world geometry), and {@code beamBuffer} is <b>depth-disabled</b>
   * again but drawn <b>last</b> so hole beams composite over skirts.
   *
   * @param positionMatrix already translated so world coordinates are
   *                       camera-relative; pass absolute world coords.
   * @param fillBuffer     depth-disabled layer (tops/borders through walls).
   * @param skirtBuffer    depth-tested layer (occluded by world geometry).
   * @param beamBuffer     depth-disabled layer drawn after skirts (hole beams).
   */
  void emit(Matrix4fc positionMatrix, BufferBuilder fillBuffer, BufferBuilder skirtBuffer,
      BufferBuilder beamBuffer);

  default boolean isVisible() {
    return true;
  }

  /**
   * Called on the rising edge of the use key (right-click). The widget picks
   * which hand to act on itself (e.g. main-first with an off-hand fallback), so
   * the manager stays agnostic to what item a widget cares about.
   */
  default void onUseItem(Player player) {
  }

  /**
   * Called every client tick after the use-key rising-edge dispatch. Interval
   * work belongs here (tick-based, so it pauses with the game); frame-driven
   * extract stays on the render path.
   */
  default void onClientTick(Minecraft client) {
  }
}
