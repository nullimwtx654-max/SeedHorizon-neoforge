package com.voxelray.render;

import com.voxelray.VoxelRayMod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Manages the GLSL 330 core shader programs, 1/3-res shadow FBO, and full-res scene copy
 * texture for VoxelRay's native NeoForge post-processing pipeline.
 */
public final class VoxelRayShaderProgram {
    private static final String FULLSCREEN_VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);
                vUv = pos * 0.5 + 0.5;
                gl_Position = vec4(pos, 0.0, 1.0);
            }
            """;

    private static final String RAY_SHADOW_FRAG = """
            #version 330 core
            in vec2 vUv;
            out float outShadow;

            uniform sampler2D u_SceneDepth;
            uniform usampler2D u_VoxelTree;
            uniform mat4 u_InvProjView;
            uniform vec3 u_CameraPos;
            uniform vec3 u_SunDir;
            uniform ivec3 u_GridMinBlock;
            uniform int u_GridDiameter;
            uniform int u_SectionHeight;
            uniform int u_MaxSteps;

            uvec2 fetchNode(int index) {
                return texelFetch(u_VoxelTree, ivec2(index & 2047, index >> 11), 0).xy;
            }

            bool testBit64(uvec2 mask, int bit) {
                if (bit < 32) {
                    return ((mask.x >> uint(bit)) & 1u) != 0u;
                }
                return ((mask.y >> uint(bit - 32)) & 1u) != 0u;
            }

            int popcountBelow(uvec2 mask, int bit) {
                if (bit == 0) return 0;
                if (bit < 32) {
                    uint m = (1u << uint(bit)) - 1u;
                    return bitCount(mask.x & m);
                }
                int low = bitCount(mask.x);
                int hiBit = bit - 32;
                uint m = (hiBit == 32) ? 0xFFFFFFFFu : ((1u << uint(hiBit)) - 1u);
                return low + bitCount(mask.y & m);
            }

            bool isVoxelOccupied(ivec3 worldBlock) {
                ivec3 rel = worldBlock - u_GridMinBlock;
                ivec3 sec = rel >> 4;
                if (sec.x < 0 || sec.x >= u_GridDiameter ||
                    sec.z < 0 || sec.z >= u_GridDiameter ||
                    sec.y < 0 || sec.y >= u_SectionHeight) {
                    return false;
                }
                int dirIdx = (sec.y * u_GridDiameter + sec.z) * u_GridDiameter + sec.x;
                uint leafBase = fetchNode(dirIdx).x;
                if (leafBase == 0xFFFFFFFFu) {
                    return false;
                }

                ivec3 local = rel & 15;
                ivec3 c = local >> 2;
                int cBit = (c.y << 4) | (c.z << 2) | c.x;
                uvec2 coarse = fetchNode(int(leafBase));
                if (!testBit64(coarse, cBit)) {
                    return false;
                }

                int leafSlot = int(leafBase) + 1 + popcountBelow(coarse, cBit);
                uvec2 leaf = fetchNode(leafSlot);
                ivec3 l = local & 3;
                int lBit = (l.y << 4) | (l.z << 2) | l.x;
                return testBit64(leaf, lBit);
            }

            void main() {
                float depth = texture(u_SceneDepth, vUv).r;
                if (depth >= 0.9999) {
                    outShadow = 1.0;
                    return;
                }

                vec4 ndc = vec4(vUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                vec4 worldRel = u_InvProjView * ndc;
                worldRel.xyz /= worldRel.w;

                vec3 worldPos = worldRel.xyz + u_CameraPos;
                vec3 dir = normalize(u_SunDir);

                // Bias slightly along sun direction to avoid self-shadowing
                vec3 rayPos = worldPos + dir * 0.45;
                vec3 invDir = 1.0 / mix(dir, vec3(1e-5), lessThan(abs(dir), vec3(1e-5)));

                ivec3 gridMaxBlock = u_GridMinBlock + ivec3(u_GridDiameter * 16, u_SectionHeight * 16, u_GridDiameter * 16);

                // 3D DDA traversal through 64-tree
                ivec3 cell = ivec3(floor(rayPos));
                ivec3 stepDir = ivec3(sign(dir));
                vec3 nextBoundary = vec3(cell + max(stepDir, ivec3(0)));
                vec3 tMax = (nextBoundary - rayPos) * invDir;
                vec3 tDelta = abs(invDir);

                for (int i = 0; i < u_MaxSteps; i++) {
                    if (any(lessThan(cell, u_GridMinBlock)) || any(greaterThanEqual(cell, gridMaxBlock))) {
                        break;
                    }
                    if (isVoxelOccupied(cell)) {
                        outShadow = 0.0;
                        return;
                    }
                    if (tMax.x < tMax.y && tMax.x < tMax.z) {
                        cell.x += stepDir.x;
                        tMax.x += tDelta.x;
                    } else if (tMax.y < tMax.z) {
                        cell.y += stepDir.y;
                        tMax.y += tDelta.y;
                    } else {
                        cell.z += stepDir.z;
                        tMax.z += tDelta.z;
                    }
                }
                outShadow = 1.0;
            }
            """;

    private static final String COMPOSITE_GRADE_FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;

            uniform sampler2D u_SceneColor;
            uniform sampler2D u_SceneDepth;
            uniform sampler2D u_ShadowMask;
            uniform vec2 u_ShadowTexelSize;

            uniform float u_ShadowStrength;
            uniform vec3 u_ShadowTint;
            uniform float u_SunWarmth;
            uniform float u_DayFactor;
            uniform int u_EnableAces;
            uniform float u_Exposure;
            uniform float u_Contrast;
            uniform float u_Saturation;
            uniform float u_Vibrance;

            vec3 acesFilmic(vec3 x) {
                return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
            }

            void main() {
                vec4 scene = texture(u_SceneColor, vUv);
                float depth = texture(u_SceneDepth, vUv).r;
                vec3 color = scene.rgb;

                // Depth-aware bilateral upscale of 1/3-res shadow mask (skips sky)
                if (depth < 0.9999 && u_ShadowStrength > 0.001) {
                    vec2 offsets[4] = vec2[4](
                        vec2(-0.5, -0.5), vec2(0.5, -0.5),
                        vec2(-0.5,  0.5), vec2(0.5,  0.5)
                    );
                    float sumW = 0.0;
                    float sumS = 0.0;
                    for (int i = 0; i < 4; i++) {
                        vec2 suv = clamp(vUv + offsets[i] * u_ShadowTexelSize, vec2(0.001), vec2(0.999));
                        float s = texture(u_ShadowMask, suv).r;
                        float sd = texture(u_SceneDepth, suv).r;
                        float w = exp(-abs(depth - sd) * 1800.0);
                        sumS += s * w;
                        sumW += w;
                    }
                    float shadow = (sumW > 0.0001) ? (sumS / sumW) : texture(u_ShadowMask, vUv).r;
                    float effectiveShadow = mix(1.0, shadow, u_DayFactor);

                    vec3 shaded = color * u_ShadowTint * (1.0 - u_ShadowStrength);
                    vec3 lit = color * (1.0 + vec3(u_SunWarmth, u_SunWarmth * 0.55, -u_SunWarmth * 0.25) * u_DayFactor);
                    color = mix(shaded, lit, effectiveShadow);
                }

                // Exposure & Contrast
                color *= u_Exposure;
                color = (color - 0.5) * u_Contrast + 0.5;
                color = max(color, vec3(0.0));

                // ACES Filmic Tonemapping
                if (u_EnableAces == 1) {
                    color = acesFilmic(color);
                }

                // Vibrance + Saturation
                float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
                float maxC = max(color.r, max(color.g, color.b));
                float minC = min(color.r, min(color.g, color.b));
                float satAmount = u_Saturation + u_Vibrance * (1.0 - (maxC - minC));
                color = mix(vec3(luma), color, satAmount);

                fragColor = vec4(clamp(color, 0.0, 1.0), scene.a);
            }
            """;

    private int emptyVao = 0;
    private int rayProgram = 0;
    private int compositeProgram = 0;

    private int shadowFbo = 0;
    private int shadowTex = 0;
    private int sceneCopyTex = 0;
    private int cachedWidth = -1;
    private int cachedHeight = -1;
    private int cachedShadowW = -1;
    private int cachedShadowH = -1;

    public void ensureInitialized(int width, int height, int downscale) {
        if (emptyVao == 0) {
            emptyVao = GL30.glGenVertexArrays();
        }
        if (rayProgram == 0) {
            rayProgram = linkProgram(FULLSCREEN_VERT, RAY_SHADOW_FRAG);
        }
        if (compositeProgram == 0) {
            compositeProgram = linkProgram(FULLSCREEN_VERT, COMPOSITE_GRADE_FRAG);
        }

        int sw = Math.max(1, (width + downscale - 1) / downscale);
        int sh = Math.max(1, (height + downscale - 1) / downscale);

        if (width != cachedWidth || height != cachedHeight) {
            if (sceneCopyTex != 0) GL11.glDeleteTextures(sceneCopyTex);
            sceneCopyTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneCopyTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            cachedWidth = width;
            cachedHeight = height;
        }

        if (sw != cachedShadowW || sh != cachedShadowH) {
            if (shadowFbo != 0) GL30.glDeleteFramebuffers(shadowFbo);
            if (shadowTex != 0) GL11.glDeleteTextures(shadowTex);

            shadowTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, sw, sh, 0,
                    GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 0L);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            shadowFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, shadowTex, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

            cachedShadowW = sw;
            cachedShadowH = sh;
        }
    }

    public int getEmptyVao() { return emptyVao; }
    public int getRayProgram() { return rayProgram; }
    public int getCompositeProgram() { return compositeProgram; }
    public int getShadowFbo() { return shadowFbo; }
    public int getShadowTex() { return shadowTex; }
    public int getSceneCopyTex() { return sceneCopyTex; }
    public int getShadowWidth() { return cachedShadowW; }
    public int getShadowHeight() { return cachedShadowH; }

    private static int linkProgram(String vertSrc, String fragSrc) {
        int vs = compile(GL20.GL_VERTEX_SHADER, vertSrc);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, fragSrc);
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            VoxelRayMod.LOGGER.error("VoxelRay shader link error: {}", GL20.glGetProgramInfoLog(prog));
        }
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return prog;
    }

    private static int compile(int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            VoxelRayMod.LOGGER.error("VoxelRay shader compile error: {}", GL20.glGetShaderInfoLog(id));
        }
        return id;
    }

    public void destroy() {
        if (emptyVao != 0) { GL30.glDeleteVertexArrays(emptyVao); emptyVao = 0; }
        if (rayProgram != 0) { GL20.glDeleteProgram(rayProgram); rayProgram = 0; }
        if (compositeProgram != 0) { GL20.glDeleteProgram(compositeProgram); compositeProgram = 0; }
        if (shadowFbo != 0) { GL30.glDeleteFramebuffers(shadowFbo); shadowFbo = 0; }
        if (shadowTex != 0) { GL11.glDeleteTextures(shadowTex); shadowTex = 0; }
        if (sceneCopyTex != 0) { GL11.glDeleteTextures(sceneCopyTex); sceneCopyTex = 0; }
        cachedWidth = cachedHeight = cachedShadowW = cachedShadowH = -1;
    }
}
