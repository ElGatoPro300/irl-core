package org.qualet.irl.light.shadow;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

final class NativeShadowBlocks
{
    private NativeShadowBlocks()
    {
    }

    static boolean isCutout(BlockState state, BlockPos pos)
    {
        return RenderLayers.getBlockLayer(state) == RenderLayer.getCutout() || RenderLayers.getBlockLayer(state) == RenderLayer.getCutoutMipped();
    }

    static VoxelShape shape(BlockState state, BlockView world, BlockPos pos)
    {
        return state.getCullingShape(world, pos);
    }

    static boolean sameModel(BlockState left, BlockState right)
    {
        return MinecraftClient.getInstance().getBlockRenderManager().getModel(left) == MinecraftClient.getInstance().getBlockRenderManager().getModel(right);
    }
}
