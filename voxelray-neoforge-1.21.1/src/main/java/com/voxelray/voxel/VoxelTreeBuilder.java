package com.voxelray.voxel;

import com.voxelray.config.VoxelRayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * Builds a flat, pointer-free 2-level 64-tree (4x4x4 coarse bitmask + 4x4x4 leaf bitmasks)
 * from loaded Minecraft chunk sections and uploads it to a GL_RG32UI usampler2D texture.
 *
 * <p>Texture layout (2048-wide rows of uvec2 = 64-bit masks):
 * <ul>
 *   <li>Indices {@code [0 .. totalSections - 1]}: section directory. Each entry stores
 *       {@code (leafBaseOffset, 0)} or {@code (0xFFFFFFFF, 0)} if the section is empty.</li>
 *   <li>At {@code leafBaseOffset}: entry 0 is the 64-bit {@code 4x4x4} coarse occupancy mask;
 *       entries {@code 1 .. popcount(coarse)} are the occupied {@code 4x4x4} leaf voxel masks.</li>
 * </ul>
 */
public final class VoxelTreeBuilder {
    public static final int TEX_WIDTH = 2048;
    public static final int MIN_SECTION_Y = -4; // Y = -64
    public static final int SECTION_HEIGHT = 20; // Y = -64 .. 255
    private static final int EMPTY_SECTION = 0xFFFFFFFF;

    private int chunkRadius = -1;
    private int gridDiameter = 0;
    private int originChunkX = Integer.MIN_VALUE;
    private int originChunkZ = Integer.MIN_VALUE;

    private long[][] sectionCoarse = new long[0][];
    private long[][] sectionLeaves = new long[0][];
    private boolean[] sectionBuilt = new boolean[0];

    private int glTextureId = 0;
    private int allocatedTexHeight = 0;
    private boolean gpuDirty = true;

    public void update(ClientLevel level, int playerChunkX, int playerChunkZ) {
        int radius = VoxelRayConfig.CLIENT.voxelChunkRadius.get();
        int diameter = radius * 2 + 1;
        int totalSections = diameter * SECTION_HEIGHT * diameter;

        if (radius != chunkRadius || Math.abs(playerChunkX - originChunkX) >= 2 || Math.abs(playerChunkZ - originChunkZ) >= 2) {
            chunkRadius = radius;
            gridDiameter = diameter;
            originChunkX = playerChunkX;
            originChunkZ = playerChunkZ;
            sectionCoarse = new long[totalSections][];
            sectionLeaves = new long[totalSections][];
            sectionBuilt = new boolean[totalSections];
            gpuDirty = true;
        }

        // Build up to 24 unbuilt sections per tick to avoid frame hitches
        int builtThisTick = 0;
        int minCx = originChunkX - chunkRadius;
        int minCz = originChunkZ - chunkRadius;

        for (int czOff = 0; czOff < gridDiameter && builtThisTick < 24; czOff++) {
            for (int cxOff = 0; cxOff < gridDiameter && builtThisTick < 24; cxOff++) {
                int cx = minCx + cxOff;
                int cz = minCz + czOff;
                LevelChunk chunk = (LevelChunk) level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = 0; sy < SECTION_HEIGHT; sy++) {
                    int idx = sectionIndex(cxOff, sy, czOff);
                    if (sectionBuilt[idx]) {
                        continue;
                    }
                    int chunkSecIdx = (MIN_SECTION_Y + sy) - level.getMinSection();
                    if (chunkSecIdx >= 0 && chunkSecIdx < sections.length) {
                        buildSection(idx, sections[chunkSecIdx]);
                    } else {
                        sectionCoarse[idx] = null;
                        sectionLeaves[idx] = null;
                    }
                    sectionBuilt[idx] = true;
                    gpuDirty = true;
                    builtThisTick++;
                }
            }
        }
    }

    public void invalidateAll() {
        Arrays.fill(sectionBuilt, false);
        gpuDirty = true;
    }

    public void invalidateBlock(BlockPos pos) {
        if (gridDiameter == 0) {
            return;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int sy = (pos.getY() >> 4) - MIN_SECTION_Y;
        int cxOff = cx - (originChunkX - chunkRadius);
        int czOff = cz - (originChunkZ - chunkRadius);
        if (cxOff >= 0 && cxOff < gridDiameter && czOff >= 0 && czOff < gridDiameter
                && sy >= 0 && sy < SECTION_HEIGHT) {
            sectionBuilt[sectionIndex(cxOff, sy, czOff)] = false;
        }
    }

    private void buildSection(int idx, LevelChunkSection section) {
        if (section == null || section.hasOnlyAir()) {
            sectionCoarse[idx] = null;
            sectionLeaves[idx] = null;
            return;
        }

        long coarse = 0L;
        long[] tempLeaves = new long[64];

        for (int y = 0; y < 16; y++) {
            int cy = y >> 2;
            int ly = y & 3;
            for (int z = 0; z < 16; z++) {
                int cz = z >> 2;
                int lz = z & 3;
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir() || !state.canOcclude()) {
                        continue;
                    }
                    int cx = x >> 2;
                    int lx = x & 3;
                    int cBit = (cy << 4) | (cz << 2) | cx;
                    int lBit = (ly << 4) | (lz << 2) | lx;
                    coarse |= (1L << cBit);
                    tempLeaves[cBit] |= (1L << lBit);
                }
            }
        }

        if (coarse == 0L) {
            sectionCoarse[idx] = null;
            sectionLeaves[idx] = null;
            return;
        }

        int leafCount = Long.bitCount(coarse);
        long[] packedLeaves = new long[leafCount];
        int write = 0;
        for (int i = 0; i < 64; i++) {
            if ((coarse & (1L << i)) != 0L) {
                packedLeaves[write++] = tempLeaves[i];
            }
        }
        sectionCoarse[idx] = new long[]{coarse};
        sectionLeaves[idx] = packedLeaves;
    }

    private int sectionIndex(int cxOff, int sy, int czOff) {
        return (sy * gridDiameter + czOff) * gridDiameter + cxOff;
    }

    public int uploadIfDirty() {
        if (glTextureId == 0) {
            glTextureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }

        if (!gpuDirty || sectionCoarse.length == 0) {
            return glTextureId;
        }

        int totalSections = sectionCoarse.length;
        int totalEntries = totalSections;
        for (int i = 0; i < totalSections; i++) {
            if (sectionCoarse[i] != null && sectionLeaves[i] != null) {
                totalEntries += 1 + sectionLeaves[i].length;
            }
        }

        int texHeight = Math.max(1, (totalEntries + TEX_WIDTH - 1) / TEX_WIDTH);
        int bufferCapacity = texHeight * TEX_WIDTH * 2;
        IntBuffer buf = MemoryUtil.memAllocInt(bufferCapacity);
        try {
            // Fill directory
            int cursor = totalSections;
            for (int i = 0; i < totalSections; i++) {
                if (sectionCoarse[i] == null || sectionLeaves[i] == null) {
                    buf.put(EMPTY_SECTION).put(0);
                } else {
                    buf.put(cursor).put(0);
                    cursor += 1 + sectionLeaves[i].length;
                }
            }
            // Fill 64-tree nodes
            for (int i = 0; i < totalSections; i++) {
                long[] coarse = sectionCoarse[i];
                long[] leaves = sectionLeaves[i];
                if (coarse != null && leaves != null) {
                    putU64(buf, coarse[0]);
                    for (long leaf : leaves) {
                        putU64(buf, leaf);
                    }
                }
            }
            while (buf.hasRemaining()) {
                buf.put(0);
            }
            buf.flip();

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
            if (texHeight != allocatedTexHeight) {
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32UI,
                        TEX_WIDTH, texHeight, 0, GL30.GL_RG_INTEGER, GL11.GL_UNSIGNED_INT, buf);
                allocatedTexHeight = texHeight;
            } else {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0,
                        TEX_WIDTH, texHeight, GL30.GL_RG_INTEGER, GL11.GL_UNSIGNED_INT, buf);
            }
            gpuDirty = false;
        } finally {
            MemoryUtil.memFree(buf);
        }
        return glTextureId;
    }

    private static void putU64(IntBuffer buf, long val) {
        buf.put((int) (val & 0xFFFFFFFFL));
        buf.put((int) ((val >>> 32) & 0xFFFFFFFFL));
    }

    public int getGridMinBlockX() {
        return (originChunkX - chunkRadius) << 4;
    }

    public int getGridMinBlockY() {
        return MIN_SECTION_Y << 4;
    }

    public int getGridMinBlockZ() {
        return (originChunkZ - chunkRadius) << 4;
    }

    public int getGridDiameter() {
        return gridDiameter;
    }

    public int getBuiltSectionCount() {
        int c = 0;
        for (boolean b : sectionBuilt) {
            if (b) c++;
        }
        return c;
    }

    public void destroy() {
        if (glTextureId != 0) {
            GL11.glDeleteTextures(glTextureId);
            glTextureId = 0;
            allocatedTexHeight = 0;
        }
        sectionCoarse = new long[0][];
        sectionLeaves = new long[0][];
        sectionBuilt = new boolean[0];
        originChunkX = Integer.MIN_VALUE;
        originChunkZ = Integer.MIN_VALUE;
    }
}
