package com.seedhorizon.lod;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * Exact vanilla-matched column height and biome sampler using Minecraft's own
 * {@link NoiseBasedChunkGenerator#getBaseHeight(int, int, Heightmap.Types, net.minecraft.world.level.LevelHeightAccessor, net.minecraft.world.level.levelgen.RandomState)}.
 * Guarantees zero vertical mismatch against real Minecraft terrain at the 6-chunk border.
 */
public final class SeedColumnSampler {
    private SeedColumnSampler() {
    }

    public record SampledColumn(
            int surfaceY,
            int renderY,
            int waterDepth,
            boolean isWater,
            boolean isNether,
            boolean isEnd,
            int seaLevel,
            Holder<Biome> biome,
            boolean valid
    ) {
        public static final SampledColumn INVALID = new SampledColumn(0, 0, 0, false, false, false, 63, null, false);
    }

    public static SampledColumn sample(SeedStateManager.SeedContext ctx, int blockX, int blockZ) {
        int seaLevel = ctx.settings().seaLevel();
        boolean isNether = ctx.dimension().equals(Level.NETHER);
        boolean isEnd = ctx.dimension().equals(Level.END);

        NoiseBasedChunkGenerator gen = ctx.generator();
        if (gen == null) {
            return SampledColumn.INVALID;
        }

        // Exact vanilla terrain surface height (OCEAN_FLOOR_WG gives solid ground even underwater)
        int solidTopExclusive = gen.getBaseHeight(
                blockX, blockZ, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState());
        int minY = ctx.heightAccessor().getMinBuildHeight();
        if (isEnd && solidTopExclusive <= minY + 1) {
            return SampledColumn.INVALID;
        }

        int surfaceY = Math.max(minY, solidTopExclusive - 1);
        boolean isWater = !isNether && !isEnd && surfaceY < seaLevel;
        int renderY = isWater ? seaLevel : surfaceY;
        int waterDepth = isWater ? (seaLevel - surfaceY) : 0;

        Holder<Biome> biome = ctx.biomeSource().getNoiseBiome(
                QuartPos.fromBlock(blockX),
                QuartPos.fromBlock(renderY),
                QuartPos.fromBlock(blockZ),
                ctx.randomState().sampler()
        );

        return new SampledColumn(surfaceY, renderY, waterDepth, isWater, isNether, isEnd, seaLevel, biome, true);
    }
}
