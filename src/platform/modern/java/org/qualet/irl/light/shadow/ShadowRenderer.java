package org.qualet.irl.light.shadow;

import org.qualet.irl.platform.NativeGl;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * Raw depth-pass facade shared by Minecraft 1.21.11 and 26.x.
 * Owns the pass lifecycle and caster accumulator. RawBlockShadows owns block
 * geometry caches; RawDepthPrograms owns the shared shader programs.
 */
public final class ShadowRenderer
{
    private static final float NEAR = 0.05F;

    private static boolean inPass = false;
    /** True once {@link #savePassState} has snapshotted the original GL state this
     *  bake. {@link #beginBake} clears it so the next bake re-grabs the (possibly
     *  different) state, while passes within one bake share the single snapshot
     *  instead of re-running the glGet* sync points. */
    private static boolean passStateSaved = false;

    private static int savedFbo;
    private static final int[] savedViewport = new int[4];
    private static boolean savedScissorEnabled;
    private static final int[] savedScissorBox = new int[4];
    private static boolean savedDepthMask;

    /** The current light's view/projection (world space), combined into
     *  {@link #viewProj} for the depth programs' uViewProj uniform. The block +
     *  caster draws upload this directly, NEVER MC's live modelview (which no
     *  longer exists on the hot path here), so a caster can never corrupt them. */
    private static final Matrix4f currentView = new Matrix4f();
    private static final Matrix4f currentProj = new Matrix4f();
    private static final Matrix4f viewProj = new Matrix4f();

    /** Per-pass light-relative bake anchor A = round((float) lightPos). The pass view
     *  eye is L - A and EVERY caster vertex (opaque VBO, cutout VBO, entity accum)
     *  enters as worldPos - A subtracted in DOUBLE before its float cast, so
     *  viewProj*(v-A) with eye L-A == R*(v-L): A cancels exactly, the stored depth
     *  contract is unchanged, the 1e5 float cancellation is removed. round((float)
     *  lightPos) == BlockShadowCache's block snap == round(getX), so this shared
     *  anchor stays in lockstep with the per-light cached block/cutout VBO. Exposed so
     *  the mods' entity arm (editor OccluderGeometryCapturer) subtracts the SAME A. */
    private static double currentOriginX, currentOriginY, currentOriginZ;
    public static double currentOriginX() { return currentOriginX; }
    public static double currentOriginY() { return currentOriginY; }
    public static double currentOriginZ() { return currentOriginZ; }

    public static Matrix4f currentViewMatrix()
    {
        return new Matrix4f(currentView);
    }

    public static Matrix4f currentProjectionMatrix()
    {
        return new Matrix4f(currentProj);
    }

    // --- Reused matrix scratch (T1.3) ----------------------------------------
    // The spot projection depends only on (fov, far) and the point projection only
    // on far, but a bake runs many passes (every spot tile, every point face) —
    // recomputing perspective()'s trig per pass is wasted work when consecutive
    // lamps share parameters. These hold the last-built matrix + the params it was
    // built for; each begin*() copies the cached matrix into currentProj, rebuilding
    // only when params change (currentProj itself can't cache it — the other light
    // kind overwrites currentProj).
    private static final Matrix4f spotProj = new Matrix4f();
    private static float spotProjFov = Float.NaN;
    private static float spotProjFar = Float.NaN;
    private static final Matrix4f pointProj = new Matrix4f();
    private static float pointProjFar = Float.NaN;

    /** Reused raw-GL batch handed to {@link ShadowCasterSource#emitOccluder} (the
     *  render thread is single-threaded, so one instance is safe). It appends the
     *  source's world-space POSITION triangles into {@link #casterAccum}. */
    private static final RawOccluderBatch casterBatch = new RawOccluderBatch();

    private static boolean blockErrorLogged;
    private static final RawDepthPrograms programs = new RawDepthPrograms();
    private static long casterFailures;
    private static int savedClipOrigin;
    private static int savedClipDepth;
    private static double savedClearDepth;
    private static boolean clipControlSupported()
    {
        return GL.getCapabilities().OpenGL45 || GL.getCapabilities().GL_ARB_clip_control;
    }
    private static void classicClipDepth()
    {
        if (clipControlSupported())
        {
            GL45.glClipControl(GL45.GL_LOWER_LEFT, GL45.GL_NEGATIVE_ONE_TO_ONE);
        }
    }

    /** Monotonic failure stamp: a recovered partial draw must never be cached. */
    public static long casterFailures() { return casterFailures; }

    private static final RawBlockShadows blockShadows = new RawBlockShadows(currentView, currentProj,
        () -> currentOriginX, () -> currentOriginY, () -> currentOriginZ, () -> inPass, programs);

    private ShadowRenderer()
    {
    }

    /** Call once at the start of a bake, before any begin*()/endPass(). Arms a
     *  fresh snapshot of the MC/Iris GL state on the first pass of this bake
     *  (see {@link #savePassState}), so the per-pass glGet* stalls collapse to
     *  one per bake. */
    public static void beginBake()
    {
        passStateSaved = false;
        // Defensive: drop any unflushed pass triangles (endPass clears every pass).
        casterAccum.clear();
    }

    /**
     * Begin a spot depth pass into the live ({@code toStatic} false) or static
     * ({@code toStatic} true) atlas tile. {@code clear} false keeps the tile's
     * current depth — used for the dynamic-caster overlay drawn on top of a
     * static base just restored by {@link SpotlightDepthAtlas#copyStaticToLive}.
     */
    public static void beginSpot(int tile,
                                 double lpx, double lpy, double lpz,
                                 float ldx, float ldy, float ldz,
                                 float range, float outerDeg,
                                 boolean toStatic, boolean clear)
    {
        savePassState();

        // Light-relative bake anchor A = round((float) lightPos) (== BlockShadowCache's
        // block snap), and the pass view eye at L - A carried in float (the sub-block
        // residual). ldx/ldy/ldz stay float — direction is translation-invariant.
        currentOriginX = Math.round((float) lpx);
        currentOriginY = Math.round((float) lpy);
        currentOriginZ = Math.round((float) lpz);
        float ex = (float) (lpx - currentOriginX);
        float ey = (float) (lpy - currentOriginY);
        float ez = (float) (lpz - currentOriginZ);

        NativeGl._glBindFramebuffer(GL30.GL_FRAMEBUFFER, SpotlightDepthAtlas.getFboId(toStatic));
        int px = SpotlightDepthAtlas.tilePixelX(tile);
        int py = SpotlightDepthAtlas.tilePixelY(tile);
        int ts = SpotlightDepthAtlas.tileSizePx(tile);
        GL11.glViewport(px, py, ts, ts);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(px, py, ts, ts);
        if (clear)
        {
            GL11.glDepthMask(true);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }

        float fovDeg = Math.max(outerDeg, 1.0F);
        float far = Math.max(range, NEAR + 0.1F);
        if (fovDeg != spotProjFov || far != spotProjFar)
        {
            spotProj.identity().perspective((float) Math.toRadians(fovDeg), 1.0F, NEAR, far);
            spotProjFov = fovDeg;
            spotProjFar = far;
        }
        currentProj.set(spotProj);

        Vector3f up = pickStableUp(ldy);
        currentView.identity().lookAt(
            ex, ey, ez,
            ex + ldx, ey + ldy, ez + ldz,
            up.x, up.y, up.z
        );
    }

    /** Shrink the current spot tile's scissor to a partial sub-rect (the 1.1.3
     *  dyn-rect / partial-tile bake path). Call between {@link #beginSpot} (which
     *  set the full-tile scissor; the viewport/NDC mapping stays full-tile) and
     *  the caster draws; {@link #endPass} restores the caller's scissor. */
    public static void restrictScissorSpot(int tile, int localX, int localY, int w, int h)
    {
        int px = SpotlightDepthAtlas.tilePixelX(tile);
        int py = SpotlightDepthAtlas.tilePixelY(tile);
        GL11.glScissor(px + localX, py + localY, w, h);
    }

    /** DIAGNOSTIC (-Dirlite.dynRectDebug=true): clear the CURRENTLY SCISSORED
     *  depth region to the near plane so the partial-tile dyn rect shows up in
     *  the world as a fully-shadowed block. Call right after
     *  {@link #restrictScissorSpot}. */
    public static void debugFillScissoredDepth()
    {
        GL11.glClearDepth(0.0);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glClearDepth(1.0);
    }

    /**
     * Begin a point-cube face depth pass into the live or static atlas (see
     * {@link #beginSpot} for the {@code toStatic}/{@code clear} semantics; the
     * static base of a whole block is restored by
     * {@link PointDepthAtlas#copyStaticToLive}). {@code block} is the GLOBAL
     * point-atlas block index; each of the 6 cube faces renders into its own
     * pixel sub-rect of that block's atlas region.
     */
    public static void beginPointFace(int block, int face,
                                      double lpx, double lpy, double lpz,
                                      float radius,
                                      boolean toStatic, boolean clear)
    {
        savePassState();

        // Light-relative bake anchor A = round((float) lightPos) (== BlockShadowCache's
        // block snap), and the pass view eye at L - A carried in float (the sub-block
        // residual). The face dx/dy/dz below stay unit floats — translation-invariant.
        currentOriginX = Math.round((float) lpx);
        currentOriginY = Math.round((float) lpy);
        currentOriginZ = Math.round((float) lpz);
        float ex = (float) (lpx - currentOriginX);
        float ey = (float) (lpy - currentOriginY);
        float ez = (float) (lpz - currentOriginZ);

        NativeGl._glBindFramebuffer(GL30.GL_FRAMEBUFFER, PointDepthAtlas.getFboId(toStatic));
        int px = PointDepthAtlas.facePixelX(block, face);
        int py = PointDepthAtlas.facePixelY(block, face);
        int fs = PointDepthAtlas.faceSizePx(block);
        GL11.glViewport(px, py, fs, fs);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(px, py, fs, fs);
        if (clear)
        {
            GL11.glDepthMask(true);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }

        float far = Math.max(radius, NEAR + 0.1F);
        if (far != pointProjFar)
        {
            pointProj.identity().perspective((float) Math.toRadians(90.0), 1.0F, NEAR, far);
            pointProjFar = far;
        }
        currentProj.set(pointProj);

        float dx, dy, dz, ux, uy, uz;
        switch (face)
        {
            case 0:  dx =  1; dy =  0; dz =  0; ux = 0; uy = -1; uz =  0; break; // +X
            case 1:  dx = -1; dy =  0; dz =  0; ux = 0; uy = -1; uz =  0; break; // -X
            case 2:  dx =  0; dy =  1; dz =  0; ux = 0; uy =  0; uz =  1; break; // +Y
            case 3:  dx =  0; dy = -1; dz =  0; ux = 0; uy =  0; uz = -1; break; // -Y
            case 4:  dx =  0; dy =  0; dz =  1; ux = 0; uy = -1; uz =  0; break; // +Z
            default: dx =  0; dy =  0; dz = -1; ux = 0; uy = -1; uz =  0; break; // -Z
        }

        currentView.identity().lookAt(
            ex, ey, ez,
            ex + dx, ey + dy, ez + dz,
            ux, uy, uz
        );
    }

    // --- Batched caster pass (T2.2) ------------------------------------------
    // A pass brackets its casters with
    //   beginCasterBatch() -> emitCaster()* -> endCasterBatch()
    // and flushes ONCE (in endPass): each caster APPENDS its world-space POSITION
    // triangles into casterAccum, and a single glDrawArrays at pass end submits
    // them all, so a pass costs at most one caster draw regardless of subject count.

    /** Triangles (POSITION xyz) accumulated for the current pass; uploaded + drawn
     *  once in {@link #endPass} so many casters cost a single draw + state snapshot. */
    private static final FloatArrayList casterAccum = new FloatArrayList();
    private static int casterScratchVbo = 0;
    /** Persistent off-heap upload buffer, grown on demand and reused across passes
     *  (a moving caster flushes every dynamic pass / cube face — a fresh
     *  memAllocFloat each time would be per-frame native churn). Freed by
     *  {@link #releaseScratch}. */
    private static FloatBuffer casterScratch;
    private static int casterScratchCap;

    /** Open a batched caster pass. Enters baking mode so a mod's light-form
     *  renderers skip re-registration while a caster's model is captured during
     *  the bake. Casters are emitted with {@link #emitCaster} and flushed by
     *  {@link #endPass}. No-op outside a begin*()/endPass() bracket. */
    public static void beginCasterBatch()
    {
        if (!inPass)
        {
            return;
        }
        ShadowBakeState.setBaking(true);
    }

    /** Shared per-caster wrapper around {@link ShadowCasterSource#emitOccluder}.
     *  Owns the per-caster run isolation (INVARIANT 4): the source appends one
     *  caster's world-space POSITION triangles into the shared accumulator (via
     *  {@link RawOccluderBatch}); if it throws mid-append the run is rewound to the
     *  start-of-this-caster mark here, so the broken caster's partial vertices can
     *  never fuse with the next caster's into a garbage triangle. The source NEVER
     *  flushes or catches its own throw.
     *
     *  <p>The raw-GL caster path is INVARIANT-1 exempt: it appends world-space
     *  geometry drawn through the depth program's explicit uViewProj (never MC's
     *  live modelview, which the hot path no longer touches), so no per-caster
     *  matrix / depth-state re-establish is needed (unlike the 1.21.4 Immediate
     *  backend). See {@code docs/shadow-caster-seam-spec.md} INV-1 EXEMPTION. */
    public static void emitCaster(ShadowCasterSource source, Object caster, int casterType, float tickDelta)
    {
        if (source == null || caster == null || !inPass || programs.glBroken)
        {
            return;
        }
        if (!programs.glInit)
        {
            programs.initGl();
        }
        if (programs.glBroken || programs.program == 0)
        {
            return;
        }

        casterBatch.bind(casterAccum);
        casterBatch.mark();
        try
        {
            source.emitOccluder(caster, casterType, tickDelta, casterBatch);
        }
        catch (Throwable t)
        {
            casterFailures++;
            // The caster threw mid-append: rewind its partial run to the mark so it
            // ends at a whole-caster boundary instead of merging into the next one.
            casterBatch.terminateRun(currentView, currentProj);
        }
    }

    /** Close a batched caster pass: leave baking mode. The accumulated casters are
     *  drawn (once) by {@link #endPass}, which still holds this pass's
     *  view/proj + bound FBO. */
    public static void endCasterBatch()
    {
        ShadowBakeState.setBaking(false);
    }

    /** Draw the caster silhouette triangles accumulated this pass (if any) with the
     *  position depth program, then clear the accumulator. Called by {@link #endPass}
     *  before the GL state is restored (they share the pass's currentView/currentProj,
     *  still set). Mirrors {@link #drawOpaqueBlocks}'s state discipline: snapshot
     *  every global bit the draw mutates and restore it exactly. */
    private static void flushCasters()
    {
        if (casterAccum.isEmpty() || programs.glBroken || programs.program == 0)
        {
            return;
        }
        int floats = casterAccum.size();
        int verts = floats / 3;

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        // Grow-only persistent upload buffer (no per-pass alloc/free).
        if (casterScratch == null || floats > casterScratchCap)
        {
            if (casterScratch != null)
            {
                MemoryUtil.memFree(casterScratch);
            }
            casterScratchCap = Math.max(floats, 4096);
            casterScratch = MemoryUtil.memAllocFloat(casterScratchCap);
        }
        FloatBuffer fb = casterScratch;
        try
        {
            fb.clear();
            fb.put(casterAccum.elements(), 0, floats);
            fb.flip();

            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            // Cull OFF: the model captures both windings; the depth test keeps the
            // nearest (light-facing) surface -> a tight, correct silhouette.
            GL11.glDisable(GL11.GL_CULL_FACE);

            GL20.glUseProgram(programs.program);
            currentProj.mul(currentView, viewProj);
            try (MemoryStack stack = MemoryStack.stackPush())
            {
                FloatBuffer mb = stack.mallocFloat(16);
                viewProj.get(mb);
                GL20.glUniformMatrix4fv(programs.uViewProjLoc, false, mb);
            }

            if (casterScratchVbo == 0)
            {
                casterScratchVbo = GL15.glGenBuffers();
            }
            GL30.glBindVertexArray(programs.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, casterScratchVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fb, GL15.GL_STREAM_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);

            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verts);
        }
        catch (Throwable t)
        {
            if (!blockErrorLogged)
            {
                blockErrorLogged = true;
                System.err.println("[irl-core] caster shadow bake failed: " + t);
                t.printStackTrace();
            }
        }
        finally
        {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL11.glDepthMask(prevDepthMask);
            GL11.glDepthFunc(prevDepthFunc);
            if (prevDepthTest) { GL11.glEnable(GL11.GL_DEPTH_TEST); } else { GL11.glDisable(GL11.GL_DEPTH_TEST); }
            if (prevCull) { GL11.glEnable(GL11.GL_CULL_FACE); } else { GL11.glDisable(GL11.GL_CULL_FACE); }
            casterAccum.clear();
        }
    }

    /** Free the persistent caster scratch buffer + its VBO (called on shaders-off,
     *  alongside the depth textures). Both lazily re-create on the next bake. */
    public static void releaseScratch()
    {
        if (casterScratch != null)
        {
            MemoryUtil.memFree(casterScratch);
            casterScratch = null;
            casterScratchCap = 0;
        }
        if (casterScratchVbo != 0)
        {
            GL15.glDeleteBuffers(casterScratchVbo);
            casterScratchVbo = 0;
        }
    }

    public static void endPass()
    {
        if (!inPass)
        {
            return;
        }

        // Draw the caster silhouettes accumulated in this pass before restoring
        // state (they share the pass's currentView/currentProj, still set here).
        flushCasters();

        GL11.glDepthMask(savedDepthMask);

        if (savedScissorEnabled)
        {
            GL11.glScissor(savedScissorBox[0], savedScissorBox[1], savedScissorBox[2], savedScissorBox[3]);
        }
        else
        {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        NativeGl._glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);

        GL11.glClearDepth(savedClearDepth);
        if (clipControlSupported())
        {
            GL45.glClipControl(savedClipOrigin, savedClipDepth);
        }
        inPass = false;
    }

    /**
     * Snapshot the GL state every endPass restores, once per bake (the glGet* are
     * CPU<->GPU sync points; up to ~112 passes per bake would otherwise stall on
     * each). {@link #beginBake} re-arms it.
     */
    private static void savePassState()
    {
        inPass = true;
        if (passStateSaved)
        {
            classicClipDepth();
            return;
        }

        if (clipControlSupported())
        {
            savedClipOrigin = GL11.glGetInteger(GL45.GL_CLIP_ORIGIN);
            savedClipDepth = GL11.glGetInteger(GL45.GL_CLIP_DEPTH_MODE);
        }
        savedClearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        classicClipDepth();
        savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
        savedScissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (savedScissorEnabled)
        {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, savedScissorBox);
        }
        savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        passStateSaved = true;
    }

    /** Shared up-vectors for the spot lookAt. lookAt only reads the components,
     *  so a single instance of each is safe (and avoids a per-pass allocation). */
    private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F);
    private static final Vector3f UP_Z = new Vector3f(0F, 0F, 1F);

    private static Vector3f pickStableUp(float dy)
    {
        return Math.abs(dy) > 0.99F ? UP_Z : UP_Y;
    }

    public static void renderBlocksDepth(long id, List<BlockShadowEntry> blocks)
    {
        blockShadows.renderBlocksDepth(id, blocks);
    }

    public static void releaseBlockVbo(long id)
    {
        blockShadows.releaseBlockVbo(id);
    }

    public static void retainBlockVbos(LongSet liveIds)
    {
        blockShadows.retainBlockVbos(liveIds);
    }

}
