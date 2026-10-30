package org.qualet.irl.platform;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

public final class NativeFrame
{
    private NativeFrame()
    {
    }

    public static Camera camera(MinecraftClient mc)
    {
        return mc.gameRenderer.getCamera();
    }

    public static Vec3d position(Camera camera)
    {
        return camera.getPos();
    }

    public static void prepare(MinecraftClient mc, ClientWorld world, Camera camera)
    {
        mc.getEntityRenderDispatcher().configure(world, camera, mc.getCameraEntity());
    }
}
