package org.qualet.irl.light.shadow;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

final class NativeBlockGeometry
{
    private NativeBlockGeometry()
    {
    }

    static boolean available()
    {
        return Minecraft.getInstance().level != null;
    }

    static float[] triangles(BlockPos pos, RandomSource random, double x, double y, double z)
    {
        BlockState state = Minecraft.getInstance().level.getBlockState(pos);
        if (state.isAir())
        {
            return new float[0];
        }
        return BlockShadowModels.triangles(BlockShadowModels.models(), pos, state, random, x, y, z);
    }
}
