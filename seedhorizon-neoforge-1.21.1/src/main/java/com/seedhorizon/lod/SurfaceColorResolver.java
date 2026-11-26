package com.seedhorizon.lod;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * Lightweight, allocation-free biome & elevation surface color resolver for SeedHorizon LODs.
 * Mirrors SeedGen's surface/tree-cover look without requiring full block-state meshing.
 */
public final class SurfaceColorResolver {
    private SurfaceColorResolver() {
    }

    public static int resolveColumnColor(
            Holder<Biome> biomeHolder,
            int x,
            int y,
            int z,
            int seaLevel,
            boolean isWater,
            int waterDepth,
            float slopeShade,
            boolean isNether,
            boolean isEnd) {

        Biome biome = biomeHolder.value();

        if (isEnd) {
            return applyShade(0xFFDBDE9E, slopeShade);
        }

        if (isNether) {
            if (biomeHolder.is(Biomes.CRIMSON_FOREST)) {
                return applyShade(0xFF7B1C1C, slopeShade);
            }
            if (biomeHolder.is(Biomes.WARPED_FOREST)) {
                return applyShade(0xFF1D6A66, slopeShade);
            }
            if (biomeHolder.is(Biomes.SOUL_SAND_VALLEY)) {
                return applyShade(0xFF4F3E34, slopeShade);
            }
            if (biomeHolder.is(Biomes.BASALT_DELTAS)) {
                return applyShade(0xFF44454D, slopeShade);
            }
            return applyShade(0xFF6B2A2A, slopeShade);
        }

        if (isWater) {
            int waterRgb = biome.getWaterColor();
            float depthFactor = Mth.clamp(1.0f - (waterDepth / 48.0f) * 0.45f, 0.50f, 1.0f);
            return applyShade(0xFF000000 | (waterRgb & 0x00FFFFFF), depthFactor);
        }

        int baseColor;
        ResourceKey<Biome> key = biomeHolder.unwrapKey().orElse(null);

        if (y >= 118 && biome.getBaseTemperature() < 0.35f) {
            baseColor = 0xFFEAF2F5; // high mountain snowcap
        } else if (slopeShade < 0.78f && y > seaLevel + 18) {
            baseColor = 0xFF7A7D82; // steep stone cliff
        } else if (biomeHolder.is(BiomeTags.IS_BADLANDS) || (key != null && key.equals(Biomes.BADLANDS))) {
            int band = Math.floorMod(y, 6);
            baseColor = switch (band) {
                case 0, 1 -> 0xFFB85C38;
                case 2 -> 0xFFD48855;
                case 3 -> 0xFF9C462A;
                default -> 0xFFC96F44;
            };
        } else if (key != null && (key.equals(Biomes.DESERT) || key.equals(Biomes.BEACH))) {
            baseColor = 0xFFDFD29A;
        } else if (key != null && (key.equals(Biomes.SNOWY_PLAINS)
                || key.equals(Biomes.SNOWY_TAIGA)
                || key.equals(Biomes.ICE_SPIKES)
                || key.equals(Biomes.FROZEN_PEAKS)
                || key.equals(Biomes.JAGGED_PEAKS)
                || key.equals(Biomes.SNOWY_SLOPES))) {
            baseColor = 0xFFE9F1F4;
        } else if (key != null && key.equals(Biomes.MUSHROOM_FIELDS)) {
            baseColor = 0xFF6F636B;
        } else if (key != null && key.equals(Biomes.STONY_SHORE) || (key != null && key.equals(Biomes.STONY_PEAKS))) {
            baseColor = 0xFF7C8085;
        } else {
            int grass = biome.getGrassColor(x, z);
            baseColor = 0xFF000000 | (grass & 0x00FFFFFF);

            // Procedural canopy speckle for forested/jungle/taiga biomes
            if (biomeHolder.is(BiomeTags.IS_FOREST)
                    || biomeHolder.is(BiomeTags.IS_JUNGLE)
                    || biomeHolder.is(BiomeTags.IS_TAIGA)) {
                int hash = mixHash(x >> 2, z >> 2);
                if ((hash & 3) == 0) {
                    int foliage = biome.getFoliageColor();
                    baseColor = blend(baseColor, 0xFF000000 | (foliage & 0x00FFFFFF), 0.65f);
                }
            }
        }

        // Subtle elevation brightness gradient for horizon readability
        float elevBoost = Mth.clamp(0.92f + (y - seaLevel) * 0.0022f, 0.82f, 1.12f);
        return applyShade(baseColor, slopeShade * elevBoost);
    }

    private static int mixHash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return h ^ (h >> 16);
    }

    private static int blend(int c1, int c2, float t) {
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        int r = (int) Mth.lerp(t, r1, r2);
        int g = (int) Mth.lerp(t, g1, g2);
        int b = (int) Mth.lerp(t, b1, b2);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static int applyShade(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = Mth.clamp((int) (((argb >> 16) & 0xFF) * factor), 0, 255);
        int g = Mth.clamp((int) (((argb >> 8) & 0xFF) * factor), 0, 255);
        int b = Mth.clamp((int) ((argb & 0xFF) * factor), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
