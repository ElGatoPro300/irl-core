package org.qualet.irl.platform;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;

final class NativeCamera
{
    private NativeCamera()
    {
    }

    static Camera get(Minecraft client)
    {
        return client.gameRenderer.mainCamera();
    }
}
