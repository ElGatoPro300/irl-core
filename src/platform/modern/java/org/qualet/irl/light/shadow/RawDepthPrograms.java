package org.qualet.irl.light.shadow;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Shared raw depth programs for block and caster rasterization. */
final class RawDepthPrograms
{
    int program = 0;
    int uViewProjLoc = -1;
    int vao = 0;
    boolean glInit = false;
    boolean glBroken = false;
    int cutoutProgram = 0;
    int cutoutViewProjLoc = -1;
    int cutoutAtlasLoc = -1;
    int cutoutVao = 0;

    RawDepthPrograms()
    {
    }

    void initGl()
    {
        this.glInit = true;
        try
        {
            int vs = this.compile(GL20.GL_VERTEX_SHADER,
                "#version 150\n" +
                "in vec3 aPos;\n" +
                "uniform mat4 uViewProj;\n" +
                "void main() { gl_Position = uViewProj * vec4(aPos, 1.0); }\n");
            int fs = this.compile(GL20.GL_FRAGMENT_SHADER,
                "#version 150\n" +
                "void main() {}\n");

            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vs);
            GL20.glAttachShader(prog, fs);
            GL20.glBindAttribLocation(prog, 0, "aPos");
            GL20.glLinkProgram(prog);
            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
            {
                throw new IllegalStateException("link: " + GL20.glGetProgramInfoLog(prog));
            }
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            this.program = prog;
            this.uViewProjLoc = GL20.glGetUniformLocation(prog, "uViewProj");
            this.vao = GL30.glGenVertexArrays();

            // Cutout depth program: positions + atlas UVs, discards transparent
            // texels so light passes through the holes in the texture.
            int cvs = this.compile(GL20.GL_VERTEX_SHADER,
                "#version 150\n" +
                "in vec3 aPos;\n" +
                "in vec2 aUV;\n" +
                "uniform mat4 uViewProj;\n" +
                "out vec2 vUV;\n" +
                "void main() { vUV = aUV; gl_Position = uViewProj * vec4(aPos, 1.0); }\n");
            int cfs = this.compile(GL20.GL_FRAGMENT_SHADER,
                "#version 150\n" +
                "in vec2 vUV;\n" +
                "uniform sampler2D uAtlas;\n" +
                "void main() { if (texture(uAtlas, vUV).a < 0.5) discard; }\n");

            int cprog = GL20.glCreateProgram();
            GL20.glAttachShader(cprog, cvs);
            GL20.glAttachShader(cprog, cfs);
            GL20.glBindAttribLocation(cprog, 0, "aPos");
            GL20.glBindAttribLocation(cprog, 1, "aUV");
            GL20.glLinkProgram(cprog);
            if (GL20.glGetProgrami(cprog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
            {
                throw new IllegalStateException("cutout link: " + GL20.glGetProgramInfoLog(cprog));
            }
            GL20.glDeleteShader(cvs);
            GL20.glDeleteShader(cfs);

            this.cutoutProgram = cprog;
            this.cutoutViewProjLoc = GL20.glGetUniformLocation(cprog, "uViewProj");
            this.cutoutAtlasLoc = GL20.glGetUniformLocation(cprog, "uAtlas");
            this.cutoutVao = GL30.glGenVertexArrays();
        }
        catch (Throwable t)
        {
            this.glBroken = true;
            System.err.println("[irl-core] shadow depth program init failed: " + t);
            t.printStackTrace();
        }
    }

    private int compile(int type, String src)
    {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            throw new IllegalStateException("compile: " + GL20.glGetShaderInfoLog(sh));
        }
        return sh;
    }
}
