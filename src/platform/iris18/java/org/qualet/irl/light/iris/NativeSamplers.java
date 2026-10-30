package org.qualet.irl.light.iris;

import org.qualet.irl.light.IrlSamplers;

import net.irisshaders.iris.gl.program.ProgramSamplers;

final class NativeSamplers
{
    private NativeSamplers()
    {
    }

    static void register(ProgramSamplers.Builder builder)
    {
        IrlSamplers.forEach((name, glId, glTarget) -> builder.addDynamicSampler(glId, name));
    }
}
