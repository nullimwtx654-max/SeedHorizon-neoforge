package com.seedhorizon.lod;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;

/**
 * SeedGen-inspired column height and biome sampler for Minecraft 1.21.1:
 * uses {@code NoiseRouter.initialDensityWithoutJaggedness()} (cave-free terrain field)
 * for a fast coarse height scan, then refines locally with {@code finalDensity()}
 * so distant terrain never drops into cave mouths.
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
        NoiseSettings noiseSettings = ctx.settings().noiseSettings();
        int minY = noiseSettings.minY();
        int maxY = minY + noiseSettings.height();
        int seaLevel = ctx.settings().seaLevel();

        boolean isNether = ctx.dimension().equals(Level.NETHER);
        boolean isEnd = ctx.dimension().equals(Level.END);

        NoiseRouter router = ctx.randomState().router();
        DensityFunction finalDensity = router.finalDensity();

        int surfaceY;
        if (!isNether && !isEnd) {
            // 1. Fast coarse scan on cave-free initialDensityWithoutJaggedness() (16-block stride)
            DensityFunction terrainDensity = router.initialDensityWithoutJaggedness();
            int coarseTop = Math.min(256, maxY - 4);
            int coarseBot = Math.max(minY + 4, seaLevel - 32);

            int prelimY = coarseBot;
            int prevY = coarseTop;
            double prevD = sampleDensity(terrainDensity, blockX, prevY, blockZ);
            for (int y = prevY - 16; y >= coarseBot; y -= 16) {
                double d = sampleDensity(terrainDensity, blockX, y, blockZ);
                if (prevD <= 0.0 && d > 0.0) {
                    // Linear interpolation for a tight initial guess
                    double frac = prevD / (prevD - d);
                    prelimY = (int) Math.round(prevY - frac * 16.0);
                    break;
                }
                prevY = y;
                prevD = d;
            }

            // 2. Local 4-block scan + 2-step binary search on finalDensity around prelimY
            int scanTop = Mth.clamp(prelimY + 20, minY + 8, maxY - 4);
            int scanBottom = Mth.clamp(prelimY - 12, minY + 4, scanTop - 4);

            int hitTop = Integer.MIN_VALUE;
            int hitBottom = Integer.MIN_VALUE;
            int pY = scanTop;
            double pD = sampleDensity(finalDensity, blockX, pY, blockZ);

            for (int y = pY - 4; y >= scanBottom; y -= 4) {
                double d = sampleDensity(finalDensity, blockX, y, blockZ);
                if (pD <= 0.0 && d > 0.0) {
                    hitTop = pY;
                    hitBottom = y;
                    break;
                }
                pY = y;
                pD = d;
            }

            if (hitTop != Integer.MIN_VALUE) {
                int lo = hitBottom;
                int hi = hitTop;
                for (int i = 0; i < 2; i++) {
                    int mid = (lo + hi) >> 1;
                    if (sampleDensity(finalDensity, blockX, mid, blockZ) > 0.0) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                surfaceY = lo;
            } else {
                surfaceY = Mth.clamp(prelimY, minY + 4, maxY - 4);
            }
        } else {
            int scanTop = isNether ? Math.min(116, maxY - 4) : Math.min(160, maxY - 4);
            int scanBottom = isNether ? Math.max(minY + 4, 24) : Math.max(minY + 4, 0);

            int prevY = scanTop;
            double prevDensity = sampleDensity(finalDensity, blockX, prevY, blockZ);

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
            for (int y = prevY - 8; y >= scanBottom; y -= 8) {
                double d = sampleDensity(finalDensity, blockX, y, blockZ);
                if (prevDensity <= 0.0 && d > 0.0) {
                    hitTop = prevY;
                    hitBottom = y;
                    break;
                }
                prevY = y;
                prevDensity = d;
            }

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

        return new SampledColumn(surfaceY, renderY, waterDepth, isWater, isNether, isEnd, seaLevel, biome, true);
    }

    private static double sampleDensity(DensityFunction fn, int x, int y, int z) {
        return fn.compute(new DensityFunction.SinglePointContext(x, y, z));
    }
}
