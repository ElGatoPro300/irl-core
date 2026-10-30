package org.qualet.irl.light.shadow;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.systems.RenderSystem;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import it.unimi.dsi.fastutil.longs.LongSet;

import java.nio.IntBuffer;
import java.util.List;

/**
 * Bakes spotlight shadow depth maps. Phase 1: perspective depth tile per spot,
 * entity occluders only (vanilla render dispatcher), hard shadow. Ported from
 * the original IRLights ShadowRenderer (spot + entity path).
 *
 * Usage: beginSpot(...) -> renderEntity(...) * -> endPass().
 */
public final class ShadowRenderer
{
    private static final float NEAR = 0.05F;

    // Constant "up" axes for the spot lookAt — lookAt only reads them, so a
    // shared instance replaces the per-call allocation (T1.3).
    private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F);
    private static final Vector3f UP_Z = new Vector3f(0F, 0F, 1F);

    private static boolean inPass = false;
    /** True once {@link #savePassState} has snapshotted the original GL state
     *  this bake. {@link #beginBake} clears it so the next bake re-grabs the
     *  (possibly different) state, while passes within one bake share the
     *  single snapshot instead of re-running the glGet* sync points. */
    private static boolean passStateSaved = false;

    private static int savedFbo;
    private static final int[] savedViewport = new int[4];
    private static boolean savedScissorEnabled;
    private static final int[] savedScissorBox = new int[4];
    private static boolean savedCullEnabled;
    private static int savedCullFace;
    private static int savedFrontFace;
    private static NativeLegacyRender.Projection savedProjection;
    private static boolean savedMaskR, savedMaskG, savedMaskB, savedMaskA;

    private static final Matrix4f currentView = new Matrix4f();
    /** The current light's projection, mirrored from what applyMatrices set on
     *  RenderSystem. The block batch draws with this + currentView directly,
     *  NOT RenderSystem's live matrices, which a caster can leave corrupted. */
    private static final Matrix4f currentProj = new Matrix4f();

    // --- Per-pass bake anchor (light-relative bake) --------------------------
    // The depth pass bakes every caster AND the light view matrix around this
    // per-pass double anchor A = round(lightPos) instead of absolute world
    // coordinates, so the float matrix math stays sub-block-magnitude and its
    // error is translation-invariant far from the origin. A cancels exactly in
    // view*vertex (view = R·Translate(-(L-A)), vertex = worldPos-A, so
    // view*vertex = R·(worldPos-L)), leaving the stored perspective depth
    // bit-identical — no shader/atlas/format change. Every caster arm (block VBO,
    // cutout VBO, entity/model/replay Immediate) subtracts these BEFORE its float
    // cast; the eye = L-A is set from the DOUBLE light position in begin*().
    // round(lightPos) (not L itself) keeps the light's sub-block motion in the
    // per-frame eye and OUT of the per-light cached block/cutout VBO: it is
    // byte-identical to BlockShadowCache's block snap (round of the same float
    // getX), so the shared pass anchor always matches the anchor the cached VBO
    // was built with (a light drifting within its 1-block cell reuses the VBO and
    // is tracked exactly by the recomputed eye).
    private static double currentOriginX, currentOriginY, currentOriginZ;

    public static double currentOriginX() { return currentOriginX; }
    public static double currentOriginY() { return currentOriginY; }
    public static double currentOriginZ() { return currentOriginZ; }

    // --- Reused matrix scratch (T1.3) ----------------------------------------
    // RenderSystem.setProjectionMatrix copies its argument, and the block batch
    // draws with currentProj (a copy made in applyMatrices), so reusing these
    // projection objects across passes is safe — endPass restores the saved one.
    /** Spot projection; rebuilt only when its fov/far change (all of a light's
     *  passes, and consecutive equal lights, reuse it). */
    private static final Matrix4f spotProj = new Matrix4f();
    private static float spotProjFov = Float.NaN;
    private static float spotProjFar = Float.NaN;
    /** Point projection — identical for all 6 cube faces; rebuilt only when far
     *  (= radius) changes. */
    private static final Matrix4f pointProj = new Matrix4f();
    private static float pointProjFar = Float.NaN;
    /** Shared per-caster / per-cutout-block MatrixStack (the render thread is
     *  single-threaded). {@link #resetScratch} drains + identity-resets it
     *  before each use, so a caster that throws mid-build can't leave it dirty. */
    private static final MatrixStack scratch = new MatrixStack();

    /** Reused Immediate-backed batch handed to {@link ShadowCasterSource#emitOccluder}
     *  (the render thread is single-threaded, so one instance is safe). */
    private static final ImmediateOccluderBatch casterBatch = new ImmediateOccluderBatch();

    private static long casterFailures;

    /** Monotonic failure stamp: a recovered partial draw must never be cached. */
    public static long casterFailures() { return casterFailures; }

    private static final ImmediateBlockShadows blockShadows = new ImmediateBlockShadows(currentView, currentProj,
        () -> currentOriginX, () -> currentOriginY, () -> currentOriginZ, () -> inPass, ShadowRenderer::restoreCullState);

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

        // Light-relative bake anchor for this pass (see currentOrigin* docs):
        // A = round((float) lightPos) == BlockShadowCache's block snap, so the
        // shared pass anchor stays in lockstep with the cached block/cutout VBO.
        // eye = L - A keeps the full-precision sub-block light motion in the view
        // while the caster arms subtract A from their world coords.
        currentOriginX = Math.round((float) lpx);
        currentOriginY = Math.round((float) lpy);
        currentOriginZ = Math.round((float) lpz);
        float ex = (float) (lpx - currentOriginX);
        float ey = (float) (lpy - currentOriginY);
        float ez = (float) (lpz - currentOriginZ);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, SpotlightDepthAtlas.getFboId(toStatic));
        int px = SpotlightDepthAtlas.tilePixelX(tile);
        int py = SpotlightDepthAtlas.tilePixelY(tile);
        int ts = SpotlightDepthAtlas.tileSizePx(tile);
        GL11.glViewport(px, py, ts, ts);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(px, py, ts, ts);
        if (clear)
        {
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

        Vector3f up = pickStableUp(ldy);
        currentView.identity().lookAt(
            ex, ey, ez,
            ex + ldx, ey + ldy, ez + ldz,
            up.x, up.y, up.z
        );

        applyMatrices(spotProj);
    }

    /**
     * Narrow the current pass's scissor to a TILE-LOCAL depth-pixel rect
     * (partial-tile overlay draw: only the dynamic casters' region may be
     * written — the scissor is the HARD bound that keeps depth writes inside
     * the region the filters will re-run on, so an under-estimated caster
     * bbox degrades to visible silhouette clipping, never to stale
     * pyramid/EVSM content being sampled as fresh). Call between
     * {@link #beginSpot} (which set the full-tile scissor; the viewport — the
     * NDC mapping — stays full-tile) and the caster draws; {@link #endPass}
     * restores the caller's scissor as usual.
     */
    public static void restrictScissorSpot(int tile, int localX, int localY, int w, int h)
    {
        int px = SpotlightDepthAtlas.tilePixelX(tile);
        int py = SpotlightDepthAtlas.tilePixelY(tile);
        GL11.glScissor(px + localX, py + localY, w, h);
    }

    /** DIAGNOSTIC (-Dirlite.dynRectDebug=true): clear the CURRENTLY SCISSORED
     *  depth region to the near plane, making the partial-tile dyn rect show
     *  up in the world as a fully-shadowed block of the spot's light — a
     *  direct visual of where the rect actually lands relative to the caster
     *  it is supposed to cover. Call right after {@link #restrictScissorSpot}. */
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
     * atlas block (the value published to vlParams.w); the face's pixel rect
     * inside the flat atlas comes from the PointDepthAtlas accessors. The
     * face switch below is the layout canon of
     * {@link PointDepthAtlas#FACE_COL}/{@link PointDepthAtlas#FACE_ROW}.
     */
    public static void beginPointFace(int block, int face,
                                      double lpx, double lpy, double lpz,
                                      float radius,
                                      boolean toStatic, boolean clear)
    {
        savePassState();

        // Light-relative bake anchor (see beginSpot / currentOrigin* docs).
        currentOriginX = Math.round((float) lpx);
        currentOriginY = Math.round((float) lpy);
        currentOriginZ = Math.round((float) lpz);
        float ex = (float) (lpx - currentOriginX);
        float ey = (float) (lpy - currentOriginY);
        float ez = (float) (lpz - currentOriginZ);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, PointDepthAtlas.getFboId(toStatic));
        int px = PointDepthAtlas.facePixelX(block, face);
        int py = PointDepthAtlas.facePixelY(block, face);
        int ts = PointDepthAtlas.faceSizePx(block);
        GL11.glViewport(px, py, ts, ts);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(px, py, ts, ts);
        if (clear)
        {
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }

        float far = Math.max(radius, NEAR + 0.1F);
        if (far != pointProjFar)
        {
            pointProj.identity().perspective((float) Math.toRadians(90.0), 1.0F, NEAR, far);
            pointProjFar = far;
        }

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

        applyMatrices(pointProj);
    }

    // --- Batched caster pass (T2.2) ------------------------------------------
    // Casters used to flush one immediate.draw() EACH (the old renderCaster), so a
    // point light with N subjects paid up to 6N GPU flushes — once per caster per
    // cube face. Now a pass brackets its casters with
    //   beginCasterBatch() -> emitCaster()* -> endCasterBatch()
    // and flushes ONCE: the casters accumulate in the shared entity Immediate and
    // a single draw at the end submits them all, so a pass costs at most one flush
    // (one per face / per atlas tile) regardless of how many subjects it has.

    /** Open a batched caster pass. Pins the depth GL state once for the whole
     *  batch (each layer's RenderLayer.startDrawing sets the real state at flush
     *  time, but pin defensively, as the old per-caster path did) and enters
     *  baking mode so light-form renderers skip re-registration during the bake.
     *  Casters are emitted with {@link #emitCaster} and flushed by
     *  {@link #endCasterBatch}. No-op outside a begin*()/endPass() bracket. */
    public static void beginCasterBatch()
    {
        if (!inPass)
        {
            return;
        }
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        ShadowBakeState.setBaking(true);
    }

    /** Shared per-caster wrapper around {@link ShadowCasterSource#emitOccluder}.
     *  Owns the scratch reset and the per-caster exception + run isolation
     *  (INVARIANT 4): the source emits one caster's depth geometry into the shared
     *  Immediate (via {@link ImmediateOccluderBatch}); if it throws mid-build the
     *  batch is drained here — re-asserting the light matrices (INVARIANT 1) — so
     *  the broken caster's partial vertices can never fuse with the next caster's
     *  into a garbage quad. The source NEVER flushes or catches its own throw. */
    public static void emitCaster(ShadowCasterSource source, Object caster, int casterType, float tickDelta)
    {
        if (caster == null || !inPass)
        {
            return;
        }

        VertexConsumerProvider.Immediate immediate = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
        resetScratch();
        casterBatch.bind(immediate, scratch);
        casterBatch.mark();
        // INVARIANT 1 hardening (MAJOR-A): some caster forms self-draw SYNCHRONOUSLY
        // inside emitOccluder (BBS ModelForm via glDrawArrays; MobForm leaves the live
        // modelview at identity), reading the LIVE RenderSystem modelview AT EMIT TIME —
        // before the end-of-batch flush re-establishes it. A form emitted after a
        // modelview-corrupting caster would otherwise draw through a garbage transform
        // and cast a wrong/absent shadow (no GL error). Re-assert the clean light
        // view/proj before EVERY emit so each self-drawing form starts from the light
        // modelview. Harmless/idempotent for the buffered path (its draws ride the
        // scratch MatrixStack until the single end-of-batch flush, which re-asserts the
        // same matrices). See irl-core/docs/shadow-caster-seam-spec.md INV-1 erratum.
        establishLightMatrices(currentView, currentProj);
        // MAJOR-A analog for NON-matrix GL state. beginCasterBatch pins the bake's
        // depth state ONCE per pass, but a self-drawing caster can mutate it mid-pass:
        // e.g. a BBS form item held by the local-player occluder renders through a BBS
        // FormRenderer that calls RenderSystem.disableDepthTest() and leaves blend on
        // (exactly as MorphRenderer does), so the caster emitted NEXT in the same pass
        // — notably a model block whose form self-draws via glDrawArrays — would bake
        // with depthTest OFF and land nothing in the depth map: its shadow silently
        // vanishes with no GL error (the same failure renderBlocksDepth dodges by
        // passing matrices explicitly). establishLightMatrices repairs the matrices but
        // not depthMask/depthTest/blend; re-pin them before EVERY emit so each caster
        // starts from the pinned bake state regardless of what the previous one left.
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        try
        {
            source.emitOccluder(caster, casterType, tickDelta, casterBatch);
        }
        catch (Throwable t)
        {
            casterFailures++;
            // The caster threw mid-build: terminate its run now (drain the batch,
            // re-asserting the light matrices) so its partial geometry ends here
            // instead of merging into the next caster's quads.
            casterBatch.terminateRun(currentView, currentProj);
        }
    }

    /** Close a batched caster pass: flush every buffered caster with one
     *  immediate.draw() and leave baking mode. */
    public static void endCasterBatch()
    {
        try
        {
            if (inPass)
            {
                flushCasterImmediate(MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers(), currentView, currentProj);
            }
        }
        finally
        {
            ShadowBakeState.setBaking(false);
        }
    }

    /** Drain the shared entity Immediate with a single draw, re-asserting the
     *  light's {@code view}/{@code proj} first (INVARIANT 1): the batched draw
     *  transforms its buffered world-space caster geometry by RenderSystem's LIVE
     *  modelview/projection, which a caster baked earlier in the batch can leave
     *  corrupted (a vanilla mob drawn through the EntityRenderer; the same
     *  corruption the block/cutout paths dodge by passing matrices explicitly to
     *  VertexBuffer.draw). The per-caster path got away without this because each
     *  caster flushed right after applyMatrices set the state; a single end-of-batch
     *  (or recovery) flush does not. Called by {@link #endCasterBatch} (success) and
     *  {@link ImmediateOccluderBatch#terminateRun} (per-caster recovery, INVARIANT 4). */
    static void flushCasterImmediate(VertexConsumerProvider.Immediate immediate, Matrix4f view, Matrix4f proj)
    {
        establishLightMatrices(view, proj);
        try
        {
            immediate.draw();
        }
        catch (Throwable t)
        {
            casterFailures++;
            // swallow — a broken buffer must not abort the whole bake
        }
    }

    /** Re-assert the light's {@code view}/{@code proj} onto the live RenderSystem
     *  projection + modelview stack: reload the modelview-stack top to {@code view}
     *  (NO extra push — applyMatrices / endPass own the single push/pop) and restore
     *  the light projection. The shared matrix prologue used by
     *  {@link #flushCasterImmediate} (immediately before the batched draw, INVARIANT 1
     *  CLAUSE 1) and by {@link #emitCaster} (before each emitOccluder — the MAJOR-A
     *  hardening for forms that self-draw against the live modelview at emit time). */
    static void establishLightMatrices(Matrix4f view, Matrix4f proj)
    {
        NativeLegacyRender.projection(proj);
        // 1.21: getModelViewStack() returns a JOML Matrix4fStack (loadIdentity ->
        // identity, multiplyPositionMatrix -> mul); applyModelViewMatrix still uploads it.
        NativeLegacyRender.view(view, false);
    }

    public static void endPass()
    {
        if (!inPass)
        {
            return;
        }

        NativeLegacyRender.popView();
        NativeLegacyRender.restoreProjection(savedProjection);

        GL11.glColorMask(savedMaskR, savedMaskG, savedMaskB, savedMaskA);
        restoreCullState();

        if (savedScissorEnabled)
        {
            GL11.glScissor(savedScissorBox[0], savedScissorBox[1], savedScissorBox[2], savedScissorBox[3]);
        }
        else
        {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);

        inPass = false;
    }

    private static void savePassState()
    {
        // The saved state is the original MC/Iris GL state, which every endPass
        // restores — so it is invariant across all passes of one bake. Snapshot
        // it only on the first pass (the glGet* are CPU<->GPU sync points; up to
        // 64 spots + 18*6 point faces = ~172 passes would otherwise issue ~5
        // each). beginBake() re-arms it for the next bake.
        inPass = true;
        if (passStateSaved)
        {
            return;
        }

        savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
        savedScissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (savedScissorEnabled)
        {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, savedScissorBox);
        }
        savedProjection = NativeLegacyRender.saveProjection();
        // BBS model/BOBJ renderers may change culling while they are replayed as
        // shadow casters. Merely re-enabling culling is insufficient: a leaked
        // GL_FRONT selector (or flipped winding) makes the following world pass
        // show only the inside faces of every model. Preserve the complete state.
        savedCullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        savedCullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        savedFrontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);

        try (MemoryStack stack = MemoryStack.stackPush())
        {
            IntBuffer maskBuf = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, maskBuf);
            savedMaskR = maskBuf.get(0) != 0;
            savedMaskG = maskBuf.get(1) != 0;
            savedMaskB = maskBuf.get(2) != 0;
            savedMaskA = maskBuf.get(3) != 0;
        }

        passStateSaved = true;
    }

    private static void restoreCullState()
    {
        GL11.glCullFace(savedCullFace);
        GL11.glFrontFace(savedFrontFace);

        if (savedCullEnabled)
        {
            RenderSystem.enableCull();
        }
        else
        {
            RenderSystem.disableCull();
        }
    }

    private static void applyMatrices(Matrix4f proj)
    {
        GL11.glColorMask(true, true, true, true);

        NativeLegacyRender.projection(proj);
        currentProj.set(proj);

        NativeLegacyRender.view(currentView, true);
    }

    private static Vector3f pickStableUp(float dy)
    {
        return Math.abs(dy) > 0.99F ? UP_Z : UP_Y;
    }

    /** Drain the shared {@link #scratch} back to its single root entry (popping
     *  anything a thrown caster left pushed — {@code isEmpty()} is true at size
     *  1) and reset that entry to identity, so each use starts clean. */
    private static void resetScratch()
    {
        while (!scratch.isEmpty())
        {
            scratch.pop();
        }
        scratch.loadIdentity();
    }
    public static void releaseScratch()
    {
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
