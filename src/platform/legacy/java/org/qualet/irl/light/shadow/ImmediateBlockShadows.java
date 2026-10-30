package org.qualet.irl.light.shadow;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShapes;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/** Per-light block geometry cache and depth draw pass. */
final class ImmediateBlockShadows
{
    private final Matrix4f view;
    private final Matrix4f projection;
    private final DoubleSupplier originX;
    private final DoubleSupplier originY;
    private final DoubleSupplier originZ;
    private final BooleanSupplier active;
    private final Runnable restoreCull;
    private final MatrixStack scratch = new MatrixStack();



    private boolean blockRenderErrorLogged = false;
    private final Random cutoutRandom = Random.create();

    // --- Per-light block VBO cache (Stage B), keyed by LightRegistry.id ---
    // The block list from BlockShadowCache is referentially stable on a cache
    // hit (same List instance), so a reference compare detects a rebuild
    // perfectly. One VertexBuffer per lamp, built once and redrawn across all
    // 6 cube faces (point) / the single atlas tile (spot). Static lamps
    // re-upload nothing. Evicted by retainBlockVbos when the lamp disappears.
    private final Long2ObjectOpenHashMap<VertexBuffer> blockVboById = new Long2ObjectOpenHashMap<>();
    // A matching list with a null VBO caches "no shaped entries" as well.
    private final Long2ObjectOpenHashMap<List<BlockShadowEntry>> blockVboListById = new Long2ObjectOpenHashMap<>();

    // --- Per-light cutout block VBO cache (T2.3), keyed by LightRegistry.id ---
    // Cutout blocks (doors, leaves, glass panes, iron bars) were re-tessellated
    // through brm.renderBlock on every static bake — and once PER CUBE FACE for
    // point lights, so a point lamp paid 6x that CPU cost each bake. Capture them
    // once into a textured VBO per render layer (CUTOUT / CUTOUT_MIPPED), keyed
    // by id + the referentially-stable block-list instance, then redraw the VBO
    // under that layer's own cutout shader + block atlas (alpha-discard intact)
    // for every face / tile. Rebuilt only when the list instance changes; evicted
    // alongside the opaque VBOs in retainBlockVbos. NOTE: the captured atlas UVs
    // would go stale on a resource-pack reload that repacks the atlas without any
    // block change — rare, out of the perf scope, and recovered by any later edit.
    private static final class CutoutVbos
    {
        VertexBuffer cutout;        // blocks on RenderLayer.getCutout()
        VertexBuffer cutoutMipped;  // blocks on RenderLayer.getCutoutMipped()

        void close()
        {
            if (cutout != null) { try { cutout.close(); } catch (Throwable ignored) {} cutout = null; }
            if (cutoutMipped != null) { try { cutoutMipped.close(); } catch (Throwable ignored) {} cutoutMipped = null; }
        }
    }

    private final Long2ObjectOpenHashMap<CutoutVbos> cutoutVboById = new Long2ObjectOpenHashMap<>();
    // A matching list with null VBOs caches "no cutout entries" as well.
    private final Long2ObjectOpenHashMap<List<BlockShadowEntry>> cutoutVboListById = new Long2ObjectOpenHashMap<>();

    ImmediateBlockShadows(Matrix4f view, Matrix4f projection,
        DoubleSupplier originX, DoubleSupplier originY,
        DoubleSupplier originZ, BooleanSupplier active, Runnable restoreCull)
    {
        this.view = view;
        this.projection = projection;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.active = active;
        this.restoreCull = restoreCull;
    }

    /**
     * Render a light's non-full-cube blocks into the currently-bound depth FBO,
     * between a begin*()/endPass() bracket and after the entity casters. Two
     * paths: cutout blocks bake from their textured BakedModel (alpha-test
     * shader drops transparent texels), opaque shapes draw as a cached
     * POSITION-only quad-box VBO decomposed from their VoxelShape AABBs.
     */
    public void renderBlocksDepth(long id, List<BlockShadowEntry> blocks)
    {
        if (blocks == null || blocks.isEmpty() || !this.active.getAsBoolean())
        {
            return;
        }

        // Cutout blocks first (their own textured pass), then opaque AABBs.
        this.renderBlocksDepthCutout(id, blocks);

        VertexBuffer vb = this.blockVboById.get(id);
        boolean sameBlocks = this.blockVboListById.get(id) == blocks;
        if (!sameBlocks)
        {
            boolean anyShape = false;
            for (int i = 0, n = blocks.size(); i < n; i++)
            {
                BlockShadowEntry e = blocks.get(i);
                if (e != null && e.shape != null)
                {
                    anyShape = true;
                    break;
                }
            }
            if (!anyShape)
            {
                // Cache the absence too: point faces must not rescan a cutout-
                // only list six times. Keep a map entry so normal eviction owns it.
                this.releaseBlockVbo(id);
                this.blockVboById.put(id, null);
                this.blockVboListById.put(id, blocks);
                return;
            }
        }
        else if (vb == null)
        {
            return;
        }

        ShadowBakeState.setBaking(true);
        boolean disabledCull = false;
        try
        {
            // Culling OFF: both faces of every quad write depth and the depth test
            // keeps the nearest (light-facing) surface — the true occluder — so
            // blocks cast tight, correct shadows (matches the proven IRLEngine bake).
            RenderSystem.disableCull();
            disabledCull = true;
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            NativeLegacyRender.positionShader();

            // Rebuild only when the list instance changed (BlockShadowCache
            // returns the same instance on a hit) — static lamps just redraw.
            if (!sameBlocks)
            {
                if (vb != null)
                {
                    try { vb.close(); } catch (Throwable ignored) {}
                }
                vb = this.buildBlockVbo(blocks);
                this.blockVboById.put(id, vb);
                this.blockVboListById.put(id, blocks);
            }

            if (vb != null)
            {
                vb.bind();
                // Draw with the LIGHT's own view/proj, NOT RenderSystem's live
                // matrices. A caster baked earlier in this pass — notably a model
                // block whose form is a vanilla mob, drawn through the vanilla
                // EntityRenderer — can leave RenderSystem's modelview changed; the
                // block batch would then transform wrong and land nothing in the
                // depth map, so the light's block shadows silently vanished (no GL
                // error). this.view/this.projection are set in applyMatrices.
                vb.draw(this.view, this.projection, RenderSystem.getShader());
                VertexBuffer.unbind();
            }
        }
        catch (Throwable t)
        {
            if (!this.blockRenderErrorLogged)
            {
                this.blockRenderErrorLogged = true;
                System.err.println("[irlite] renderBlocksDepth failed: " + t);
                t.printStackTrace();
            }
            // Drop the (possibly broken) VBO so the next frame retries clean.
            this.releaseBlockVbo(id);
        }
        finally
        {
            if (disabledCull)
            {
                this.restoreCull.run();
            }
            ShadowBakeState.setBaking(false);
        }
    }

    /** Build a STATIC POSITION VBO from a block list's shaped entries. Only
     *  called when at least one entry has a shape, so the buffer is non-empty. */
    private VertexBuffer buildBlockVbo(List<BlockShadowEntry> blocks)
    {
        // 1.21: begin() moved to Tessellator and returns the builder.
        BufferBuilder buf = NativeLegacyRender.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);

        QuadBoxConsumer consumer = new QuadBoxConsumer(buf);
        // Light-relative bake: the consumer subtracts the pass anchor in double
        // before its float cast. The anchor A == BlockShadowCache's snap, so it is
        // stable for the life of this cached VBO (rebuilt only when the block list
        // instance — hence A — changes), keeping the VBO consistent with the eye.
        consumer.originX = this.originX.getAsDouble();
        consumer.originY = this.originY.getAsDouble();
        consumer.originZ = this.originZ.getAsDouble();
        for (int i = 0, n = blocks.size(); i < n; i++)
        {
            BlockShadowEntry entry = blocks.get(i);
            if (entry == null || entry.shape == null)
            {
                continue;
            }
            consumer.ox = entry.pos.getX();
            consumer.oy = entry.pos.getY();
            consumer.oz = entry.pos.getZ();
            entry.shape.forEachBox(consumer);
        }

        return NativeLegacyRender.upload(buf);
    }

    /** Free one lamp's cached block VBO. */
    public void releaseBlockVbo(long id)
    {
        VertexBuffer vb = this.blockVboById.remove(id);
        if (vb != null)
        {
            try { vb.close(); } catch (Throwable ignored) {}
        }
        this.blockVboListById.remove(id);
    }

    /** Free block VBOs for lamps not in {@code liveIds} (gone, or feature off
     *  -> empty set drains all). Run once per bake after the light loops. */
    public void retainBlockVbos(LongSet liveIds)
    {
        if (!this.blockVboById.isEmpty())
        {
            ObjectIterator<Long2ObjectMap.Entry<VertexBuffer>> it = this.blockVboById.long2ObjectEntrySet().iterator();
            while (it.hasNext())
            {
                Long2ObjectMap.Entry<VertexBuffer> me = it.next();
                if (!liveIds.contains(me.getLongKey()))
                {
                    VertexBuffer vb = me.getValue();
                    if (vb != null)
                    {
                        try { vb.close(); } catch (Throwable ignored) {}
                    }
                    this.blockVboListById.remove(me.getLongKey());
                    it.remove();
                }
            }
        }

        // Same eviction for the cutout VBOs (a lamp with only cutout blocks has
        // no opaque VBO, so this can't be folded into the loop above).
        if (!this.cutoutVboById.isEmpty())
        {
            ObjectIterator<Long2ObjectMap.Entry<CutoutVbos>> it = this.cutoutVboById.long2ObjectEntrySet().iterator();
            while (it.hasNext())
            {
                Long2ObjectMap.Entry<CutoutVbos> me = it.next();
                if (!liveIds.contains(me.getLongKey()))
                {
                    CutoutVbos v = me.getValue();
                    if (v != null)
                    {
                        v.close();
                    }
                    this.cutoutVboListById.remove(me.getLongKey());
                    it.remove();
                }
            }
        }
    }

    // Cutout blocks bake from their textured BakedModel through vanilla's
    // alpha-test cutout shader so transparent texture pixels (door glass, iron
    // grates, ladder gaps, leaves) let light pass through. The tessellated
    // geometry is cached per light in a VBO per render layer (T2.3) and redrawn
    // each face / tile under that layer's cutout shader: startDrawing binds the
    // block atlas to Sampler0 and sets the cutout(_mipped) shader whose FS does
    // the alpha discard; colour writes land on no attachment in our depth-only
    // FBO, so only depth is recorded.
    private void renderBlocksDepthCutout(long id, List<BlockShadowEntry> blocks)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null)
        {
            return;
        }

        BlockRenderManager brm = mc.getBlockRenderManager();
        if (brm == null)
        {
            return;
        }

        CutoutVbos vbos = this.cutoutVboById.get(id);
        boolean sameBlocks = this.cutoutVboListById.get(id) == blocks;
        if (!sameBlocks)
        {
            boolean any = false;
            for (int i = 0, n = blocks.size(); i < n; i++)
            {
                BlockShadowEntry e = blocks.get(i);
                if (e != null && e.cutout)
                {
                    any = true;
                    break;
                }
            }
            if (!any)
            {
                this.releaseCutoutVbos(id);
                this.cutoutVboById.put(id, null);
                this.cutoutVboListById.put(id, blocks);
                return;
            }
        }
        else if (vbos == null)
        {
            return;
        }

        // Tessellate once per (id, list instance); reuse across all 6 cube faces
        // / the single atlas tile and across static bakes while the list is
        // stable (BlockShadowCache returns the same instance until a block in
        // range changes), so a static lamp re-tessellates nothing.
        if (!sameBlocks)
        {
            if (vbos != null)
            {
                vbos.close();
            }
            ShadowBakeState.setBaking(true);
            try
            {
                vbos = this.buildCutoutVbos(blocks, world, brm);
            }
            catch (Throwable t)
            {
                if (!this.blockRenderErrorLogged)
                {
                    this.blockRenderErrorLogged = true;
                    System.err.println("[irlite] buildCutoutVbos failed: " + t);
                    t.printStackTrace();
                }
                this.releaseCutoutVbos(id);
                return;
            }
            finally
            {
                ShadowBakeState.setBaking(false);
            }
            this.cutoutVboById.put(id, vbos);
            this.cutoutVboListById.put(id, blocks);
        }

        // Redraw the cached VBOs. vb.draw is handed the LIGHT's own view/proj
        // explicitly, so — unlike the old immediate.draw path — it never reads
        // RenderSystem's live modelview, which a vanilla-mob caster baked earlier
        // in this pass can leave corrupted (that was the original "a second cutout
        // layer makes the others' shadows vanish" bug; gone here by construction).
        try
        {
            this.drawCutoutVbo(RenderLayer.getCutout(), vbos.cutout);
            this.drawCutoutVbo(RenderLayer.getCutoutMipped(), vbos.cutoutMipped);
        }
        catch (Throwable t)
        {
            if (!this.blockRenderErrorLogged)
            {
                this.blockRenderErrorLogged = true;
                System.err.println("[irlite] drawCutoutVbo failed: " + t);
                t.printStackTrace();
            }
        }
    }

    /** Tessellate a light's cutout blocks into one STATIC textured VBO per
     *  cutout render layer (CUTOUT / CUTOUT_MIPPED). */
    private CutoutVbos buildCutoutVbos(List<BlockShadowEntry> blocks, ClientWorld world, BlockRenderManager brm)
    {
        CutoutVbos out = new CutoutVbos();
        out.cutout = this.buildCutoutLayerVbo(blocks, world, brm, RenderLayer.getCutout());
        out.cutoutMipped = this.buildCutoutLayerVbo(blocks, world, brm, RenderLayer.getCutoutMipped());
        return out;
    }

    /** Tessellate the cutout blocks that map to ONE render layer into a STATIC
     *  VBO in that layer's textured vertex format, exactly as the old immediate
     *  path did (cull on, per-block render seed). Returns null if no block maps
     *  to this layer or they all tessellate to nothing (e.g. fully neighbour-
     *  culled), so the redraw skips a missing layer. */
    private VertexBuffer buildCutoutLayerVbo(List<BlockShadowEntry> blocks, ClientWorld world, BlockRenderManager brm, RenderLayer layer)
    {
        // 1.21: begin() moved to Tessellator and returns the builder.
        BufferBuilder buf = NativeLegacyRender.begin(layer.getDrawMode(), layer.getVertexFormat());

        this.resetScratch();
        MatrixStack stack = this.scratch;
        for (int i = 0, n = blocks.size(); i < n; i++)
        {
            BlockShadowEntry entry = blocks.get(i);
            if (entry == null || !entry.cutout)
            {
                continue;
            }
            BlockPos p = entry.pos;
            BlockState state = world.getBlockState(p);
            if (state.isAir())
            {
                continue;
            }

            RenderLayer bl;
            try
            {
                bl = RenderLayers.getBlockLayer(state);
            }
            catch (Throwable t)
            {
                continue;
            }
            if (bl != layer)
            {
                continue;
            }

            stack.push();
            // Light-relative bake: int block coord minus the double pass anchor is
            // a small double, cast to float exactly inside MatrixStack.translate.
            stack.translate(p.getX() - this.originX.getAsDouble(), p.getY() - this.originY.getAsDouble(), p.getZ() - this.originZ.getAsDouble());
            this.cutoutRandom.setSeed(state.getRenderingSeed(p));
            try
            {
                brm.renderBlock(state, p, world, stack, buf, true, this.cutoutRandom);
            }
            catch (Throwable t)
            {
                // skip a single broken block, keep tessellating the rest
            }
            stack.pop();
        }

        return NativeLegacyRender.upload(buf);
    }

    /** Draw one cached cutout layer VBO under that layer's render state.
     *  startDrawing/endDrawing replicate exactly what immediate.draw uses for
     *  the layer — cutout shader, block atlas on Sampler0, blend off, cull on —
     *  so transparent texels still discard; only the light's own view/proj reach
     *  the shader (corruption-proof). The depth-only FBO drops the colour writes. */
    private void drawCutoutVbo(RenderLayer layer, VertexBuffer vb)
    {
        if (vb == null)
        {
            return;
        }
        layer.startDrawing();
        try
        {
            vb.bind();
            vb.draw(this.view, this.projection, RenderSystem.getShader());
            VertexBuffer.unbind();
        }
        finally
        {
            layer.endDrawing();
        }
    }

    /** Free one lamp's cached cutout VBOs. */
    private void releaseCutoutVbos(long id)
    {
        CutoutVbos v = this.cutoutVboById.remove(id);
        if (v != null)
        {
            v.close();
        }
        this.cutoutVboListById.remove(id);
    }

    /**
     * Reusable forEachBox consumer that emits one block-aligned VoxelShape AABB
     * as 6 world-space quads. The block origin (ox, oy, oz) is mutated per
     * block; the shape's box coords are local (0..1). Winding is irrelevant —
     * culling is disabled, so both faces of each quad write depth.
     */
    private static final class QuadBoxConsumer implements VoxelShapes.BoxConsumer
    {
        final BufferBuilder buf;
        int ox, oy, oz;
        /** Light-relative bake anchor (= ShadowRenderer.currentOrigin* when the
         *  VBO was built), subtracted in double before the float cast so the
         *  emitted quad coords stay sub-block-magnitude far from the origin. */
        double originX, originY, originZ;

        QuadBoxConsumer(BufferBuilder buf)
        {
            this.buf = buf;
        }

        @Override
        public void consume(double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ)
        {
            float x1 = (float) (ox - this.originX + minX);
            float y1 = (float) (oy - this.originY + minY);
            float z1 = (float) (oz - this.originZ + minZ);
            float x2 = (float) (ox - this.originX + maxX);
            float y2 = (float) (oy - this.originY + maxY);
            float z2 = (float) (oz - this.originZ + maxZ);

            // -X  (1.21: VertexConsumer.next() removed — each vertex() starts a new vertex)
            NativeLegacyRender.vertex(buf, x1, y1, z1);
            NativeLegacyRender.vertex(buf, x1, y1, z2);
            NativeLegacyRender.vertex(buf, x1, y2, z2);
            NativeLegacyRender.vertex(buf, x1, y2, z1);
            // +X
            NativeLegacyRender.vertex(buf, x2, y1, z2);
            NativeLegacyRender.vertex(buf, x2, y1, z1);
            NativeLegacyRender.vertex(buf, x2, y2, z1);
            NativeLegacyRender.vertex(buf, x2, y2, z2);
            // -Y
            NativeLegacyRender.vertex(buf, x1, y1, z2);
            NativeLegacyRender.vertex(buf, x1, y1, z1);
            NativeLegacyRender.vertex(buf, x2, y1, z1);
            NativeLegacyRender.vertex(buf, x2, y1, z2);
            // +Y
            NativeLegacyRender.vertex(buf, x1, y2, z1);
            NativeLegacyRender.vertex(buf, x1, y2, z2);
            NativeLegacyRender.vertex(buf, x2, y2, z2);
            NativeLegacyRender.vertex(buf, x2, y2, z1);
            // -Z
            NativeLegacyRender.vertex(buf, x2, y1, z1);
            NativeLegacyRender.vertex(buf, x1, y1, z1);
            NativeLegacyRender.vertex(buf, x1, y2, z1);
            NativeLegacyRender.vertex(buf, x2, y2, z1);
            // +Z
            NativeLegacyRender.vertex(buf, x1, y1, z2);
            NativeLegacyRender.vertex(buf, x2, y1, z2);
            NativeLegacyRender.vertex(buf, x2, y2, z2);
            NativeLegacyRender.vertex(buf, x1, y2, z2);
        }
    }

    private void resetScratch()
    {
        while (!this.scratch.isEmpty())
        {
            this.scratch.pop();
        }
        this.scratch.loadIdentity();
    }

}
