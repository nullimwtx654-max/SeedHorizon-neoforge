package com.seedhorizon.lod;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.seedhorizon.config.SeedHorizonConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A 1x1-chunk (16x16 block) textured LOD section using {@link DefaultVertexFormat#POSITION_TEX_COLOR}
 * mapped directly onto Minecraft's live {@code BLOCK_ATLAS} textures, with neighbor-culled layered cliffs,
 * valley AO, and multi-tier textured 3D trees.
 */
public final class LodSection implements AutoCloseable {
    public static final int SIZE_CHUNKS = 1;
    public static final int SIZE_BLOCKS = 16;

    private record TreeInstance(float cx, float groundY, float cz, SurfaceColorResolver.TreeStyle style,
                                int trunkH, int canopyH,
                                LodAtlasSprites.Material trunkMat,
                                LodAtlasSprites.Material leafMat,
                                int leafTint) {
    }

    private final int chunkX;
    private final int chunkZ;
    private final int stepBlocks;
    private final int gridWidth;
    private final int extW;
    private final int[] renderYs;
    private final SurfaceColorResolver.ColumnPalette[] palettes;
    private final boolean[] valid;
    private final List<TreeInstance> trees;
    private final int seaLevel;
    private final AABB bounds;

    @Nullable
    private VertexBuffer vertexBuffer;
    private volatile boolean uploaded = false;
    private volatile boolean closed = false;

    private LodSection(int chunkX, int chunkZ, int stepBlocks, int gridWidth, int extW,
                       int[] renderYs, SurfaceColorResolver.ColumnPalette[] palettes,
                       boolean[] valid, List<TreeInstance> trees, int seaLevel, int minY, int maxY) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.stepBlocks = stepBlocks;
        this.gridWidth = gridWidth;
        this.extW = extW;
        this.renderYs = renderYs;
        this.palettes = palettes;
        this.valid = valid;
        this.trees = trees;
        this.seaLevel = seaLevel;

        int worldX = chunkX * SIZE_BLOCKS;
        int worldZ = chunkZ * SIZE_BLOCKS;
        this.bounds = new AABB(worldX, minY, worldZ, worldX + SIZE_BLOCKS, maxY, worldZ + SIZE_BLOCKS);
    }

    public static LodSection generate(SeedStateManager.SeedContext ctx, int chunkX, int chunkZ, int stepBlocks) {
        int gridWidth = Math.max(1, SIZE_BLOCKS / stepBlocks);
        int extW = gridWidth + 2;
        SeedColumnSampler.SampledColumn[] samples = new SeedColumnSampler.SampledColumn[extW * extW];

        int baseX = chunkX * SIZE_BLOCKS;
        int baseZ = chunkZ * SIZE_BLOCKS;

        for (int gz = 0; gz < extW; gz++) {
            for (int gx = 0; gx < extW; gx++) {
                int wx = baseX + (gx - 1) * stepBlocks + (stepBlocks >> 1);
                int wz = baseZ + (gz - 1) * stepBlocks + (stepBlocks >> 1);
                samples[gz * extW + gx] = SeedColumnSampler.sample(ctx, wx, wz);
            }
        }

        int[] renderYs = new int[extW * extW];
        boolean[] valid = new boolean[extW * extW];
        for (int i = 0; i < samples.length; i++) {
            if (samples[i].valid()) {
                valid[i] = true;
                renderYs[i] = samples[i].renderY();
            }
        }

        SurfaceColorResolver.ColumnPalette[] palettes = new SurfaceColorResolver.ColumnPalette[gridWidth * gridWidth];
        List<TreeInstance> trees = new ArrayList<>();

        boolean treesEnabled = SeedHorizonConfig.CLIENT.enableLodTrees.get() && stepBlocks <= 4;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int seaLevel = ctx.settings().seaLevel();

        for (int gz = 0; gz < gridWidth; gz++) {
            for (int gx = 0; gx < gridWidth; gx++) {
                int idx = gz * gridWidth + gx;
                int sx = gx + 1;
                int sz = gz + 1;
                int eIdx = sz * extW + sx;
                SeedColumnSampler.SampledColumn col = samples[eIdx];
                if (!col.valid()) {
                    continue;
                }

                int y = col.renderY();
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;

                int hW = valid[sz * extW + (sx - 1)] ? renderYs[sz * extW + (sx - 1)] : y;
                int hE = valid[sz * extW + (sx + 1)] ? renderYs[sz * extW + (sx + 1)] : y;
                int hN = valid[(sz - 1) * extW + sx] ? renderYs[(sz - 1) * extW + sx] : y;
                int hS = valid[(sz + 1) * extW + sx] ? renderYs[(sz + 1) * extW + sx] : y;

                float dx = (hW - hE);
                float dy = 2.0f * stepBlocks;
                float dz = (hN - hS);
                float invLen = 1.0f / (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float nx = dx * invLen;
                float ny = dy * invLen;
                float nz = dz * invLen;

                int higherCount = (hW > y ? 1 : 0) + (hE > y ? 1 : 0) + (hN > y ? 1 : 0) + (hS > y ? 1 : 0);
                float ao = 1.0f - higherCount * 0.045f;
                float sunDot = Mth.clamp(0.86f + (nx * 0.12f + ny * 0.14f - nz * 0.08f), 0.78f, 1.06f) * ao;

                int wx = baseX + gx * stepBlocks + (stepBlocks >> 1);
                int wz = baseZ + gz * stepBlocks + (stepBlocks >> 1);
                palettes[idx] = SurfaceColorResolver.resolvePalette(
                        col.biome(), wx, col.surfaceY(), wz, col.seaLevel(),
                        col.isWater(), col.waterDepth(), ny, sunDot, col.isNether(), col.isEnd());

                if (treesEnabled && !col.isWater() && !col.isNether() && !col.isEnd() && ny > 0.74f) {
                    boolean onTreeGrid = (stepBlocks == 1) ? ((gx & 3) == 1 && (gz & 3) == 1)
                            : (stepBlocks == 2 ? ((gx & 1) == 0 && (gz & 1) == 0) : true);
                    if (onTreeGrid) {
                        SurfaceColorResolver.TreeStyle style = SurfaceColorResolver.getTreeStyle(col.biome());
                        if (style != SurfaceColorResolver.TreeStyle.NONE) {
                            int th = SurfaceColorResolver.mixHash(wx, wz);
                            int chanceMask = switch (style) {
                                case JUNGLE, DARK_OAK -> 1;
                                case SPRUCE, OAK, BIRCH, CHERRY -> 3;
                                default -> 7;
                            };
                            if ((th & chanceMask) == 0) {
                                int trunkH = switch (style) {
                                    case SPRUCE -> 2 + ((th >> 4) & 1);
                                    case JUNGLE -> 4 + ((th >> 4) & 3);
                                    default -> 2 + ((th >> 4) & 1);
                                };
                                int canopyH = switch (style) {
                                    case SPRUCE -> 5 + ((th >> 6) & 3);
                                    case JUNGLE -> 4 + ((th >> 6) & 2);
                                    default -> 3 + ((th >> 6) & 1);
                                };
                                int leafTint = SurfaceColorResolver.resolveTreeCanopyTint(col.biome(), style, wx, wz);
                                float cx = baseX + gx * stepBlocks + stepBlocks * 0.5f;
                                float cz = baseZ + gz * stepBlocks + stepBlocks * 0.5f;
                                trees.add(new TreeInstance(cx, y + 0.94f, cz, style, trunkH, canopyH,
                                        SurfaceColorResolver.getTrunkMaterial(style),
                                        SurfaceColorResolver.getLeafMaterial(style), leafTint));
                                if (y + trunkH + canopyH + 2 > maxY) {
                                    maxY = y + trunkH + canopyH + 2;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (minY == Integer.MAX_VALUE) {
            minY = 0;
            maxY = 64;
        }
        return new LodSection(chunkX, chunkZ, stepBlocks, gridWidth, extW,
                renderYs, palettes, valid, trees, seaLevel, minY - 32, maxY + 4);
    }

    /**
     * Must be called on the render thread so {@link LodAtlasSprites} can query the live block atlas.
     */
    public void uploadToGpu() {
        if (closed || uploaded) {
            return;
        }

        int baseX = chunkX * SIZE_BLOCKS;
        int baseZ = chunkZ * SIZE_BLOCKS;
        float deepFloor = seaLevel - 32.0f;

        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(20480)) {
            BufferBuilder builder = new BufferBuilder(byteBuffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            boolean hasGeometry = false;

            for (int gz = 0; gz < gridWidth; gz++) {
                for (int gx = 0; gx < gridWidth; gx++) {
                    int idx = gz * gridWidth + gx;
                    int sx = gx + 1;
                    int sz = gz + 1;
                    int eIdx = sz * extW + sx;
                    if (!valid[eIdx] || palettes[idx] == null) {
                        continue;
                    }
                    hasGeometry = true;

                    float x0 = baseX + gx * stepBlocks;
                    float z0 = baseZ + gz * stepBlocks;
                    float x1 = x0 + stepBlocks;
                    float z1 = z0 + stepBlocks;
                    float yTop = renderYs[eIdx] + 0.94f;

                    SurfaceColorResolver.ColumnPalette pal = palettes[idx];
                    LodAtlasSprites.SpriteUv topUv = LodAtlasSprites.getUv(pal.topMat());
                    emitTopFace(builder, x0, yTop, z0, x1, z1, topUv, pal.topTint());

                    float yN = valid[(sz - 1) * extW + sx] ? (renderYs[(sz - 1) * extW + sx] + 0.94f) : deepFloor;
                    float yS = valid[(sz + 1) * extW + sx] ? (renderYs[(sz + 1) * extW + sx] + 0.94f) : deepFloor;
                    float yW = valid[sz * extW + (sx - 1)] ? (renderYs[sz * extW + (sx - 1)] + 0.94f) : deepFloor;
                    float yE = valid[sz * extW + (sx + 1)] ? (renderYs[sz * extW + (sx + 1)] + 0.94f) : deepFloor;

                    if (gz == 0) yN = Math.min(yN, yTop - 3.0f);
                    if (gz == gridWidth - 1) yS = Math.min(yS, yTop - 3.0f);
                    if (gx == 0) yW = Math.min(yW, yTop - 3.0f);
                    if (gx == gridWidth - 1) yE = Math.min(yE, yTop - 3.0f);

                    LodAtlasSprites.SpriteUv fillUv = LodAtlasSprites.getUv(pal.fillerMat());
                    LodAtlasSprites.SpriteUv stoneUv = LodAtlasSprites.getUv(pal.stoneMat());

                    if (yTop > yN) emitLayeredSide(builder, x1, z0, x0, z0, yTop, yN, fillUv, pal.fillerTint(), stoneUv, pal.stoneTint(), 0.80f);
                    if (yTop > yS) emitLayeredSide(builder, x0, z1, x1, z1, yTop, yS, fillUv, pal.fillerTint(), stoneUv, pal.stoneTint(), 0.80f);
                    if (yTop > yW) emitLayeredSide(builder, x0, z0, x0, z1, yTop, yW, fillUv, pal.fillerTint(), stoneUv, pal.stoneTint(), 0.86f);
                    if (yTop > yE) emitLayeredSide(builder, x1, z1, x1, z0, yTop, yE, fillUv, pal.fillerTint(), stoneUv, pal.stoneTint(), 0.86f);
                }
            }

            // Textured multi-tier 3D trees
            for (TreeInstance t : trees) {
                hasGeometry = true;
                LodAtlasSprites.SpriteUv trunkUv = LodAtlasSprites.getUv(t.trunkMat());
                LodAtlasSprites.SpriteUv leafUv = LodAtlasSprites.getUv(t.leafMat());
                float tr = Math.max(0.45f, stepBlocks * 0.28f);
                float trunkTop = t.groundY + t.trunkH;
                emitBox(builder, t.cx - tr, t.groundY, t.cz - tr, t.cx + tr, trunkTop + 0.5f, t.cz + tr, trunkUv, 0xFFFFFFFF);

                if (t.style == SurfaceColorResolver.TreeStyle.SPRUCE) {
                    float r1 = Math.max(1.8f, stepBlocks * 0.9f);
                    float r2 = Math.max(1.2f, stepBlocks * 0.6f);
                    float r3 = Math.max(0.6f, stepBlocks * 0.3f);
                    float h1 = t.canopyH * 0.42f;
                    float h2 = t.canopyH * 0.34f;
                    float h3 = t.canopyH * 0.24f;
                    emitBox(builder, t.cx - r1, trunkTop, t.cz - r1, t.cx + r1, trunkTop + h1, t.cz + r1,
                            leafUv, SurfaceColorResolver.applyShade(t.leafTint(), 0.90f));
                    emitBox(builder, t.cx - r2, trunkTop + h1, t.cz - r2, t.cx + r2, trunkTop + h1 + h2, t.cz + r2,
                            leafUv, t.leafTint());
                    emitBox(builder, t.cx - r3, trunkTop + h1 + h2, t.cz - r3, t.cx + r3, trunkTop + h1 + h2 + h3, t.cz + r3,
                            leafUv, SurfaceColorResolver.applyShade(t.leafTint(), 1.06f));
                } else {
                    float r1 = Math.max(2.0f, stepBlocks * 0.95f);
                    float r2 = Math.max(1.2f, stepBlocks * 0.58f);
                    float h1 = t.canopyH * 0.62f;
                    float h2 = t.canopyH * 0.38f;
                    emitBox(builder, t.cx - r1, trunkTop, t.cz - r1, t.cx + r1, trunkTop + h1, t.cz + r1,
                            leafUv, t.leafTint());
                    emitBox(builder, t.cx - r2, trunkTop + h1, t.cz - r2, t.cx + r2, trunkTop + h1 + h2, t.cz + r2,
                            leafUv, SurfaceColorResolver.applyShade(t.leafTint(), 1.06f));
                }
            }

            if (hasGeometry) {
                MeshData mesh = builder.build();
                if (mesh != null) {
                    VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    vbo.bind();
                    vbo.upload(mesh);
                    VertexBuffer.unbind();
                    this.vertexBuffer = vbo;
                }
            }
            this.uploaded = true;
        }
    }

    private static void emitTopFace(BufferBuilder builder, float x0, float y, float z0, float x1, float z1,
                                    LodAtlasSprites.SpriteUv uv, int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        builder.addVertex(x0, y, z0).setUv(uv.u0(), uv.v0()).setColor(r, g, b, a);
        builder.addVertex(x0, y, z1).setUv(uv.u0(), uv.v1()).setColor(r, g, b, a);
        builder.addVertex(x1, y, z1).setUv(uv.u1(), uv.v1()).setColor(r, g, b, a);
        builder.addVertex(x1, y, z0).setUv(uv.u1(), uv.v0()).setColor(r, g, b, a);
    }

    private static void emitLayeredSide(BufferBuilder builder, float xA, float zA, float xB, float zB,
                                        float yTop, float yBot,
                                        LodAtlasSprites.SpriteUv fillUv, int fillTint,
                                        LodAtlasSprites.SpriteUv stoneUv, int stoneTint,
                                        float shade) {
        float fillerBot = Math.max(yBot, yTop - 3.0f);
        emitQuad(builder, xA, zA, xB, zB, yTop, fillerBot, fillUv, SurfaceColorResolver.applyShade(fillTint, shade));
        if (fillerBot > yBot) {
            emitQuad(builder, xA, zA, xB, zB, fillerBot, yBot, stoneUv, SurfaceColorResolver.applyShade(stoneTint, shade));
        }
    }

    private static void emitQuad(BufferBuilder builder, float xA, float zA, float xB, float zB,
                                 float yTop, float yBot, LodAtlasSprites.SpriteUv uv, int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        builder.addVertex(xA, yTop, zA).setUv(uv.u0(), uv.v0()).setColor(r, g, b, a);
        builder.addVertex(xA, yBot, zA).setUv(uv.u0(), uv.v1()).setColor(r, g, b, a);
        builder.addVertex(xB, yBot, zB).setUv(uv.u1(), uv.v1()).setColor(r, g, b, a);
        builder.addVertex(xB, yTop, zB).setUv(uv.u1(), uv.v0()).setColor(r, g, b, a);
    }

    private static void emitBox(BufferBuilder builder, float x0, float y0, float z0, float x1, float y1, float z1,
                                LodAtlasSprites.SpriteUv uv, int argb) {
        emitTopFace(builder, x0, y1, z0, x1, z1, uv, argb);
        int ns = SurfaceColorResolver.applyShade(argb, 0.80f);
        int ew = SurfaceColorResolver.applyShade(argb, 0.86f);
        emitQuad(builder, x1, z0, x0, z0, y1, y0, uv, ns);
        emitQuad(builder, x0, z1, x1, z1, y1, y0, uv, ns);
        emitQuad(builder, x0, z0, x0, z1, y1, y0, uv, ew);
        emitQuad(builder, x1, z1, x1, z0, y1, y0, uv, ew);
    }

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public AABB getBounds() { return bounds; }
    @Nullable
    public VertexBuffer getVertexBuffer() { return vertexBuffer; }
    public boolean isUploaded() { return uploaded; }

    @Override
    public void close() {
        closed = true;
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
