package org.qualet.irl.light.shadow;

import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;

/** Native buffer and matrix boundary for the immediate shadow backend. */
final class NativeLegacyRender
{
    private NativeLegacyRender()
    {
    }

    record Projection(Matrix4f matrix, VertexSorter type)
    {
    }

    static Projection saveProjection()
    {
        return new Projection(RenderSystem.getProjectionMatrix(), RenderSystem.getVertexSorting());
    }

    static void restoreProjection(Projection projection)
    {
        RenderSystem.setProjectionMatrix(projection.matrix(), projection.type());
    }

    static void projection(Matrix4f projection)
    {
        RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_DISTANCE);
    }

    static void positionShader()
    {
        RenderSystem.setShader(GameRenderer::getPositionProgram);
    }

    static BufferBuilder begin(VertexFormat.DrawMode mode, VertexFormat format)
    {
        return Tessellator.getInstance().begin(mode, format);
    }

    static VertexBuffer upload(BufferBuilder buffer)
    {
        BuiltBuffer built = buffer.endNullable();
        if (built == null)
        {
            return null;
        }
        VertexBuffer result = new VertexBuffer(VertexBuffer.Usage.STATIC);
        result.bind();
        result.upload(built);
        VertexBuffer.unbind();
        return result;
    }

    static void vertex(BufferBuilder buffer, float x, float y, float z)
    {
        buffer.vertex(x, y, z);
    }

    static void view(Matrix4f view, boolean push)
    {
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        if (push)
        {
            stack.pushMatrix();
        }
        stack.identity();
        stack.mul(view);
        RenderSystem.applyModelViewMatrix();
    }

    static void popView()
    {
        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
    }
}
