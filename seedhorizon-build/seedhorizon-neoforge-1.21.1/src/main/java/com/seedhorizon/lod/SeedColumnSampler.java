package com.seedhorizon.lod;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseSettings;

/**
 * Fast seed-based column height and surface sampler using Minecraft's {@code NoiseRouter.finalDensity()}
 * and {@code BiomeSource.getNoiseBiome(...)}, inspired by SeedGen's coarse-scan + binary-search strategy.
 */
public final class SeedColumnSampler {
    private SeedColumnSampler() {
    }

    public record SampledColumn(int surfaceY, int renderY, int argbColor, boolean valid) {
        public static final SampledColumn INVALID = new SampledColumn(0, 0, 0, false);
    }

    public static SampledColumn sample(SeedStateManager.SeedContext ctx, int blockX, int blockZ) {
        NoiseSettings noiseSettings = ctx.settings().noiseSettings();
        int minY = noiseSettings.minY();
        int maxY = minY + noiseSettings.height();
        int seaLevel = ctx.settings().seaLevel();

        boolean isNether = ctx.dimension().equals(Level.NETHER);
        boolean isEnd = ctx.dimension().equals(Level.END);

        DensityFunction finalDensity = ctx.randomState().router().finalDensity();

        int scanTop = isNether ? Math.min(116, maxY - 4) : Math.min(256, maxY - 4);
        int scanBottom = isNether ? Math.max(minY + 4, 24) : Math.max(minY + 4, seaLevel - 32);

        int prevY = scanTop;
        double prevDensity = sampleDensity(finalDensity, blockX, prevY, blockZ);

        // If Nether scan starts inside solid ceiling, walk down until air first
        if (isNether && prevDensity > 0.0) {
            for (int y = scanTop - 8; y >= scanBottom; y -= 8) {
                double d = sampleDensity(finalDensity, blockX, y, blockZ);
                if (d <= 0.0) {
                    prevY = y;
                    prevDensity = d;
                    break;
                }
            }
        }

        int hitTop = Integer.MIN_VALUE;
        int hitBottom = Integer.MIN_VALUE;
        int step = 8;

        for (int y = prevY - step; y >= scanBottom; y -= step) {
            double d = sampleDensity(finalDensity, blockX, y, blockZ);
            if (prevDensity <= 0.0 && d > 0.0) {
                hitTop = prevY;
                hitBottom = y;
                break;
            }
            prevY = y;
            prevDensity = d;
        }

        int surfaceY;
        if (hitTop == Integer.MIN_VALUE) {
            if (isEnd) {
                return SampledColumn.INVALID;
            }
            surfaceY = scanBottom;
        } else {
            int lo = hitBottom;
            int hi = hitTop;
            for (int i = 0; i < 3; i++) {
                int mid = (lo + hi) >> 1;
                if (sampleDensity(finalDensity, blockX, mid, blockZ) > 0.0) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            surfaceY = lo;
        }

        boolean isWater = !isNether && !isEnd && surfaceY < seaLevel;
        int renderY = isWater ? seaLevel : surfaceY;
        int waterDepth = isWater ? (seaLevel - surfaceY) : 0;

        Holder<Biome> biome = ctx.biomeSource().getNoiseBiome(
                QuartPos.fromBlock(blockX),
                QuartPos.fromBlock(renderY),
                QuartPos.fromBlock(blockZ),
                ctx.randomState().sampler()
        );

        // Cheap slope approximation for directional horizon shading
        float slopeShade = 1.0f;
        if (!isWater) {
            double dEast = sampleDensity(finalDensity, blockX + 4, surfaceY + 2, blockZ);
            double dNorth = sampleDensity(finalDensity, blockX, surfaceY + 2, blockZ - 4);
            if (dEast > 0.05 || dNorth > 0.05) {
                slopeShade = 0.76f;
            } else if (dEast < -0.15 && dNorth < -0.15) {
                slopeShade = 1.06f;
            }
        }

        int color = SurfaceColorResolver.resolveColumnColor(
                biome, blockX, surfaceY, blockZ, seaLevel, isWater, waterDepth, slopeShade, isNether, isEnd);

        return new SampledColumn(surfaceY, renderY, color, true);
    }

    private static double sampleDensity(DensityFunction fn, int x, int y, int z) {
        return fn.compute(new DensityFunction.SinglePointContext(x, y, z));
    }
}
