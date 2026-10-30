package org.qualet.irl.light.shadow;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

final class NativeShadowBlocks
{
    private NativeShadowBlocks()
    {
    }

    static boolean isCutout(BlockState state, BlockPos pos)
    {
        return BlockShadowModels.isCutout(state, pos);
    }

    static VoxelShape shape(BlockState state, BlockGetter world, BlockPos pos)
    {
        return state.getOcclusionShape();
    }

    static boolean sameModel(BlockState left, BlockState right)
    {
        return BlockShadowModels.models().get(left) == BlockShadowModels.models().get(right);
    }
}
