package dev.kelianmao.mobwalk.client.overlay;

import dev.kelianmao.mobwalk.client.surface.CollisionSurfaceOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import dev.kelianmao.mobwalk.MobWalk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Registry and render dispatch for {@link WorldOverlay} widgets.
 *
 * <p>Keeps all Fabric-API and low-level rendering contact in one place: it owns
 * a single filled {@code POSITION_COLOR}/{@code QUADS} pipeline and the GPU
 * buffer plumbing, batches every visible overlay into one draw, and releases
 * GPU resources on client shutdown. Widgets only implement {@link WorldOverlay}.
 */
public final class WorldOverlayManager {
  private static final List<WorldOverlay> OVERLAYS = new ArrayList<>();

  // Reuses vanilla's filled debug pipeline (QUADS + POSITION_COLOR). Depth test
  // is disabled (withDepthStencilState empty) so the flat tops/borders draw
  // THROUGH solid geometry — a debug aid that reveals surfaces buried inside
  // blocks (e.g. occlusion bugs).
  private static final RenderPipeline FILLED = RenderPipelines.register(
    RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
      .withLocation(Identifier.fromNamespaceAndPath(MobWalk.MOD_ID, "pipeline/world_overlay_filled"))
      .withDepthStencilState(Optional.empty())
      .build()
  );

  // Depth-tested layer for skirts + non-crouch tops. Keeps DEBUG_FILLED_SNIPPET's
  // depth state (LEQUAL, writeDepth=false). Drawn after translucent terrain so
  // ice/glass/honey stay in the color buffer and the half-alpha fill composites
  // on top; opaque terrain still occludes via the depth test.
  private static final RenderPipeline SKIRT = RenderPipelines.register(
    RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
      .withLocation(Identifier.fromNamespaceAndPath(MobWalk.MOD_ID, "pipeline/world_overlay_skirt"))
      .build()
  );

  private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
  private static final Vector3f MODEL_OFFSET = new Vector3f();
  private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

  // One GPU layer (own allocator + ring buffer) per pipeline role. Depth-off
  // FILLED carries tops/borders; depth-tested SKIRT carries vertical skirts;
  // depth-off BEAM carries hole beams and is drawn last so opaque beams cover
  // skirts (fill alone cannot — skirts always drew after fill).
  private static final Layer fillLayer = new Layer(FILLED);
  private static final Layer skirtLayer = new Layer(SKIRT);
  private static final Layer beamLayer = new Layer(FILLED);

  private static boolean usePressedLastTick = false;

  // The collision-surface widget instance, exposed so MobWalkClient's scroll
  // handler can adjust its flood radius.
  private static CollisionSurfaceOverlay collisionSurface;

  private WorldOverlayManager() {
  }

  public static void bootstrap() {
    collisionSurface = new CollisionSurfaceOverlay();
    register(collisionSurface);

    LevelExtractionEvents.END_EXTRACTION.register(WorldOverlayManager::extract);
    // Draw at END_MAIN, after vanilla closes its main render pass: the 26.3
    // command encoder allows one open pass, and the translucent-terrain
    // callbacks run inside vanilla's. Ice/glass/honey (classic or Improved
    // Transparency) are already in the color buffer, so our half-alpha fill
    // composites on top. Depth-tested SKIRT fails against water's written
    // depth, so pond bottoms need crouch (depth-off FILLED) to show through.
    LevelRenderEvents.END_MAIN.register(WorldOverlayManager::draw);
    // Free GPU resources at shutdown. We use CLIENT_STOPPING rather than a
    // GameRenderer#close mixin to avoid mixin plumbing; trade-off: buffers
    // are freed at shutdown, not on a mid-session renderer reload.
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
    ClientTickEvents.END_CLIENT_TICK.register(WorldOverlayManager::onClientTick);
  }

  // Dispatch a use only on the rising edge of the "use item" key, so holding the
  // button does not spam. Deliberately not Fabric's UseItemCallback: that
  // re-fires every tick while held for items with no use cooldown (e.g. a stick).
  private static void onClientTick(Minecraft client) {
    boolean down = client.gui.screen() == null && client.options.keyUse.isDown();
    if (down && !usePressedLastTick && client.player != null) {
      for (WorldOverlay overlay : OVERLAYS) {
        overlay.onUseItem(client.player);
      }
    }
    usePressedLastTick = down;
    for (WorldOverlay overlay : OVERLAYS) {
      overlay.onClientTick(client);
    }
  }

  public static void register(WorldOverlay overlay) {
    OVERLAYS.add(overlay);
  }

  public static CollisionSurfaceOverlay collisionSurface() {
    return collisionSurface;
  }

  private static void extract(LevelExtractionContext context) {
    for (WorldOverlay overlay : OVERLAYS) {
      overlay.extract(context);
    }
  }

  private static void draw(LevelRenderContext context) {
    List<WorldOverlay> visible = new ArrayList<>();
    for (WorldOverlay overlay : OVERLAYS) {
      if (overlay.isVisible()) {
        visible.add(overlay);
      }
    }
    if (visible.isEmpty()) {
      return;
    }

    PoseStack matrices = context.poseStack();
    Vec3 camera = context.levelState().cameraRenderState.pos;

    // Overlays emit vertices in absolute world coords; translate by -camera
    // to make them camera-relative, matching the world renderer.
    matrices.pushPose();
    matrices.translate(-camera.x, -camera.y, -camera.z);

    Matrix4fc pose = matrices.last().pose();
    BufferBuilder fill = fillLayer.begin();
    BufferBuilder skirt = skirtLayer.begin();
    BufferBuilder beam = beamLayer.begin();
    for (WorldOverlay overlay : visible) {
      overlay.emit(pose, fill, skirt, beam);
    }

    matrices.popPose();

    // Fill (depth-off tops) → skirts (depth-tested) → beams (depth-off, last):
    // beams composite over skirts so opaque hole beams are not overdrawn.
    // One RenderPass for every layer: 26.3's command encoder rejects
    // createRenderPass while a pass is still open.
    Minecraft client = Minecraft.getInstance();
    Batch fillBatch = null;
    Batch skirtBatch = null;
    Batch beamBatch = null;
    // Ring-buffer rotate() creates a fence, which the encoder rejects while any
    // pass is open, so cleanup rotates only once our pass has closed.
    boolean passClosed = true;
    try {
      fillBatch = prepare(fillLayer);
      skirtBatch = prepare(skirtLayer);
      beamBatch = prepare(beamLayer);
      if (fillBatch == null && skirtBatch == null && beamBatch == null) {
        return;
      }

      // Snapshot the model-view instead of passing the live shared Matrix4fStack:
      // the dynamic-transform write must not entangle our world pass with vanilla's
      // singleton stack, or later first-person screen overlays (fire, etc.) inherit
      // our camera transform and render world-anchored instead of on the screen.
      Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewStack());
      GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
        .writeTransform(modelView, COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
      passClosed = false;
      try (RenderPass renderPass = RenderSystem.getDevice()
          .createCommandEncoder()
          .createRenderPass(
            () -> MobWalk.MOD_ID + " world overlay rendering",
            client.gameRenderer.mainRenderTarget().getColorTextureView(), Optional.empty(),
            client.gameRenderer.mainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
        drawBatch(renderPass, fillBatch, dynamicTransforms);
        drawBatch(renderPass, skirtBatch, dynamicTransforms);
        drawBatch(renderPass, beamBatch, dynamicTransforms);
      }
      passClosed = true;
    } finally {
      closeBatch(fillBatch, passClosed);
      closeBatch(skirtBatch, passClosed);
      closeBatch(beamBatch, passClosed);
    }
  }

  // build() returns null when nothing was emitted into this layer's buffer;
  // reset the builder regardless so next frame starts fresh.
  private static Batch prepare(Layer layer) {
    MeshData builtBuffer = layer.buffer.build();
    layer.buffer = null;
    if (builtBuffer == null) {
      return null;
    }

    MeshData.DrawState drawParameters = builtBuffer.drawState();
    VertexFormat format = drawParameters.format();
    GpuBuffer vertices = upload(layer, drawParameters, format, builtBuffer);

    GpuBuffer indices;
    IndexType indexType;
    boolean closeIndices = false;
    RenderPipeline pipeline = layer.pipeline;
    if (pipeline.getPrimitiveTopology() == PrimitiveTopology.QUADS) {
      builtBuffer.sortQuads(layer.allocator, RenderSystem.getProjectionType().vertexSorting());
      indices = RenderSystem.getDevice().createBuffer(
        () -> MobWalk.MOD_ID + " world overlay indices",
        GpuBuffer.USAGE_INDEX,
        builtBuffer.indexBuffer()
      );
      closeIndices = true;
      indexType = builtBuffer.drawState().indexType();
    } else {
      RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.getPrimitiveTopology());
      indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
      indexType = shapeIndexBuffer.type();
    }

    return new Batch(layer, builtBuffer, drawParameters, vertices, indices, indexType, closeIndices);
  }

  private static void drawBatch(RenderPass renderPass, Batch batch, GpuBufferSlice dynamicTransforms) {
    if (batch == null) {
      return;
    }
    renderPass.setPipeline(RenderSystem.getCompiledPipeline(batch.layer.pipeline));
    RenderSystem.bindDefaultUniforms(renderPass);
    renderPass.setUniform("DynamicTransforms", dynamicTransforms);
    renderPass.setVertexBuffer(0, batch.vertices.slice());
    renderPass.setIndexBuffer(batch.indices, batch.indexType);
    renderPass.drawIndexed(batch.drawParameters.indexCount(), 1, 0, 0, 0);
  }

  // Skipping rotate() after a failed draw reuses the current ring slot next
  // frame; that only costs a fence wait, and it keeps cleanup from throwing
  // over the original exception.
  private static void closeBatch(Batch batch, boolean rotate) {
    if (batch == null) {
      return;
    }
    if (batch.closeIndices) {
      batch.indices.close();
    }
    batch.builtBuffer.close();
    if (rotate) {
      batch.layer.vertexBuffer.rotate();
    }
  }

  private static GpuBuffer upload(Layer layer, MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
    int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

    if (layer.vertexBuffer == null || layer.vertexBuffer.size() < vertexBufferSize) {
      if (layer.vertexBuffer != null) {
        layer.vertexBuffer.close();
      }

      layer.vertexBuffer = new MappableRingBuffer(
        () -> MobWalk.MOD_ID + " world overlay pipeline",
        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
        vertexBufferSize
      );
    }

    try (GpuBufferSlice.MappedView mappedView = layer.vertexBuffer.currentBuffer()
        .slice(0, builtBuffer.vertexBuffer().remaining())
        .map(false, true)) {
      MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
    }

    return layer.vertexBuffer.currentBuffer();
  }

  private static void close() {
    fillLayer.close();
    skirtLayer.close();
    beamLayer.close();
  }

  // A single GPU draw layer: its pipeline, a private vertex allocator/builder,
  // and a ring buffer sized to its largest batch. The builder is rebuilt each
  // frame (see prepare); the allocator and ring buffer are reused.
  private static final class Layer {
    final RenderPipeline pipeline;
    final ByteBufferBuilder allocator = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    BufferBuilder buffer;
    MappableRingBuffer vertexBuffer;

    Layer(RenderPipeline pipeline) {
      this.pipeline = pipeline;
    }

    BufferBuilder begin() {
      if (buffer == null) {
        buffer = new BufferBuilder(allocator, pipeline.getPrimitiveTopology(), pipeline.getVertexFormatBinding(0));
      }
      return buffer;
    }

    void close() {
      allocator.close();
      if (vertexBuffer != null) {
        vertexBuffer.close();
        vertexBuffer = null;
      }
    }
  }

  // One layer's uploaded geometry for the shared render pass.
  private static final class Batch {
    final Layer layer;
    final MeshData builtBuffer;
    final MeshData.DrawState drawParameters;
    final GpuBuffer vertices;
    final GpuBuffer indices;
    final IndexType indexType;
    final boolean closeIndices;

    Batch(Layer layer, MeshData builtBuffer, MeshData.DrawState drawParameters, GpuBuffer vertices,
        GpuBuffer indices, IndexType indexType, boolean closeIndices) {
      this.layer = layer;
      this.builtBuffer = builtBuffer;
      this.drawParameters = drawParameters;
      this.vertices = vertices;
      this.indices = indices;
      this.indexType = indexType;
      this.closeIndices = closeIndices;
    }
  }
}
