package org.qualet.irl.light.shadow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3fc;

import it.unimi.dsi.fastutil.floats.FloatArrayList;

import java.util.ArrayList;
import java.util.List;

/** Native model parts define material layers and texture coordinates since 26.1. */
final class BlockShadowModels
{
    private BlockShadowModels()
    {
    }

    static BlockStateModelSet models()
    {
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet();
    }

    static boolean isCutout(BlockState state, BlockPos pos)
    {
        List<BlockStateModelPart> parts = new ArrayList<>();
        models().get(state).collectParts(RandomSource.create(state.getSeed(pos)), parts);
        Direction[] faces = Direction.values();
        for (BlockStateModelPart part : parts)
        {
            for (int face = -1; face < faces.length; face++)
            {
                for (BakedQuad quad : part.getQuads(face < 0 ? null : faces[face]))
                {
                    if (quad.materialInfo().layer() == ChunkSectionLayer.CUTOUT)
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static float[] triangles(BlockStateModelSet models, BlockPos pos, BlockState state, RandomSource random,
                             double originX, double originY, double originZ)
    {
        random.setSeed(state.getSeed(pos));
        BlockStateModel model = models.get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(random, parts);
        FloatArrayList output = new FloatArrayList();
        Vec3 offset = state.getOffset(pos);
        double x = pos.getX() - originX + offset.x;
        double y = pos.getY() - originY + offset.y;
        double z = pos.getZ() - originZ + offset.z;
        Direction[] faces = Direction.values();
        for (BlockStateModelPart part : parts)
        {
            for (int face = -1; face < faces.length; face++)
            {
                for (BakedQuad quad : part.getQuads(face < 0 ? null : faces[face]))
                {
                    vertex(output, quad, 0, x, y, z);
                    vertex(output, quad, 1, x, y, z);
                    vertex(output, quad, 2, x, y, z);
                    vertex(output, quad, 0, x, y, z);
                    vertex(output, quad, 2, x, y, z);
                    vertex(output, quad, 3, x, y, z);
                }
            }
        }
        return output.toFloatArray();
    }

    private static void vertex(FloatArrayList output, BakedQuad quad, int corner, double x, double y, double z)
    {
        Vector3fc position = quad.position(corner);
        long uv = quad.packedUV(corner);
        output.add((float) (position.x() + x));
        output.add((float) (position.y() + y));
        output.add((float) (position.z() + z));
        output.add(UVPair.unpackU(uv));
        output.add(UVPair.unpackV(uv));
    }
}
