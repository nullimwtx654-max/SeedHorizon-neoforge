package com.seedhorizon.lod;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * A 2x2-chunk (32x32 block) LOD section storing sampled terrain columns and a GPU VertexBuffer.
 */
public final class LodSection implements AutoCloseable {
    public static final int SIZE_CHUNKS = 2;
    public static final int SIZE_BLOCKS = SIZE_CHUNKS * 16; // 32

    private final int sectionX;
    private final int sectionZ;
    private final int stepBlocks;
    private final int gridWidth;
    private final int[] renderYs;
    private final int[] colors;
    private final boolean[] valid;
    private final AABB bounds;

    @Nullable
    private VertexBuffer vertexBuffer;
    private volatile boolean uploaded = false;
    private volatile boolean closed = false;

    private LodSection(int sectionX, int sectionZ, int stepBlocks, int gridWidth,
                       int[] renderYs, int[] colors, boolean[] valid, int minY, int maxY) {
        this.sectionX = sectionX;
        this.sectionZ = sectionZ;
        this.stepBlocks = stepBlocks;
        this.gridWidth = gridWidth;
        this.renderYs = renderYs;
        this.colors = colors;
        this.valid = valid;

        int worldX = sectionX * SIZE_BLOCKS;
        int worldZ = sectionZ * SIZE_BLOCKS;
        this.bounds = new AABB(worldX, minY, worldZ, worldX + SIZE_BLOCKS, maxY, worldZ + SIZE_BLOCKS);
    }

    public static LodSection generate(SeedStateManager.SeedContext ctx, int sectionX, int sectionZ, int stepBlocks) {
        int gridWidth = Math.max(1, SIZE_BLOCKS / stepBlocks);
        int count = gridWidth * gridWidth;
        int[] renderYs = new int[count];
        int[] colors = new int[count];
        boolean[] valid = new boolean[count];

        int baseX = sectionX * SIZE_BLOCKS;
        int baseZ = sectionZ * SIZE_BLOCKS;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int gz = 0; gz < gridWidth; gz++) {
            for (int gx = 0; gx < gridWidth; gx++) {
                int idx = gz * gridWidth + gx;
                int wx = baseX + gx * stepBlocks + (stepBlocks >> 1);
                int wz = baseZ + gz * stepBlocks + (stepBlocks >> 1);
                SeedColumnSampler.SampledColumn col = SeedColumnSampler.sample(ctx, wx, wz);
                if (col.valid()) {
                    valid[idx] = true;
                    renderYs[idx] = col.renderY();
                    colors[idx] = col.argbColor();
                    if (col.renderY() < minY) minY = col.renderY();
                    if (col.renderY() > maxY) maxY = col.renderY();
                }
            }
        }

        if (minY == Integer.MAX_VALUE) {
            minY = 0;
            maxY = 64;
        }
        return new LodSection(sectionX, sectionZ, stepBlocks, gridWidth, renderYs, colors, valid, minY - 16, maxY + 2);
    }

    /**
     * Must be called on the render thread.
     */
    public void uploadToGpu() {
        if (closed || uploaded) {
            return;
        }

        int baseX = sectionX * SIZE_BLOCKS;
        int baseZ = sectionZ * SIZE_BLOCKS;
        int skirtDepth = Math.max(8, stepBlocks * 2);

        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(4096)) {
            BufferBuilder builder = new BufferBuilder(byteBuffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            boolean hasGeometry = false;

            for (int gz = 0; gz < gridWidth; gz++) {
                for (int gx = 0; gx < gridWidth; gx++) {
                    int idx = gz * gridWidth + gx;
                    if (!valid[idx]) {
                        continue;
                    }
                    hasGeometry = true;

                    float x0 = baseX + gx * stepBlocks;
                    float z0 = baseZ + gz * stepBlocks;
                    float x1 = x0 + stepBlocks;
                    float z1 = z0 + stepBlocks;
                    float yTop = renderYs[idx] + 1.0f;
                    float yBot = yTop - skirtDepth;

                    int argb = colors[idx];
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;

                    int sideR = (int) (r * 0.80f);
                    int sideG = (int) (g * 0.80f);
                    int sideB = (int) (b * 0.80f);

                    // Top face (+Y)
                    builder.addVertex(x0, yTop, z0).setColor(r, g, b, a);
                    builder.addVertex(x0, yTop, z1).setColor(r, g, b, a);
                    builder.addVertex(x1, yTop, z1).setColor(r, g, b, a);
                    builder.addVertex(x1, yTop, z0).setColor(r, g, b, a);

                    // North skirt (-Z)
                    builder.addVertex(x1, yTop, z0).setColor(sideR, sideG, sideB, a);
                    builder.addVertex(x1, yBot, z0).setColor(sideR, sideG, sideB, a);
                    builder.addVertex(x0, yBot, z0).setColor(sideR, sideG, sideB, a);
                    builder.addVertex(x0, yTop, z0).setColor(sideR, sideG, sideB, a);

                    // South skirt (+Z)
                    builder.addVertex(x0, yTop, z1).setColor(sideR, sideG, sideB, a);
                    builder.addVertex(x0, yBot, z1).setColor(sideR, sideG, sideB, a);
                    builder.addVertex(x1, yBot, z1).setColor(sideR, sideG, sideB, a);
                    builder.addVertex(x1, yTop, z1).setColor(sideR, sideG, sideB, a);

                    // West skirt (-X)
                    builder.addVertex(x0, yTop, z0).setColor(sideR, sideG, sideB, a);
                    builder.addVertex(x0, yBot, z0).setColor(sideR, sideG, sideB, a);
                    builder.addVertex(x0, yBot, z1).setColor(sideR, sideG, sideB, a);
                    builder.addVertex(x0, yTop, z1).setColor(sideR, sideG, sideB, a);

                    // East skirt (+X)
                    builder.addVertex(x1, yTop, z1).setColor(sideR, sideG, sideB, a);
                    builder.addVertex(x1, yBot, z1).setColor(sideR, sideG, sideB, a);
                    builder.addVertex(x1, yBot, z0).setColor(sideR, sideG, sideB, a);
                    builder.addVertex(x1, yTop, z0).setColor(sideR, sideG, sideB, a);
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

    public int getSectionX() {
        return sectionX;
    }

    public int getSectionZ() {
        return sectionZ;
    }

    public AABB getBounds() {
        return bounds;
    }

    @Nullable
    public VertexBuffer getVertexBuffer() {
        return vertexBuffer;
    }

    public boolean isUploaded() {
        return uploaded;
    }

    @Override
    public void close() {
        closed = true;
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
