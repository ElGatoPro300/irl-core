package org.qualet.irl.light.shadow;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import org.joml.Matrix4f;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/** Per-light block geometry cache and depth draw pass. */
final class RawBlockShadows
{
    private final Matrix4f view;
    private final Matrix4f projection;
    private final DoubleSupplier originX;
    private final DoubleSupplier originY;
    private final DoubleSupplier originZ;
    private final BooleanSupplier active;
    private final RawDepthPrograms programs;
    private final Matrix4f viewProj = new Matrix4f();



    // --- Block-shadow depth bake (raw-GL) -------------------------------------

    /** Per-light cached VBO of triangle vertices (POSITION). Rebuilt only when the
     *  block list instance changes ({@link BlockShadowCache} returns a stable
     *  instance until a block in range changes), so static lamps just redraw. */
    private final Long2ObjectOpenHashMap<List<BlockShadowEntry>> vboList = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap vboId = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap vboVertCount = new Long2IntOpenHashMap();

    // --- Cutout (textured, alpha-tested) block depth program + per-light cache ---
    // Cutout blocks (leaves / glass panes / iron bars / doors / foliage; shape ==
    // null in the entry) are baked from their BakedModel quads through a second
    // depth program that samples the block atlas and discards transparent texels,
    // so light passes through the holes. Vertices are interleaved POSITION(3) +
    // UV(2) (stride 20). Per-light VBO cached + rebuilt on a list-instance change,
    // exactly like the opaque path; evicted alongside it in retainBlockVbos. The
    // CUTOUT and CUTOUT_MIPPED layers of the 1.21.4 line collapse into the single
    // BlockRenderLayer.CUTOUT the collector classifies, so there is ONE cutout VBO
    // per light here (the 1.21.4 line kept two, one per layer object).
    private final Long2ObjectOpenHashMap<List<BlockShadowEntry>> cutoutList = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap cutoutVboId = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap cutoutVertCount = new Long2IntOpenHashMap();
    private final Random cutoutRandom = Random.create();
    private boolean cutoutErrorLogged = false;

    private boolean blockErrorLogged = false;

    RawBlockShadows(Matrix4f view, Matrix4f projection,
        DoubleSupplier originX, DoubleSupplier originY,
        DoubleSupplier originZ, BooleanSupplier active, RawDepthPrograms programs)
    {
        this.view = view;
        this.projection = projection;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.active = active;
        this.programs = programs;
    }

    /**
     * Render a light's block occluders into the currently-bound depth FBO, between
     * a begin*()/endPass() bracket and after the entity casters. Opaque-shape
     * blocks are drawn as their {@link BlockShadowEntry#shape} AABB triangles;
     * cutout blocks (shape == null) are drawn from their real MC-tessellated
     * textured geometry with alpha discard (see {@link #renderBlocksDepthCutout}).
     * Both use the per-light view/proj.
     */
    public void renderBlocksDepth(long id, List<BlockShadowEntry> blocks)
    {
        if (blocks == null || blocks.isEmpty() || !this.active.getAsBoolean() || this.programs.glBroken)
        {
            return;
        }
        if (!this.programs.glInit)
        {
            this.programs.initGl();
        }
        if (this.programs.glBroken)
        {
            return;
        }
        this.drawOpaqueBlocks(id, blocks);
        this.renderBlocksDepthCutout(id, blocks);
    }

    /** Draw the opaque-shape (non-cutout) blocks of a light as triangle AABBs. */
    private void drawOpaqueBlocks(long id, List<BlockShadowEntry> blocks)
    {
        if (this.programs.program == 0)
        {
            return;
        }

        // (Re)build this light's VBO only when its block list instance changed.
        // Keyed on the list instance alone (not vbo != 0): buildVbo stores the
        // instance even when there is no shaped geometry (a cutout-only lamp), so
        // gating on vbo would re-tessellate that empty lamp every frame.
        int verts;
        if (this.vboList.get(id) != blocks)
        {
            verts = this.buildVbo(id, blocks);
        }
        else
        {
            verts = this.vboVertCount.get(id);
        }
        if (verts <= 0)
        {
            return;
        }
        int vbo = this.vboId.get(id);

        // Snapshot every global GL state the draw mutates, then restore it — MC's
        // GlStateManager doesn't cache program/VAO/buffer, and the FBO/viewport are
        // restored by endPass, so this keeps the surrounding renderer consistent.
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        try
        {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            // Cull OFF: both faces of every box triangle write depth and the depth
            // test keeps the nearest (light-facing) surface — tight, correct block
            // silhouettes regardless of winding.
            GL11.glDisable(GL11.GL_CULL_FACE);

            GL20.glUseProgram(this.programs.program);
            this.projection.mul(this.view, this.viewProj);
            try (MemoryStack stack = MemoryStack.stackPush())
            {
                FloatBuffer fb = stack.mallocFloat(16);
                this.viewProj.get(fb);
                GL20.glUniformMatrix4fv(this.programs.uViewProjLoc, false, fb);
            }

            GL30.glBindVertexArray(this.programs.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);

            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verts);
        }
        catch (Throwable t)
        {
            if (!this.blockErrorLogged)
            {
                this.blockErrorLogged = true;
                System.err.println("[irl-core] block shadow bake failed: " + t);
                t.printStackTrace();
            }
        }
        finally
        {
            // Restore exactly what we changed (actual values -> matches MC's cache).
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL11.glDepthMask(prevDepthMask);
            GL11.glDepthFunc(prevDepthFunc);
            if (prevDepthTest) { GL11.glEnable(GL11.GL_DEPTH_TEST); } else { GL11.glDisable(GL11.GL_DEPTH_TEST); }
            if (prevCull) { GL11.glEnable(GL11.GL_CULL_FACE); } else { GL11.glDisable(GL11.GL_CULL_FACE); }
        }
    }

    /** Build (or rebuild) the per-light triangle VBO from the block shape AABBs.
     *  Returns the vertex count (0 if there is no shaped geometry). */
    private int buildVbo(long id, List<BlockShadowEntry> blocks)
    {
        // Count boxes first (cheap AABB iteration) to size one allocation.
        int[] boxes = {0};
        for (int i = 0, n = blocks.size(); i < n; i++)
        {
            BlockShadowEntry e = blocks.get(i);
            if (e != null && e.shape != null)
            {
                e.shape.forEachBox((a, b, c, d, f, g) -> boxes[0]++);
            }
        }
        int boxCount = boxes[0];
        if (boxCount == 0)
        {
            // No shaped geometry (e.g. only cutout entries): drop any VBO.
            this.releaseBlockVbo(id);
            this.vboList.put(id, blocks);
            this.vboVertCount.put(id, 0);
            return 0;
        }

        int vertCount = boxCount * 36; // 6 faces * 2 tris * 3 verts
        FloatBuffer fb = MemoryUtil.memAllocFloat(vertCount * 3);
        try
        {
            for (int i = 0, n = blocks.size(); i < n; i++)
            {
                BlockShadowEntry e = blocks.get(i);
                if (e == null || e.shape == null)
                {
                    continue;
                }
                // worldPos - A subtracted in DOUBLE before the float cast: (int pos) -
                // (integral double A) + (double shape) -> float is exact, and the cache
                // bakes A in — the block-list instance changes exactly when the snap/A
                // changes (line 493 gate), so a cached VBO's A always equals the pass
                // eye's A. Mirrors main QuadBoxConsumer x1 = (float)(ox - originX + minX).
                final int ox = e.pos.getX(), oy = e.pos.getY(), oz = e.pos.getZ();
                final double ax = this.originX.getAsDouble(), ay = this.originY.getAsDouble(), az = this.originZ.getAsDouble();
                e.shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) ->
                    this.emitBox(fb,
                        (float) (ox - ax + minX), (float) (oy - ay + minY), (float) (oz - az + minZ),
                        (float) (ox - ax + maxX), (float) (oy - ay + maxY), (float) (oz - az + maxZ)));
            }
            fb.flip();

            int vbo = this.vboId.get(id);
            if (vbo == 0)
            {
                vbo = GL15.glGenBuffers();
                this.vboId.put(id, vbo);
            }
            int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fb, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);

            this.vboList.put(id, blocks);
            this.vboVertCount.put(id, vertCount);
            return vertCount;
        }
        finally
        {
            MemoryUtil.memFree(fb);
        }
    }

    /** Append one axis-aligned box as 12 triangles (36 verts, POSITION only).
     *  Winding is irrelevant — culling is disabled during the bake. */
    private void emitBox(FloatBuffer b,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2)
    {
        // -X
        this.tri(b, x1,y1,z1, x1,y1,z2, x1,y2,z2);  this.tri(b, x1,y1,z1, x1,y2,z2, x1,y2,z1);
        // +X
        this.tri(b, x2,y1,z1, x2,y2,z1, x2,y2,z2);  this.tri(b, x2,y1,z1, x2,y2,z2, x2,y1,z2);
        // -Y
        this.tri(b, x1,y1,z1, x2,y1,z1, x2,y1,z2);  this.tri(b, x1,y1,z1, x2,y1,z2, x1,y1,z2);
        // +Y
        this.tri(b, x1,y2,z1, x1,y2,z2, x2,y2,z2);  this.tri(b, x1,y2,z1, x2,y2,z2, x2,y2,z1);
        // -Z
        this.tri(b, x1,y1,z1, x1,y2,z1, x2,y2,z1);  this.tri(b, x1,y1,z1, x2,y2,z1, x2,y1,z1);
        // +Z
        this.tri(b, x1,y1,z2, x2,y1,z2, x2,y2,z2);  this.tri(b, x1,y1,z2, x2,y2,z2, x1,y2,z2);
    }

    private void tri(FloatBuffer b,
                            float ax, float ay, float az,
                            float bx, float by, float bz,
                            float cx, float cy, float cz)
    {
        b.put(ax).put(ay).put(az);
        b.put(bx).put(by).put(bz);
        b.put(cx).put(cy).put(cz);
    }

    // --- Cutout block depth bake (textured, alpha-tested) ---------------------

    /**
     * Draw a light's cutout blocks from their real MC-tessellated textured geometry
     * (see native geometry capture), sampling the block atlas and discarding
     * transparent texels so light passes through (lacy leaf / grate / glass-pane
     * shadows). Per-light VBO cached like the opaque path. Called from
     * {@link #renderBlocksDepth} within a pass.
     */
    private void renderBlocksDepthCutout(long id, List<BlockShadowEntry> blocks)
    {
        if (this.programs.cutoutProgram == 0)
        {
            return;
        }

        // Keyed on the list instance alone (see drawOpaqueBlocks): buildCutoutVbo
        // stores the instance even with zero quads, so this won't re-tessellate an
        // empty lamp every frame.
        int verts;
        if (this.cutoutList.get(id) != blocks)
        {
            verts = this.buildCutoutVbo(id, blocks);
        }
        else
        {
            verts = this.cutoutVertCount.get(id);
        }
        if (verts <= 0)
        {
            return;
        }
        int vbo = this.cutoutVboId.get(id);

        int atlas = atlasGlId();
        if (atlas == 0)
        {
            return; // atlas texture not ready this frame
        }

        // Snapshot every global GL bit the draw mutates (program/VAO/buffer, depth,
        // cull, blend, active texture unit + its 2D binding) and restore it, like
        // the opaque path — keeps the surrounding renderer's GL state consistent.
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        try
        {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);

            GL20.glUseProgram(this.programs.cutoutProgram);
            this.projection.mul(this.view, this.viewProj);
            try (MemoryStack stack = MemoryStack.stackPush())
            {
                FloatBuffer fb = stack.mallocFloat(16);
                this.viewProj.get(fb);
                GL20.glUniformMatrix4fv(this.programs.cutoutViewProjLoc, false, fb);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
            GL20.glUniform1i(this.programs.cutoutAtlasLoc, 0);

            GL30.glBindVertexArray(this.programs.cutoutVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 20, 0L);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 20, 12L);

            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verts);
        }
        catch (Throwable t)
        {
            if (!this.cutoutErrorLogged)
            {
                this.cutoutErrorLogged = true;
                System.err.println("[irl-core] cutout block shadow bake failed: " + t);
                t.printStackTrace();
            }
        }
        finally
        {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
            GL13.glActiveTexture(prevActiveTex);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL11.glDepthMask(prevDepthMask);
            GL11.glDepthFunc(prevDepthFunc);
            if (prevDepthTest) { GL11.glEnable(GL11.GL_DEPTH_TEST); } else { GL11.glDisable(GL11.GL_DEPTH_TEST); }
            if (prevCull) { GL11.glEnable(GL11.GL_CULL_FACE); } else { GL11.glDisable(GL11.GL_CULL_FACE); }
            if (prevBlend) { GL11.glEnable(GL11.GL_BLEND); } else { GL11.glDisable(GL11.GL_BLEND); }
        }
    }

    /** Build (or rebuild) a light's cutout VBO (interleaved POSITION(3)+UV(2),
     *  stride 20) from its cutout entries, tessellated by MC's real block renderer
     *  (native geometry capture) in absolute world coordinates with correct
     *  atlas UVs. Returns the vertex count (0 if there is no cutout geometry). */
    private int buildCutoutVbo(long id, List<BlockShadowEntry> blocks)
    {
        if (!NativeBlockGeometry.available())
        {
            this.releaseCutoutVbo(id);
            this.cutoutList.put(id, blocks);
            this.cutoutVertCount.put(id, 0);
            return 0;
        }

        FloatArrayList data = new FloatArrayList();
        // Suppress the mod's per-frame light re-registration while a ModelBlock's
        // real render runs inside the capture (same gate the opaque path relies on
        // implicitly via the caster batch). Cleared in the finally.
        ShadowBakeState.setBaking(true);
        try
        {
            for (int i = 0, n = blocks.size(); i < n; i++)
            {
                BlockShadowEntry e = blocks.get(i);
                if (e == null || !e.cutout)
                {
                    continue;
                }
                BlockPos p = e.pos;
                try
                {
                    float[] tris = NativeBlockGeometry.triangles(p, this.cutoutRandom,
                        this.originX.getAsDouble(), this.originY.getAsDouble(), this.originZ.getAsDouble());
                    if (tris.length > 0)
                    {
                        data.addElements(data.size(), tris);
                    }
                }
                catch (Throwable t)
                {
                    // skip a single broken block, keep tessellating the rest
                }
            }
        }
        finally
        {
            ShadowBakeState.setBaking(false);
        }

        int floats = data.size();
        if (floats == 0)
        {
            this.releaseCutoutVbo(id);
            this.cutoutList.put(id, blocks);
            this.cutoutVertCount.put(id, 0);
            return 0;
        }
        int verts = floats / 5;

        FloatBuffer fb = MemoryUtil.memAllocFloat(floats);
        try
        {
            fb.put(data.elements(), 0, floats);
            fb.flip();

            int vbo = this.cutoutVboId.get(id);
            if (vbo == 0)
            {
                vbo = GL15.glGenBuffers();
                this.cutoutVboId.put(id, vbo);
            }
            int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fb, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);

            this.cutoutList.put(id, blocks);
            this.cutoutVertCount.put(id, verts);
            return verts;
        }
        finally
        {
            MemoryUtil.memFree(fb);
        }
    }

    /** Raw GL id of the block atlas texture, or 0 if it is not allocated yet. */
    private int atlasGlId()
    {
        return NativeBlockAtlas.glId();
    }

    /** Free one lamp's cached cutout VBO. */
    private void releaseCutoutVbo(long id)
    {
        int vbo = this.cutoutVboId.remove(id);
        if (vbo != 0)
        {
            GL15.glDeleteBuffers(vbo);
        }
        this.cutoutList.remove(id);
        this.cutoutVertCount.remove(id);
    }

    /** Free one lamp's cached block VBO. */
    public void releaseBlockVbo(long id)
    {
        int vbo = this.vboId.remove(id);
        if (vbo != 0)
        {
            GL15.glDeleteBuffers(vbo);
        }
        this.vboList.remove(id);
        this.vboVertCount.remove(id);
    }

    /** Free block VBOs (opaque + cutout) for lamps not in {@code liveIds} (gone,
     *  or feature off -> empty set drains all). Run once per bake after the light
     *  loops. A lamp with only cutout blocks isn't in the opaque {@link #vboList},
     *  so the cutout cache is swept separately. */
    public void retainBlockVbos(LongSet liveIds)
    {
        if (!this.vboId.isEmpty())
        {
            ObjectIterator<Long2ObjectMap.Entry<List<BlockShadowEntry>>> it = this.vboList.long2ObjectEntrySet().iterator();
            while (it.hasNext())
            {
                long key = it.next().getLongKey();
                if (!liveIds.contains(key))
                {
                    int vbo = this.vboId.remove(key);
                    if (vbo != 0)
                    {
                        GL15.glDeleteBuffers(vbo);
                    }
                    this.vboVertCount.remove(key);
                    it.remove();
                }
            }
        }

        if (!this.cutoutVboId.isEmpty())
        {
            ObjectIterator<Long2ObjectMap.Entry<List<BlockShadowEntry>>> it = this.cutoutList.long2ObjectEntrySet().iterator();
            while (it.hasNext())
            {
                long key = it.next().getLongKey();
                if (!liveIds.contains(key))
                {
                    int vbo = this.cutoutVboId.remove(key);
                    if (vbo != 0)
                    {
                        GL15.glDeleteBuffers(vbo);
                    }
                    this.cutoutVertCount.remove(key);
                    it.remove();
                }
            }
        }
    }

}
