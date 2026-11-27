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
 * A 1x1-chunk (16x16 block) Distant-Horizons-style LOD section with:
 * <ul>
 *   <li>Separate opaque terrain VBO (including real underwater sand/gravel seabed) and
 *       translucent water VBO ({@code alpha = 185}) with DH-style 2D greedy quad merging.</li>
 *   <li>Neighbor-culled layered cliffs (grass -> dirt -> stone) and valley Ambient Occlusion.</li>
 *   <li>Organic multi-voxel 3D tree silhouettes via {@link LodTreeShapes}.</li>
 * </ul>
 */
public final class LodSection implements AutoCloseable {
    public static final int SIZE_CHUNKS = 1;
    public static final int SIZE_BLOCKS = 16;

    private record TreeInstance(float cx, float groundY, float cz, SurfaceColorResolver.TreeStyle style,
                                int seedHash, LodAtlasSprites.Material trunkMat,
                                LodAtlasSprites.Material leafMat, int leafTint) {
    }

    private final int chunkX;
    private final int chunkZ;
    private final int stepBlocks;
    private final int gridWidth;
    private final int extW;
    private final int[] solidYs;
    private final boolean[] isWaterGrid;
    private final int[] waterTints;
    private final SurfaceColorResolver.ColumnPalette[] palettes;
    private final boolean[] valid;
    private final List<TreeInstance> trees;
    private final int seaLevel;
    private final AABB bounds;
    private final boolean realSnapshot;

    @Nullable
    private VertexBuffer opaqueVbo;
    @Nullable
    private VertexBuffer waterVbo;
    private volatile boolean uploaded = false;
    private volatile boolean closed = false;

    private LodSection(int chunkX, int chunkZ, int stepBlocks, int gridWidth, int extW,
                       int[] solidYs, boolean[] isWaterGrid, int[] waterTints,
                       SurfaceColorResolver.ColumnPalette[] palettes,
                       boolean[] valid, List<TreeInstance> trees, int seaLevel, int minY, int maxY) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.stepBlocks = stepBlocks;
        this.gridWidth = gridWidth;
        this.extW = extW;
        this.solidYs = solidYs;
        this.isWaterGrid = isWaterGrid;
        this.waterTints = waterTints;
        this.palettes = palettes;
        this.valid = valid;
        this.trees = trees;
        this.seaLevel = seaLevel;
        this.realSnapshot = false;

        int worldX = chunkX * SIZE_BLOCKS;
        int worldZ = chunkZ * SIZE_BLOCKS;
        this.bounds = new AABB(worldX, minY, worldZ, worldX + SIZE_BLOCKS, maxY, worldZ + SIZE_BLOCKS);
    }

    private LodSection(int chunkX, int chunkZ, AABB bounds, VertexBuffer vbo, boolean realSnapshot) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.stepBlocks = 1;
        this.gridWidth = 16;
        this.extW = 18;
        this.solidYs = new int[0];
        this.isWaterGrid = new boolean[0];
        this.waterTints = new int[0];
        this.palettes = new SurfaceColorResolver.ColumnPalette[0];
        this.valid = new boolean[0];
        this.trees = List.of();
        this.seaLevel = 63;
        this.bounds = bounds;
        this.opaqueVbo = vbo;
        this.waterVbo = null;
        this.realSnapshot = realSnapshot;
        this.uploaded = true;
    }

    public static LodSection fromPrebuiltVbo(int chunkX, int chunkZ, AABB bounds, VertexBuffer vbo, boolean realSnapshot) {
        return new LodSection(chunkX, chunkZ, bounds, vbo, realSnapshot);
    }

    public boolean isRealSnapshot() {
        return realSnapshot;
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

        int[] solidYs = new int[extW * extW];
        boolean[] valid = new boolean[extW * extW];
        for (int i = 0; i < samples.length; i++) {
            if (samples[i].valid()) {
                valid[i] = true;
                solidYs[i] = samples[i].surfaceY();
            }
        }

        int count = gridWidth * gridWidth;
        SurfaceColorResolver.ColumnPalette[] palettes = new SurfaceColorResolver.ColumnPalette[count];
        boolean[] isWaterGrid = new boolean[count];
        int[] waterTints = new int[count];
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

                int y = col.surfaceY();
                if (y < minY) minY = y;
                if (col.renderY() > maxY) maxY = col.renderY();

                int hW = valid[sz * extW + (sx - 1)] ? solidYs[sz * extW + (sx - 1)] : y;
                int hE = valid[sz * extW + (sx + 1)] ? solidYs[sz * extW + (sx + 1)] : y;
                int hN = valid[(sz - 1) * extW + sx] ? solidYs[(sz - 1) * extW + sx] : y;
                int hS = valid[(sz + 1) * extW + sx] ? solidYs[(sz + 1) * extW + sx] : y;

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

                if (col.isWater()) {
                    isWaterGrid[idx] = true;
                    // Opaque underwater seabed (sand in shallows, gravel in deep ocean)
                    LodAtlasSprites.Material bedMat = (col.waterDepth() > 12)
                            ? LodAtlasSprites.Material.GRAVEL : LodAtlasSprites.Material.SAND;
                    float bedShade = Mth.clamp(0.88f - col.waterDepth() * 0.025f, 0.35f, 0.88f) * sunDot;
                    int bedTint = SurfaceColorResolver.applyShade(0xFFFFFFFF, bedShade);
                    palettes[idx] = new SurfaceColorResolver.ColumnPalette(
                            bedMat, bedTint, bedMat, bedTint, LodAtlasSprites.Material.STONE, bedTint);

                    int wRgb = col.biome().value().getWaterColor();
                    int wAlpha = Mth.clamp(165 + col.waterDepth() * 3, 165, 225);
                    waterTints[idx] = (wAlpha << 24) | (wRgb & 0x00FFFFFF);
                } else {
                    palettes[idx] = SurfaceColorResolver.resolvePalette(
                            col.biome(), wx, y, wz, col.seaLevel(),
                            false, 0, ny, sunDot, col.isNether(), col.isEnd());
                }

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
                                int leafTint = SurfaceColorResolver.resolveTreeCanopyTint(col.biome(), style, wx, wz);
                                float cx = baseX + gx * stepBlocks + stepBlocks * 0.5f;
                                float cz = baseZ + gz * stepBlocks + stepBlocks * 0.5f;
                                trees.add(new TreeInstance(cx, y + 0.94f, cz, style, th,
                                        SurfaceColorResolver.getTrunkMaterial(style),
                                        SurfaceColorResolver.getLeafMaterial(style), leafTint));
                                if (y + 12 > maxY) {
                                    maxY = y + 12;
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
                solidYs, isWaterGrid, waterTints, palettes, valid, trees, seaLevel, minY - 32, maxY + 4);
    }

    /**
     * Builds both opaque terrain (with greedy flat-quad merging) and translucent water VBOs on the render thread.
     */
    public void uploadToGpu() {
        if (closed || uploaded) {
            return;
        }

        int baseX = chunkX * SIZE_BLOCKS;
        int baseZ = chunkZ * SIZE_BLOCKS;
        float deepFloor = seaLevel - 32.0f;

        // 1. Opaque terrain + seabed + organic 3D trees
        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(24576)) {
            BufferBuilder builder = new BufferBuilder(byteBuffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            boolean hasOpaque = false;

            // DH-style 2D greedy merge for flat identical top faces
            boolean[] mergedTop = new boolean[gridWidth * gridWidth];
            for (int gz = 0; gz < gridWidth; gz++) {
                for (int gx = 0; gx < gridWidth; gx++) {
                    int idx = gz * gridWidth + gx;
                    int eIdx = (gz + 1) * extW + (gx + 1);
                    if (!valid[eIdx] || palettes[idx] == null) continue;
                    hasOpaque = true;

                    if (!mergedTop[idx]) {
                        int yRef = solidYs[eIdx];
                        SurfaceColorResolver.ColumnPalette pRef = palettes[idx];
                        int runW = 1;
                        while (gx + runW < gridWidth) {
                            int nIdx = gz * gridWidth + (gx + runW);
                            int neIdx = (gz + 1) * extW + (gx + runW + 1);
                            if (mergedTop[nIdx] || !valid[neIdx] || solidYs[neIdx] != yRef
                                    || palettes[nIdx] == null
                                    || palettes[nIdx].topMat() != pRef.topMat()
                                    || palettes[nIdx].topTint() != pRef.topTint()) {
                                break;
                            }
                            runW++;
                        }
                        int runH = 1;
                        outer:
                        while (gz + runH < gridWidth) {
                            for (int rx = 0; rx < runW; rx++) {
                                int nIdx = (gz + runH) * gridWidth + (gx + rx);
                                int neIdx = (gz + runH + 1) * extW + (gx + rx + 1);
                                if (mergedTop[nIdx] || !valid[neIdx] || solidYs[neIdx] != yRef
                                        || palettes[nIdx] == null
                                        || palettes[nIdx].topMat() != pRef.topMat()
                                        || palettes[nIdx].topTint() != pRef.topTint()) {
                                    break outer;
                                }
                            }
                            runH++;
                        }
                        for (int rz = 0; rz < runH; rz++) {
                            for (int rx = 0; rx < runW; rx++) {
                                mergedTop[(gz + rz) * gridWidth + (gx + rx)] = true;
                            }
                        }

                        float x0 = baseX + gx * stepBlocks;
                        float z0 = baseZ + gz * stepBlocks;
                        float x1 = x0 + runW * stepBlocks;
                        float z1 = z0 + runH * stepBlocks;
                        LodAtlasSprites.SpriteUv topUv = LodAtlasSprites.getUv(pRef.topMat());
                        emitTopFace(builder, x0, yRef + 0.94f, z0, x1, z1, topUv, pRef.topTint());
                    }

                    // Neighbor-culled layered cliff sides
                    float x0 = baseX + gx * stepBlocks;
                    float z0 = baseZ + gz * stepBlocks;
                    float x1 = x0 + stepBlocks;
                    float z1 = z0 + stepBlocks;
                    float yTop = solidYs[eIdx] + 0.94f;
                    int sx = gx + 1, sz = gz + 1;

                    float yN = valid[(sz - 1) * extW + sx] ? (solidYs[(sz - 1) * extW + sx] + 0.94f) : deepFloor;
                    float yS = valid[(sz + 1) * extW + sx] ? (solidYs[(sz + 1) * extW + sx] + 0.94f) : deepFloor;
                    float yW = valid[sz * extW + (sx - 1)] ? (solidYs[sz * extW + (sx - 1)] + 0.94f) : deepFloor;
                    float yE = valid[sz * extW + (sx + 1)] ? (solidYs[sz * extW + (sx + 1)] + 0.94f) : deepFloor;

                    if (gz == 0) yN = Math.min(yN, yTop - 3.0f);
                    if (gz == gridWidth - 1) yS = Math.min(yS, yTop - 3.0f);
                    if (gx == 0) yW = Math.min(yW, yTop - 3.0f);
                    if (gx == gridWidth - 1) yE = Math.min(yE, yTop - 3.0f);

                    SurfaceColorResolver.ColumnPalette pal = palettes[idx];
                    LodAtlasSprites.SpriteUv fillUv = LodAtlasSprites.getUv(pal.fillerMat());
                    LodAtlasSprites.SpriteUv stoneUv = LodAtlasSprites.getUv(pal.stoneMat());

                    if (yTop > yN) emitLayeredSide(builder, x1, z0, x0, z0, yTop, yN, fillUv, pal.fillerTint(), stoneUv, pal.stoneTint(), 0.80f);
                    if (yTop > yS) emitLayeredSide(builder, x0, z1, x1, z1, yTop, yS, fillUv, pal.fillerTint(), stoneUv, pal.stoneTint(), 0.80f);
                    if (yTop > yW) emitLayeredSide(builder, x0, z0, x0, z1, yTop, yW, fillUv, pal.fillerTint(), stoneUv, pal.stoneTint(), 0.86f);
                    if (yTop > yE) emitLayeredSide(builder, x1, z1, x1, z0, yTop, yE, fillUv, pal.fillerTint(), stoneUv, pal.stoneTint(), 0.86f);
                }
            }

            // Organic multi-voxel 3D trees
            for (TreeInstance t : trees) {
                hasOpaque = true;
                LodTreeShapes.emitTree(builder, t.cx(), t.groundY(), t.cz(), t.style(), t.seedHash(),
                        stepBlocks, t.trunkMat(), t.leafMat(), t.leafTint());
            }

            if (hasOpaque) {
                MeshData mesh = builder.build();
                if (mesh != null) {
                    VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    vbo.bind();
                    vbo.upload(mesh);
                    VertexBuffer.unbind();
                    this.opaqueVbo = vbo;
                }
            }
        }

        // 2. Greedy-merged translucent water surface pass (drawn over underwater seabed!)
        try (ByteBufferBuilder waterBuf = new ByteBufferBuilder(4096)) {
            BufferBuilder wb = new BufferBuilder(waterBuf, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            boolean hasWater = false;
            boolean[] mergedW = new boolean[gridWidth * gridWidth];
            LodAtlasSprites.SpriteUv waterUv = LodAtlasSprites.getUv(LodAtlasSprites.Material.WATER);
            float waterY = seaLevel + 0.85f;

            for (int gz = 0; gz < gridWidth; gz++) {
                for (int gx = 0; gx < gridWidth; gx++) {
                    int idx = gz * gridWidth + gx;
                    if (!isWaterGrid[idx] || mergedW[idx]) continue;
                    hasWater = true;

                    int tintRef = waterTints[idx];
                    int runW = 1;
                    while (gx + runW < gridWidth) {
                        int nIdx = gz * gridWidth + (gx + runW);
                        if (mergedW[nIdx] || !isWaterGrid[nIdx] || waterTints[nIdx] != tintRef) break;
                        runW++;
                    }
                    int runH = 1;
                    outerW:
                    while (gz + runH < gridWidth) {
                        for (int rx = 0; rx < runW; rx++) {
                            int nIdx = (gz + runH) * gridWidth + (gx + rx);
                            if (mergedW[nIdx] || !isWaterGrid[nIdx] || waterTints[nIdx] != tintRef) break outerW;
                        }
                        runH++;
                    }
                    for (int rz = 0; rz < runH; rz++) {
                        for (int rx = 0; rx < runW; rx++) {
                            mergedW[(gz + rz) * gridWidth + (gx + rx)] = true;
                        }
                    }

                    float x0 = baseX + gx * stepBlocks;
                    float z0 = baseZ + gz * stepBlocks;
                    emitTopFace(wb, x0, waterY, z0, x0 + runW * stepBlocks, z0 + runH * stepBlocks, waterUv, tintRef);
                }
            }

            if (hasWater) {
                MeshData mesh = wb.build();
                if (mesh != null) {
                    VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    vbo.bind();
                    vbo.upload(mesh);
                    VertexBuffer.unbind();
                    this.waterVbo = vbo;
                }
            }
        }

        this.uploaded = true;
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

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public AABB getBounds() { return bounds; }
    @Nullable
    public VertexBuffer getOpaqueVbo() { return opaqueVbo; }
    @Nullable
    public VertexBuffer getWaterVbo() { return waterVbo; }
    public boolean isUploaded() { return uploaded; }

    @Override
    public void close() {
        closed = true;
        if (opaqueVbo != null) {
            opaqueVbo.close();
            opaqueVbo = null;
        }
        if (waterVbo != null) {
            waterVbo.close();
            waterVbo = null;
        }
    }
}
