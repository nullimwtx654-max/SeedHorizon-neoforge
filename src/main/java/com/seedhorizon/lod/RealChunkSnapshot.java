package com.seedhorizon.lod;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Pure-Java RAM snapshot of a real visited {@link LevelChunk} (zero SQLite / native JNI libraries).
 * Preserves actual player builds, real trees, exact block textures, and exact heights when chunks
 * unload beyond the 6-chunk real render cap.
 */
public final class RealChunkSnapshot {
    private RealChunkSnapshot() {
    }

    @Nullable
    public static LodSection captureFromRealChunk(ClientLevel level, LevelChunk chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int minBuildY = level.getMinBuildHeight();

        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        BlockColors blockColors = mc.getBlockColors();

        int[] heights = new int[256];
        LodAtlasSprites.SpriteUv[] uvs = new LodAtlasSprites.SpriteUv[256];
        int[] tints = new int[256];
        boolean[] valid = new boolean[256];

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int idx = lz * 16 + lx;
                int topY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                if (topY <= minBuildY) {
                    continue;
                }
                // Walk down past non-occluding air/plants to find the visual top block
                BlockState state = Blocks.AIR.defaultBlockState();
                int y = topY;
                for (int tries = 0; tries < 6 && y >= minBuildY; tries++, y--) {
                    pos.set(baseX + lx, y, baseZ + lz);
                    BlockState candidate = chunk.getBlockState(pos);
                    if (!candidate.isAir()) {
                        state = candidate;
                        break;
                    }
                }
                if (state.isAir()) {
                    continue;
                }

                valid[idx] = true;
                heights[idx] = y;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;

                TextureAtlasSprite sprite = dispatcher.getBlockModel(state).getParticleIcon();
                float du = (sprite.getU1() - sprite.getU0()) * 0.03f;
                float dv = (sprite.getV1() - sprite.getV0()) * 0.03f;
                uvs[idx] = new LodAtlasSprites.SpriteUv(
                        sprite.getU0() + du, sprite.getV0() + dv,
                        sprite.getU1() - du, sprite.getV1() - dv);

                int rawTint = blockColors.getColor(state, level, pos, 0);
                if (rawTint == -1) {
                    tints[idx] = 0xFFFFFFFF;
                } else {
                    tints[idx] = 0xFF000000 | (rawTint & 0x00FFFFFF);
                }
            }
        }

        if (minY == Integer.MAX_VALUE) {
            return null;
        }

        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(24576)) {
            BufferBuilder builder = new BufferBuilder(byteBuffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            boolean hasGeom = false;
            float deepFloor = Math.max(minBuildY, minY - 12);

            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int idx = lz * 16 + lx;
                    if (!valid[idx]) continue;
                    hasGeom = true;

                    float x0 = baseX + lx;
                    float z0 = baseZ + lz;
                    float x1 = x0 + 1.0f;
                    float z1 = z0 + 1.0f;
                    float yTop = heights[idx] + 0.96f;
                    LodAtlasSprites.SpriteUv uv = uvs[idx];
                    int col = tints[idx];

                    // Top face
                    emitTop(builder, x0, yTop, z0, x1, z1, uv, col);

                    // Neighbor-culled vertical sides
                    float yN = (lz > 0 && valid[(lz - 1) * 16 + lx]) ? (heights[(lz - 1) * 16 + lx] + 0.96f) : Math.min(deepFloor, yTop - 3.0f);
                    float yS = (lz < 15 && valid[(lz + 1) * 16 + lx]) ? (heights[(lz + 1) * 16 + lx] + 0.96f) : Math.min(deepFloor, yTop - 3.0f);
                    float yW = (lx > 0 && valid[lz * 16 + (lx - 1)]) ? (heights[lz * 16 + (lx - 1)] + 0.96f) : Math.min(deepFloor, yTop - 3.0f);
                    float yE = (lx < 15 && valid[lz * 16 + (lx + 1)]) ? (heights[lz * 16 + (lx + 1)] + 0.96f) : Math.min(deepFloor, yTop - 3.0f);

                    int ns = SurfaceColorResolver.applyShade(col, 0.80f);
                    int ew = SurfaceColorResolver.applyShade(col, 0.86f);
                    if (yTop > yN) emitSide(builder, x1, z0, x0, z0, yTop, yN, uv, ns);
                    if (yTop > yS) emitSide(builder, x0, z1, x1, z1, yTop, yS, uv, ns);
                    if (yTop > yW) emitSide(builder, x0, z0, x0, z1, yTop, yW, uv, ew);
                    if (yTop > yE) emitSide(builder, x1, z1, x1, z0, yTop, yE, uv, ew);
                }
            }

            if (!hasGeom) return null;
            MeshData mesh = builder.build();
            if (mesh == null) return null;

            VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vbo.bind();
            vbo.upload(mesh);
            VertexBuffer.unbind();

            AABB bounds = new AABB(baseX, minY - 12, baseZ, baseX + 16, maxY + 2, baseZ + 16);
            return LodSection.fromPrebuiltVbo(chunkX, chunkZ, bounds, vbo, true);
        }
    }

    private static void emitTop(BufferBuilder b, float x0, float y, float z0, float x1, float z1,
                                LodAtlasSprites.SpriteUv uv, int argb) {
        int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, bl = argb & 0xFF;
        b.addVertex(x0, y, z0).setUv(uv.u0(), uv.v0()).setColor(r, g, bl, a);
        b.addVertex(x0, y, z1).setUv(uv.u0(), uv.v1()).setColor(r, g, bl, a);
        b.addVertex(x1, y, z1).setUv(uv.u1(), uv.v1()).setColor(r, g, bl, a);
        b.addVertex(x1, y, z0).setUv(uv.u1(), uv.v0()).setColor(r, g, bl, a);
    }

    private static void emitSide(BufferBuilder b, float xA, float zA, float xB, float zB,
                                 float yTop, float yBot, LodAtlasSprites.SpriteUv uv, int argb) {
        int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, bl = argb & 0xFF;
        b.addVertex(xA, yTop, zA).setUv(uv.u0(), uv.v0()).setColor(r, g, bl, a);
        b.addVertex(xA, yBot, zA).setUv(uv.u0(), uv.v1()).setColor(r, g, bl, a);
        b.addVertex(xB, yBot, zB).setUv(uv.u1(), uv.v1()).setColor(r, g, bl, a);
        b.addVertex(xB, yTop, zB).setUv(uv.u1(), uv.v0()).setColor(r, g, bl, a);
    }
}
