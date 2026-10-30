package org.qualet.irl.light.iris;

import org.qualet.irl.light.IrlSamplers;

import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.texture.TextureType;

final class NativeSamplers
{
    private NativeSamplers()
    {
    }

    static void register(ProgramSamplers.Builder builder)
    {
        IrlSamplers.forEach((name, glId, glTarget) -> builder.addDynamicSampler(TextureType.TEXTURE_2D, glId, () -> null, name));
    }
}
