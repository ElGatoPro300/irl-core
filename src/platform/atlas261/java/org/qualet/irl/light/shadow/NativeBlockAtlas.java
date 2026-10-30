package org.qualet.irl.light.shadow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;

import com.mojang.blaze3d.opengl.GlTexture;
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
            Minecraft mc = Minecraft.getInstance();
            if (mc == null)
            {
                return 0;
            }
            AbstractTexture tex = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
            if (tex == null)
            {
                return 0;
            }
            GpuTexture gt = tex.getTexture();
            if (gt instanceof GlTexture)
            {
                return ((GlTexture) gt).glId();
            }
            return 0;
        }
        catch (Throwable t)
        {
            return 0;
        }
    }
}
