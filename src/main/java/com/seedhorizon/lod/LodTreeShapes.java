package com.seedhorizon.lod;

import com.mojang.blaze3d.vertex.BufferBuilder;

/**
 * Stamps organic, irregular Minecraft-style multi-voxel tree silhouettes (jagged spruce whorls,
 * rounded oak/birch/cherry crowns with cut corners, flat-top acacia, and tall jungle canopies)
 * with visible trunks beneath so distant forests match real Minecraft/SeedGen silhouettes.
 */
public final class LodTreeShapes {
    private LodTreeShapes() {
    }

    public static void emitTree(
            BufferBuilder builder,
            float cx,
            float groundY,
            float cz,
            SurfaceColorResolver.TreeStyle style,
            int seedHash,
            int stepBlocks,
            LodAtlasSprites.Material trunkMat,
            LodAtlasSprites.Material leafMat,
            int leafTint) {

        LodAtlasSprites.SpriteUv trunkUv = LodAtlasSprites.getUv(trunkMat);
        LodAtlasSprites.SpriteUv leafUv = LodAtlasSprites.getUv(leafMat);

        int darkLeaf = SurfaceColorResolver.applyShade(leafTint, 0.86f);
        int midLeaf = leafTint;
        int brightLeaf = SurfaceColorResolver.applyShade(leafTint, 1.07f);

        float scale = Math.max(1.0f, stepBlocks * 0.55f);

        switch (style) {
            case SPRUCE -> {
                // Tall tapered spruce with alternating wide/narrow whorls (matches Minecraft pine/spruce)
                float trunkH = 2.0f + ((seedHash >> 4) & 1);
                float tr = 0.48f * scale;
                emitBox(builder, cx - tr, groundY, cz - tr, cx + tr, groundY + trunkH + 1.0f, cz + tr, trunkUv, 0xFFFFFFFF);

                float y = groundY + trunkH;
                int extraWhorls = (seedHash >> 6) & 1;
                // Bottom wide whorl (cross-shaped / rounded)
                emitPlusLayer(builder, cx, y, cz, 2.3f * scale, 1.8f, leafUv, darkLeaf);
                y += 1.8f;
                // Narrow waist
                emitBox(builder, cx - 1.3f * scale, y, cz - 1.3f * scale, cx + 1.3f * scale, y + 1.2f, cz + 1.3f * scale, leafUv, midLeaf);
                y += 1.2f;
                // Mid whorl
                emitPlusLayer(builder, cx, y, cz, 1.9f * scale, 1.5f, leafUv, midLeaf);
                y += 1.5f;
                if (extraWhorls == 1) {
                    emitBox(builder, cx - 1.1f * scale, y, cz - 1.1f * scale, cx + 1.1f * scale, y + 1.3f, cz + 1.1f * scale, leafUv, midLeaf);
                    y += 1.3f;
                }
                // Upper spire + needle tip
                emitBox(builder, cx - 0.85f * scale, y, cz - 0.85f * scale, cx + 0.85f * scale, y + 1.4f, cz + 0.85f * scale, leafUv, brightLeaf);
                y += 1.4f;
                emitBox(builder, cx - 0.42f * scale, y, cz - 0.42f * scale, cx + 0.42f * scale, y + 1.3f, cz + 0.42f * scale, leafUv, brightLeaf);
            }
            case DARK_OAK -> {
                // Thick 2x2 trunk + wide dense dome
                float trunkH = 3.0f + ((seedHash >> 4) & 1);
                float tr = 0.85f * scale;
                emitBox(builder, cx - tr, groundY, cz - tr, cx + tr, groundY + trunkH + 0.6f, cz + tr, trunkUv, 0xFFFFFFFF);
                float y = groundY + trunkH;
                emitPlusLayer(builder, cx, y, cz, 2.7f * scale, 2.2f, leafUv, darkLeaf);
                emitBox(builder, cx - 1.9f * scale, y + 2.2f, cz - 1.9f * scale, cx + 1.9f * scale, y + 3.6f, cz + 1.9f * scale, leafUv, brightLeaf);
            }
            case JUNGLE -> {
                // Tall jungle tree with elevated multi-lobe crown
                float trunkH = 5.0f + ((seedHash >> 4) & 3);
                float tr = 0.65f * scale;
                emitBox(builder, cx - tr, groundY, cz - tr, cx + tr, groundY + trunkH + 0.5f, cz + tr, trunkUv, 0xFFFFFFFF);
                float y = groundY + trunkH;
                emitPlusLayer(builder, cx, y, cz, 2.6f * scale, 2.0f, leafUv, midLeaf);
                emitBox(builder, cx - 1.6f * scale, y + 2.0f, cz - 1.6f * scale, cx + 1.6f * scale, y + 3.2f, cz + 1.6f * scale, leafUv, brightLeaf);
            }
            case ACACIA -> {
                // Lean/offset trunk + wide flat umbrella canopy
                float trunkH = 3.5f + ((seedHash >> 4) & 1);
                float ox = (((seedHash >> 7) & 1) == 0 ? 0.6f : -0.6f) * scale;
                float tr = 0.45f * scale;
                emitBox(builder, cx - tr, groundY, cz - tr, cx + tr, groundY + trunkH, cz + tr, trunkUv, 0xFFFFFFFF);
                float y = groundY + trunkH;
                emitPlusLayer(builder, cx + ox, y, cz, 2.6f * scale, 1.1f, leafUv, midLeaf);
                emitBox(builder, cx + ox - 1.5f * scale, y + 1.1f, cz - 1.5f * scale, cx + ox + 1.5f * scale, y + 1.9f, cz + 1.5f * scale, leafUv, brightLeaf);
            }
            default -> {
                // Organic Oak / Birch / Cherry: visible trunk + cut-corner rounded crown + slight asymmetry
                float trunkH = 2.4f + ((seedHash >> 4) & 1);
                float tr = 0.48f * scale;
                emitBox(builder, cx - tr, groundY, cz - tr, cx + tr, groundY + trunkH + 0.6f, cz + tr, trunkUv, 0xFFFFFFFF);
                float y = groundY + trunkH;
                float ox = (((seedHash >> 8) & 3) - 1.5f) * 0.18f * scale;
                float oz = (((seedHash >> 10) & 3) - 1.5f) * 0.18f * scale;
                emitPlusLayer(builder, cx + ox, y, cz + oz, 2.15f * scale, 2.0f, leafUv, midLeaf);
                emitBox(builder, cx + ox - 1.25f * scale, y + 2.0f, cz + oz - 1.25f * scale,
                        cx + ox + 1.25f * scale, y + 3.2f, cz + oz + 1.25f * scale, leafUv, brightLeaf);
            }
        }
    }

    /**
     * Emits a rounded/cut-corner Minecraft leaf layer using two overlapping perpendicular boxes (a plus shape),
     * avoiding harsh square silhouettes.
     */
    private static void emitPlusLayer(BufferBuilder b, float cx, float y0, float cz,
                                      float radius, float height, LodAtlasSprites.SpriteUv uv, int argb) {
        float inner = radius * 0.68f;
        emitBox(b, cx - radius, y0, cz - inner, cx + radius, y0 + height, cz + inner, uv, argb);
        emitBox(b, cx - inner, y0, cz - radius, cx + inner, y0 + height, cz + radius, uv, argb);
    }

    private static void emitBox(BufferBuilder b, float x0, float y0, float z0, float x1, float y1, float z1,
                                LodAtlasSprites.SpriteUv uv, int argb) {
        int a = (argb >>> 24) & 0xFF;
        // Top (+Y)
        emitQuad(b, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, uv, argb, a);
        // Bottom (-Y, darker underside so trees look grounded from below)
        int bot = SurfaceColorResolver.applyShade(argb, 0.65f);
        emitQuad(b, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, uv, bot, a);
        // Sides
        int ns = SurfaceColorResolver.applyShade(argb, 0.80f);
        int ew = SurfaceColorResolver.applyShade(argb, 0.86f);
        emitQuad(b, x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0, uv, ns, a);
        emitQuad(b, x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1, uv, ns, a);
        emitQuad(b, x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, uv, ew, a);
        emitQuad(b, x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0, uv, ew, a);
    }

    private static void emitQuad(BufferBuilder b,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3,
                                 LodAtlasSprites.SpriteUv uv, int argb, int a) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, bl = argb & 0xFF;
        b.addVertex(x0, y0, z0).setUv(uv.u0(), uv.v0()).setColor(r, g, bl, a);
        b.addVertex(x1, y1, z1).setUv(uv.u0(), uv.v1()).setColor(r, g, bl, a);
        b.addVertex(x2, y2, z2).setUv(uv.u1(), uv.v1()).setColor(r, g, bl, a);
        b.addVertex(x3, y3, z3).setUv(uv.u1(), uv.v0()).setColor(r, g, bl, a);
    }
}
