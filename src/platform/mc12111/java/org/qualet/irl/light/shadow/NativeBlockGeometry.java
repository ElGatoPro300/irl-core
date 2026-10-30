package org.qualet.irl.light.shadow;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import it.unimi.dsi.fastutil.floats.FloatArrayList;

import java.util.List;

final class NativeBlockGeometry
{
    private static final float[] EMPTY_TRIS = new float[0];
    private static final Capture CUTOUT_CAPTURE = new Capture();

    private NativeBlockGeometry()
    {
    }

    static boolean available()
    {
        return MinecraftClient.getInstance().world != null;
    }

    static float[] triangles(BlockPos pos, Random random, double x, double y, double z)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir())
        {
            return EMPTY_TRIS;
        }
        return triangles(mc.world, mc.getBlockRenderManager(), pos, state, random, x, y, z);
    }

    static float[] triangles(ClientWorld world, BlockRenderManager brm,
                                                  BlockPos pos, BlockState state, Random random, double originX, double originY, double originZ)
    {
        if (world == null || brm == null || state == null)
        {
            return EMPTY_TRIS;
        }
        try
        {
            BlockStateModel model = brm.getModel(state);
            if (model == null)
            {
                return EMPTY_TRIS;
            }
            random.setSeed(state.getRenderingSeed(pos));
            List<BlockModelPart> parts = model.getParts(random);
            if (parts == null || parts.isEmpty())
            {
                return EMPTY_TRIS;
            }

            MatrixStack ms = new MatrixStack();
            // Pre-translate to the anchor-relative block origin (world - A): the block
            // renderer only applies the block's model offset, never its world position.
            // (int pos) - (double anchor A) -> MatrixStack.translate(double), narrowed
            // to float inside MC. Same list-instance cache lockstep as the opaque path.
            ms.translate(pos.getX() - originX, pos.getY() - originY, pos.getZ() - originZ);

            CUTOUT_CAPTURE.reset();
            brm.renderBlock(state, pos, world, ms, CUTOUT_CAPTURE, true, parts);
            return CUTOUT_CAPTURE.toTris();
        }
        catch (Throwable t)
        {
            return EMPTY_TRIS;
        }
    }

    private static final class Capture implements VertexConsumer
    {
        private final FloatArrayList verts = new FloatArrayList(2048);
        private float cx, cy, cz, cu, cv;
        private boolean pending;

        void reset()
        {
            verts.clear();
            pending = false;
        }

        private void commit()
        {
            if (pending)
            {
                verts.add(cx);
                verts.add(cy);
                verts.add(cz);
                verts.add(cu);
                verts.add(cv);
                pending = false;
            }
        }

        /** Flush the last pending vertex and triangulate to POSITION+UV (stride 5). */
        float[] toTris()
        {
            commit();
            int n = verts.size() / 5;       // committed vertices
            int quads = n / 4;              // 4 verts per quad
            if (quads == 0)
            {
                return EMPTY_TRIS;
            }
            float[] out = new float[quads * 6 * 5];
            float[] v = verts.elements();
            int w = 0;
            for (int q = 0; q < quads; q++)
            {
                int b = q * 4 * 5; // base float index of this quad's first vertex
                // triangle (0,1,2) then (0,2,3)
                w = put(out, w, v, b, 0);
                w = put(out, w, v, b, 1);
                w = put(out, w, v, b, 2);
                w = put(out, w, v, b, 0);
                w = put(out, w, v, b, 2);
                w = put(out, w, v, b, 3);
            }
            return out;
        }

        private static int put(float[] out, int w, float[] v, int quadBase, int corner)
        {
            int s = quadBase + corner * 5;
            out[w++] = v[s];
            out[w++] = v[s + 1];
            out[w++] = v[s + 2];
            out[w++] = v[s + 3];
            out[w++] = v[s + 4];
            return w;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z)
        {
            commit();
            cx = x; cy = y; cz = z; cu = 0F; cv = 0F;
            pending = true;
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v)
        {
            cu = u; cv = v;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha)
        {
            return this;
        }

        @Override
        public VertexConsumer color(int argb)
        {
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z)
        {
            return this;
        }

        @Override
        public VertexConsumer lineWidth(float width)
        {
            return this;
        }
    }
}
