package org.qualet.irl.platform;

import com.mojang.blaze3d.platform.GlStateManager;

/** Minecraft state-cache boundary. */
public final class NativeGl
{
    private NativeGl()
    {
    }

    public static int _genTexture()
    {
        return GlStateManager._genTexture();
    }

    public static void _bindTexture(int id)
    {
        GlStateManager._bindTexture(id);
    }

    public static void _deleteTexture(int id)
    {
        GlStateManager._deleteTexture(id);
    }

    public static void _glUseProgram(int id)
    {
        GlStateManager._glUseProgram(id);
    }

    public static void _glBindFramebuffer(int target, int id)
    {
        GlStateManager._glBindFramebuffer(target, id);
    }

    public static void _glDeleteFramebuffers(int id)
    {
        GlStateManager._glDeleteFramebuffers(id);
    }
}
