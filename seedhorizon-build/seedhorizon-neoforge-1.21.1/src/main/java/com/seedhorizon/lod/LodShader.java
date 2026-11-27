package com.seedhorizon.lod;

import com.seedhorizon.SeedHorizonMod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Distant-Horizons-3.3-style textured GLSL 330 LOD shader:
 * samples Minecraft's live {@code BLOCK_ATLAS} ({@code u_BlockAtlas}) tinted by biome color,
 * performs pixel-exact vanilla depth occlusion ({@code u_McDepth}) + 4x4 Bayer dither border fade,
 * and blends smoothly into exponential-squared horizon fog.
 */
public final class LodShader {
    private static final String VERT_SRC = """
            #version 330 core
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            layout(location = 2) in vec4 Color;

            uniform mat4 u_ModelViewMat;
            uniform mat4 u_ProjMat;

            out vec2 vUv;
            out vec4 vColor;
            out float vDist;

            void main() {
                vec4 viewPos = u_ModelViewMat * vec4(Position, 1.0);
                gl_Position = u_ProjMat * viewPos;
                vUv = UV0;
                vColor = Color;
                vDist = length(viewPos.xyz);
            }
            """;

    private static final String FRAG_SRC = """
            #version 330 core
            in vec2 vUv;
            in vec4 vColor;
            in float vDist;

            uniform sampler2D u_McDepth;
            uniform sampler2D u_BlockAtlas;
            uniform vec2 u_ScreenSize;
            uniform float u_ClipDistance;
            uniform float u_FogStart;
            uniform float u_FogEnd;
            uniform vec4 u_FogColor;
            uniform vec3 u_LightTint;

            out vec4 fragColor;

            float bayer4x4(vec2 st) {
                int x = int(mod(st.x, 4.0));
                int y = int(mod(st.y, 4.0));
                float m[16] = float[16](
                     0.0,  8.0,  2.0, 10.0,
                    12.0,  4.0, 14.0,  6.0,
                     3.0, 11.0,  1.0,  9.0,
                    15.0,  7.0, 13.0,  5.0
                );
                return (m[y * 4 + x] + 0.5) / 16.0;
            }

            void main() {
                // 1. Pixel-exact vanilla/Sodium depth occlusion
                vec2 screenUv = gl_FragCoord.xy / u_ScreenSize;
                float mcDepth = texture(u_McDepth, screenUv).r;
                if (mcDepth < 0.9999) {
                    discard;
                }

                // 2. Smooth 4x4 Bayer dithered near-border transition
                if (u_ClipDistance > 0.0 && vDist < u_ClipDistance * 1.12) {
                    float fade = smoothstep(u_ClipDistance * 0.70, u_ClipDistance * 1.12, vDist);
                    if (fade <= bayer4x4(gl_FragCoord.xy)) {
                        discard;
                    }
                }

                // 3. Real Minecraft block texture tinted by biome & AO/slope color
                vec4 texel = texture(u_BlockAtlas, vUv);
                vec3 rgb = texel.rgb * vColor.rgb * u_LightTint;

                // 4. Exponential-squared horizon fog
                float fogSpan = max(u_FogEnd - u_FogStart, 16.0);
                float t = clamp((vDist - u_FogStart) / fogSpan, 0.0, 1.0);
                float fogFactor = clamp(1.0 - exp(-2.4 * t * t), 0.0, 1.0);
                fogFactor = max(fogFactor, smoothstep(0.88, 1.0, t));

                rgb = mix(rgb, u_FogColor.rgb, fogFactor);
                fragColor = vec4(rgb, 1.0);
            }
            """;

    private int programId = 0;
    private int uModelViewMat = -1;
    private int uProjMat = -1;
    private int uMcDepth = -1;
    private int uBlockAtlas = -1;
    private int uScreenSize = -1;
    private int uClipDistance = -1;
    private int uFogStart = -1;
    private int uFogEnd = -1;
    private int uFogColor = -1;
    private int uLightTint = -1;

    public boolean ensureInitialized() {
        if (programId != 0) {
            return true;
        }
        int vs = compile(GL20.GL_VERTEX_SHADER, VERT_SRC);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG_SRC);
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);

        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            SeedHorizonMod.LOGGER.error("LodShader link failed: {}", GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return false;
        }

        programId = prog;
        uModelViewMat = GL20.glGetUniformLocation(prog, "u_ModelViewMat");
        uProjMat = GL20.glGetUniformLocation(prog, "u_ProjMat");
        uMcDepth = GL20.glGetUniformLocation(prog, "u_McDepth");
        uBlockAtlas = GL20.glGetUniformLocation(prog, "u_BlockAtlas");
        uScreenSize = GL20.glGetUniformLocation(prog, "u_ScreenSize");
        uClipDistance = GL20.glGetUniformLocation(prog, "u_ClipDistance");
        uFogStart = GL20.glGetUniformLocation(prog, "u_FogStart");
        uFogEnd = GL20.glGetUniformLocation(prog, "u_FogEnd");
        uFogColor = GL20.glGetUniformLocation(prog, "u_FogColor");
        uLightTint = GL20.glGetUniformLocation(prog, "u_LightTint");
        return true;
    }

    private static int compile(int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            SeedHorizonMod.LOGGER.error("LodShader compile failed: {}", GL20.glGetShaderInfoLog(id));
        }
        return id;
    }

    public int getProgramId() { return programId; }
    public int getModelViewMatLoc() { return uModelViewMat; }
    public int getProjMatLoc() { return uProjMat; }
    public int getMcDepthLoc() { return uMcDepth; }
    public int getBlockAtlasLoc() { return uBlockAtlas; }
    public int getScreenSizeLoc() { return uScreenSize; }
    public int getClipDistanceLoc() { return uClipDistance; }
    public int getFogStartLoc() { return uFogStart; }
    public int getFogEndLoc() { return uFogEnd; }
    public int getFogColorLoc() { return uFogColor; }
    public int getLightTintLoc() { return uLightTint; }

    public void destroy() {
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
    }
}
