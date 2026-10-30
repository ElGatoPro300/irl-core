package org.qualet.irl.platform;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

public final class NativeFrame
{
    private NativeFrame()
    {
    }

    public static Camera camera(Minecraft mc)
    {
        return NativeCamera.get(mc);
    }

    public static Vec3 position(Camera camera)
    {
        return camera.position();
    }

    public static void prepare(Minecraft mc, ClientLevel world, Camera camera)
    {
        mc.getEntityRenderDispatcher().prepare(camera, mc.getCameraEntity());
    }
}
