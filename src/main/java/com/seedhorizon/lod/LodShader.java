package com.seedhorizon.lod;

import com.seedhorizon.SeedHorizonMod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Distant-Horizons-3.3-inspired textured GLSL 330 LOD shader:
 * <ul>
 *   <li>Samples Minecraft's live {@code BLOCK_ATLAS} tinted by biome & valley AO.</li>
 *   <li>Supports both opaque terrain and translucent water passes.</li>
 *   <li>Implements Distant Horizons' {@code fog.frag} exponential-squared distance fog
 *       combined with valley height fog ({@code INVERSE_MULTIPLY}) for natural depth.</li>
 * </ul>
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
            out float vWorldY;
            out float vDist;

            void main() {
                vWorldY = Position.y;
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
            in float vWorldY;
            in float vDist;

            uniform sampler2D u_BlockAtlas;
            uniform float u_ClipDistance;
            uniform float u_FogStart;
            uniform float u_FogEnd;
            uniform vec4 u_FogColor;
            uniform vec3 u_LightTint;
            uniform float u_SeaLevel;

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
                // Smooth 4x4 Bayer dithered near-border clip
                if (u_ClipDistance > 0.0 && vDist < u_ClipDistance * 1.10) {
                    float fade = smoothstep(u_ClipDistance * 0.72, u_ClipDistance * 1.10, vDist);
                    if (fade <= bayer4x4(gl_FragCoord.xy)) {
                        discard;
                    }
                }

                // Real Minecraft block texture tinted by biome & AO/slope color
                vec4 texel = texture(u_BlockAtlas, vUv);
                vec3 rgb = texel.rgb * vColor.rgb * u_LightTint;

                // Distant Horizons style Exponential-Squared far fog
                float fogSpan = max(u_FogEnd - u_FogStart, 16.0);
                float tFar = clamp((vDist - u_FogStart) / fogSpan, 0.0, 1.0);
                float farFog = clamp(1.0 - exp(-2.4 * tFar * tFar), 0.0, 1.0);
                farFog = max(farFog, smoothstep(0.88, 1.0, tFar));

                // Subtle Distant Horizons style valley height fog (softens distant valleys while peaks stay crisp)
                float distWeight = smoothstep(96.0, 320.0, vDist);
                float heightBelowMist = clamp((u_SeaLevel + 28.0 - vWorldY) / 48.0, 0.0, 1.0);
                float heightFog = heightBelowMist * heightBelowMist * 0.24 * distWeight;

                // DH INVERSE_MULTIPLY fog mixing
                float totalFog = clamp(1.0 - (1.0 - farFog) * (1.0 - heightFog), 0.0, 1.0);

                rgb = mix(rgb, u_FogColor.rgb, totalFog);
                fragColor = vec4(rgb, vColor.a);
            }
            """;

    private int programId = 0;
    private int uModelViewMat = -1;
    private int uProjMat = -1;
    private int uBlockAtlas = -1;
    private int uClipDistance = -1;
    private int uFogStart = -1;
    private int uFogEnd = -1;
    private int uFogColor = -1;
    private int uLightTint = -1;
    private int uSeaLevel = -1;

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
        uBlockAtlas = GL20.glGetUniformLocation(prog, "u_BlockAtlas");
        uClipDistance = GL20.glGetUniformLocation(prog, "u_ClipDistance");
        uFogStart = GL20.glGetUniformLocation(prog, "u_FogStart");
        uFogEnd = GL20.glGetUniformLocation(prog, "u_FogEnd");
        uFogColor = GL20.glGetUniformLocation(prog, "u_FogColor");
        uLightTint = GL20.glGetUniformLocation(prog, "u_LightTint");
        uSeaLevel = GL20.glGetUniformLocation(prog, "u_SeaLevel");
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
    public int getBlockAtlasLoc() { return uBlockAtlas; }
    public int getClipDistanceLoc() { return uClipDistance; }
    public int getFogStartLoc() { return uFogStart; }
    public int getFogEndLoc() { return uFogEnd; }
    public int getFogColorLoc() { return uFogColor; }
    public int getLightTintLoc() { return uLightTint; }
    public int getSeaLevelLoc() { return uSeaLevel; }

    public void destroy() {
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
    }
}
