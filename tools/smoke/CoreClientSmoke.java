package org.qualet.irl.smoke;

import org.qualet.irl.light.iris.IrisShadersState;
import org.qualet.irl.light.shadow.BlockShadowEntry;
import org.qualet.irl.light.shadow.ShadowRenderer;
import org.qualet.irl.light.shadow.SpotlightDepthAtlas;
import org.qualet.irl.platform.NativeFrame;
import org.qualet.irl.platform.NativeGl;
import org.qualet.irl.platform.NativeImageResize;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShapes;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;

/** Test-only native GPU check; never included in a production JAR. */
public final class CoreClientSmoke implements ClientModInitializer
{
    private int ticks;

    @Override
    public void onInitializeClient()
    {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getOverlay() != null || client.currentScreen == null)
            {
                return;
            }
            this.ticks++;
            if (this.ticks == 100)
            {
                client.scheduleStop();
            }
            if (this.ticks != 60)
            {
                return;
            }
            try
            {
                if (NativeFrame.camera(client) == null)
                {
                    throw new AssertionError("Native camera adapter returned null");
                }
                NativeFrame.position(NativeFrame.camera(client));
                if (!IrisShadersState.shadersDisabled())
                {
                    throw new AssertionError("Isolated client must have Iris loaded with shaders disabled");
                }
                this.checkResize();
                this.checkDepth();
                System.out.println("[Core smoke] NATIVE CAMERA, IRIS, IMAGE RESIZE AND DEPTH DRAW PASSED");
            }
            catch (Throwable failure)
            {
                throw new IllegalStateException("Core native GPU smoke failed", failure);
            }
        });
    }

    private void checkResize()
    {
        ByteBuffer input = MemoryUtil.memAlloc(4);
        ByteBuffer output = MemoryUtil.memAlloc(16);
        try
        {
            for (int i = 0; i < 4; i++)
            {
                input.put(i, (byte) 127);
            }
            NativeImageResize.grayscale(input, 2, 2, output, 4);
            for (int i = 0; i < 16; i++)
            {
                if ((output.get(i) & 255) != 127)
                {
                    throw new AssertionError("Constant grayscale resize changed pixel value");
                }
            }
        }
        finally
        {
            MemoryUtil.memFree(input);
            MemoryUtil.memFree(output);
        }
    }

    private void checkDepth()
    {
        while (GL11.glGetError() != GL11.GL_NO_ERROR)
        {
            /* Discard earlier client initialization diagnostics. */
        }
        SpotlightDepthAtlas.setTileSize(64);
        int fbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        List<BlockShadowEntry> entries = List.of(new BlockShadowEntry(new BlockPos(0, 0, -3), VoxelShapes.fullCube(), false));
        ShadowRenderer.beginBake();
        for (int pass = 0; pass < 2; pass++)
        {
            ShadowRenderer.beginSpot(0, 0D, 0D, 0D, 0F, 0F, -1F, 16F, 90F, false, true);
            ShadowRenderer.renderBlocksDepth(1L, entries);
            ShadowRenderer.endPass();
            if (GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING) != fbo)
            {
                throw new AssertionError("Depth draw leaked framebuffer binding");
            }
            int[] after = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, after);
            if (!Arrays.equals(viewport, after))
            {
                throw new AssertionError("Depth draw leaked viewport");
            }
            this.checkPixels(fbo);
        }
        ShadowRenderer.releaseBlockVbo(1L);
        ShadowRenderer.releaseScratch();
        SpotlightDepthAtlas.delete();
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR)
        {
            throw new AssertionError("Depth draw OpenGL error: " + error);
        }
    }

    private void checkPixels(int previousFbo)
    {
        int size = SpotlightDepthAtlas.tileSizePx(0);
        FloatBuffer pixels = MemoryUtil.memAllocFloat(size * size);
        try
        {
            NativeGl._glBindFramebuffer(GL30.GL_FRAMEBUFFER, SpotlightDepthAtlas.getFboId(false));
            GL11.glReadPixels(SpotlightDepthAtlas.tilePixelX(0), SpotlightDepthAtlas.tilePixelY(0),
                size, size, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixels);
            float min = 1F;
            for (int i = 0; i < pixels.capacity(); i++)
            {
                min = Math.min(min, pixels.get(i));
            }
            if (!(min > 0F && min < 1F))
            {
                throw new AssertionError("Opaque caster did not write valid depth: " + min);
            }
        }
        finally
        {
            NativeGl._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
            MemoryUtil.memFree(pixels);
        }
    }
}
