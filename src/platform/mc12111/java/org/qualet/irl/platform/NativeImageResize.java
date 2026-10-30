package org.qualet.irl.platform;

import org.lwjgl.stb.STBImageResize;

import java.nio.ByteBuffer;

public final class NativeImageResize
{
    private NativeImageResize()
    {
    }

    public static void grayscale(ByteBuffer input, int width, int height, ByteBuffer output, int size)
    {
        STBImageResize.stbir_resize_uint8(input, width, height, 0, output, size, size, 0, 1);
    }
}
