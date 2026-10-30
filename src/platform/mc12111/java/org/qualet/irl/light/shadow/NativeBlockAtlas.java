package org.qualet.irl.light.shadow;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.SpriteAtlasTexture;

import com.mojang.blaze3d.textures.GpuTexture;

final class NativeBlockAtlas
{
    private NativeBlockAtlas()
    {
    }

    static int glId()
    {
        try
        {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null)
            {
                return 0;
            }
            AbstractTexture tex = mc.getTextureManager().getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
            if (tex == null)
            {
                return 0;
            }
            GpuTexture gt = tex.getGlTexture();
            if (gt instanceof GlTexture)
            {
                return ((GlTexture) gt).getGlId();
            }
            return 0;
        }
        catch (Throwable t)
        {
            return 0;
        }
    }
}
