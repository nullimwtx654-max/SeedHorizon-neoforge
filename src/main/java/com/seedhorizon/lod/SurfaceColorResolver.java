package com.seedhorizon.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * Resolves both real Minecraft block atlas materials ({@link LodAtlasSprites.Material})
 * and biome tint colors (white = untinted native texture, green/blue = biome-tinted grass/leaves/water).
 */
public final class SurfaceColorResolver {
    private SurfaceColorResolver() {
    }

    public record ColumnPalette(
            LodAtlasSprites.Material topMat,
            int topTint,
            LodAtlasSprites.Material fillerMat,
            int fillerTint,
            LodAtlasSprites.Material stoneMat,
            int stoneTint
    ) {
    }

    public enum TreeStyle {
        NONE,
        OAK,
        SPRUCE,
        BIRCH,
        JUNGLE,
        ACACIA,
        DARK_OAK,
        CHERRY
    }

    public static TreeStyle getTreeStyle(Holder<Biome> biomeHolder) {
        ResourceKey<Biome> key = biomeHolder.unwrapKey().orElse(null);
        if (key != null) {
            if (key.equals(Biomes.TAIGA) || key.equals(Biomes.SNOWY_TAIGA)
                    || key.equals(Biomes.OLD_GROWTH_PINE_TAIGA) || key.equals(Biomes.OLD_GROWTH_SPRUCE_TAIGA)
                    || key.equals(Biomes.GROVE) || key.equals(Biomes.WINDSWEPT_FOREST)) {
                return TreeStyle.SPRUCE;
            }
            if (key.equals(Biomes.BIRCH_FOREST) || key.equals(Biomes.OLD_GROWTH_BIRCH_FOREST)) {
                return TreeStyle.BIRCH;
            }
            if (key.equals(Biomes.DARK_FOREST)) {
                return TreeStyle.DARK_OAK;
            }
            if (key.equals(Biomes.CHERRY_GROVE)) {
                return TreeStyle.CHERRY;
            }
            if (key.equals(Biomes.SAVANNA) || key.equals(Biomes.SAVANNA_PLATEAU) || key.equals(Biomes.WINDSWEPT_SAVANNA)) {
                return TreeStyle.ACACIA;
            }
        }
        if (biomeHolder.is(BiomeTags.IS_JUNGLE)) return TreeStyle.JUNGLE;
        if (biomeHolder.is(BiomeTags.IS_FOREST)) return TreeStyle.OAK;
        if (biomeHolder.is(BiomeTags.IS_TAIGA)) return TreeStyle.SPRUCE;
        return TreeStyle.NONE;
    }

    public static LodAtlasSprites.Material getLeafMaterial(TreeStyle style) {
        return switch (style) {
            case SPRUCE -> LodAtlasSprites.Material.SPRUCE_LEAVES;
            case BIRCH -> LodAtlasSprites.Material.BIRCH_LEAVES;
            case JUNGLE -> LodAtlasSprites.Material.JUNGLE_LEAVES;
            case CHERRY -> LodAtlasSprites.Material.CHERRY_LEAVES;
            default -> LodAtlasSprites.Material.OAK_LEAVES;
        };
    }

    public static LodAtlasSprites.Material getTrunkMaterial(TreeStyle style) {
        return switch (style) {
            case SPRUCE, DARK_OAK -> LodAtlasSprites.Material.SPRUCE_LOG;
            case BIRCH -> LodAtlasSprites.Material.BIRCH_LOG;
            default -> LodAtlasSprites.Material.OAK_LOG;
        };
    }

    public static int resolveTreeCanopyTint(Holder<Biome> biomeHolder, TreeStyle style, int x, int z) {
        int base = switch (style) {
            case SPRUCE -> 0xFF619961;
            case BIRCH -> 0xFF80A755;
            case DARK_OAK -> 0xFF487534;
            case CHERRY -> 0xFFFFFFFF; // cherry_leaves texture is already pink
            case ACACIA -> 0xFF9EA842;
            case JUNGLE -> 0xFF48B518;
            case OAK -> 0xFF000000 | (biomeHolder.value().getFoliageColor() & 0x00FFFFFF);
            default -> 0xFF6A9F48;
        };
        int h = mixHash(x, z) & 15;
        float shade = 0.90f + (h / 15.0f) * 0.14f;
        return applyShade(base, shade);
    }

    public static ColumnPalette resolvePalette(
            Holder<Biome> biomeHolder,
            int x,
            int y,
            int z,
            int seaLevel,
            boolean isWater,
            int waterDepth,
            float normalY,
            float sunDot,
            boolean isNether,
            boolean isEnd) {

        Biome biome = biomeHolder.value();
        int white = applyShade(0xFFFFFFFF, sunDot);

        if (isEnd) {
            return new ColumnPalette(LodAtlasSprites.Material.END_STONE, white,
                    LodAtlasSprites.Material.END_STONE, white,
                    LodAtlasSprites.Material.END_STONE, white);
        }

        if (isNether) {
            return new ColumnPalette(LodAtlasSprites.Material.NETHERRACK, white,
                    LodAtlasSprites.Material.NETHERRACK, white,
                    LodAtlasSprites.Material.NETHERRACK, white);
        }

        if (isWater) {
            ResourceKey<Biome> key = biomeHolder.unwrapKey().orElse(null);
            if (key != null && (key.equals(Biomes.FROZEN_OCEAN) || key.equals(Biomes.DEEP_FROZEN_OCEAN)
                    || key.equals(Biomes.FROZEN_RIVER))) {
                if ((mixHash(x >> 2, z >> 2) & 3) == 0) {
                    return new ColumnPalette(LodAtlasSprites.Material.ICE, white,
                            LodAtlasSprites.Material.ICE, white,
                            LodAtlasSprites.Material.STONE, white);
                }
            }
            int waterRgb = biome.getWaterColor();
            // Blend shallow coastal water toward warm sandy turquoise, deep water toward rich blue
            float depthT = Mth.clamp(waterDepth / 26.0f, 0.0f, 1.0f);
            float depthShade = Mth.lerp(depthT, 1.08f, 0.62f);
            int wCol = applyShade(0xFF000000 | (waterRgb & 0x00FFFFFF), depthShade);
            return new ColumnPalette(LodAtlasSprites.Material.WATER, wCol,
                    LodAtlasSprites.Material.WATER, applyShade(wCol, 0.85f),
                    LodAtlasSprites.Material.SAND, white);
        }

        ResourceKey<Biome> key = biomeHolder.unwrapKey().orElse(null);
        boolean coldSnow = biome.coldEnoughToSnow(new BlockPos(x, y, z));

        if (normalY < 0.56f && y > seaLevel + 6) {
            return new ColumnPalette(LodAtlasSprites.Material.STONE, white,
                    LodAtlasSprites.Material.STONE, white,
                    LodAtlasSprites.Material.STONE, white);
        }

        if (coldSnow || (key != null && (
                key.equals(Biomes.SNOWY_PLAINS) || key.equals(Biomes.SNOWY_TAIGA)
                || key.equals(Biomes.ICE_SPIKES) || key.equals(Biomes.FROZEN_PEAKS)
                || key.equals(Biomes.JAGGED_PEAKS) || key.equals(Biomes.SNOWY_SLOPES)
                || key.equals(Biomes.GROVE)))) {
            LodAtlasSprites.Material top = (key != null && key.equals(Biomes.ICE_SPIKES))
                    ? LodAtlasSprites.Material.ICE : LodAtlasSprites.Material.SNOW;
            LodAtlasSprites.Material fill = (y > 110)
                    ? LodAtlasSprites.Material.STONE : LodAtlasSprites.Material.DIRT;
            return new ColumnPalette(top, white, fill, white, LodAtlasSprites.Material.STONE, white);
        }

        if (biomeHolder.is(BiomeTags.IS_BADLANDS) || (key != null && (
                key.equals(Biomes.BADLANDS) || key.equals(Biomes.ERODED_BADLANDS) || key.equals(Biomes.WOODED_BADLANDS)))) {
            if (key != null && key.equals(Biomes.WOODED_BADLANDS) && y > 96) {
                int grass = applyShade(0xFF000000 | (biome.getGrassColor(x, z) & 0x00FFFFFF), sunDot);
                return new ColumnPalette(LodAtlasSprites.Material.GRASS_TOP, grass,
                        LodAtlasSprites.Material.DIRT, white,
                        LodAtlasSprites.Material.TERRACOTTA, white);
            }
            if (y < seaLevel + 10) {
                return new ColumnPalette(LodAtlasSprites.Material.RED_SAND, white,
                        LodAtlasSprites.Material.RED_SAND, white,
                        LodAtlasSprites.Material.TERRACOTTA, white);
            }
            int band = Math.floorMod(y, 6);
            int bandTint = switch (band) {
                case 0, 1 -> 0xFFD99A7E;
                case 2 -> 0xFFF2C6A0;
                case 3 -> 0xFFBD7860;
                default -> 0xFFE5A985;
            };
            int shadedBand = applyShade(bandTint, sunDot);
            return new ColumnPalette(LodAtlasSprites.Material.TERRACOTTA, shadedBand,
                    LodAtlasSprites.Material.TERRACOTTA, shadedBand,
                    LodAtlasSprites.Material.TERRACOTTA, shadedBand);
        }

        if (key != null && (key.equals(Biomes.OLD_GROWTH_PINE_TAIGA) || key.equals(Biomes.OLD_GROWTH_SPRUCE_TAIGA))) {
            return new ColumnPalette(LodAtlasSprites.Material.PODZOL, white,
                    LodAtlasSprites.Material.DIRT, white,
                    LodAtlasSprites.Material.STONE, white);
        }

        if (key != null && key.equals(Biomes.MANGROVE_SWAMP)) {
            return new ColumnPalette(LodAtlasSprites.Material.MUD, white,
                    LodAtlasSprites.Material.MUD, white,
                    LodAtlasSprites.Material.STONE, white);
        }

        if (key != null && (key.equals(Biomes.DESERT) || key.equals(Biomes.BEACH))
                || (y <= seaLevel + 2 && biome.getBaseTemperature() >= 0.25f)) {
            return new ColumnPalette(LodAtlasSprites.Material.SAND, white,
                    LodAtlasSprites.Material.SAND, white,
                    LodAtlasSprites.Material.STONE, white);
        }

        if (key != null && key.equals(Biomes.SNOWY_BEACH)) {
            return new ColumnPalette(LodAtlasSprites.Material.SNOW, white,
                    LodAtlasSprites.Material.SAND, white,
                    LodAtlasSprites.Material.STONE, white);
        }

        if (key != null && (key.equals(Biomes.STONY_SHORE) || key.equals(Biomes.STONY_PEAKS)
                || key.equals(Biomes.WINDSWEPT_GRAVELLY_HILLS))) {
            return new ColumnPalette(LodAtlasSprites.Material.GRAVEL, white,
                    LodAtlasSprites.Material.STONE, white,
                    LodAtlasSprites.Material.STONE, white);
        }

        if (key != null && key.equals(Biomes.MUSHROOM_FIELDS)) {
            return new ColumnPalette(LodAtlasSprites.Material.MYCELIUM, white,
                    LodAtlasSprites.Material.DIRT, white,
                    LodAtlasSprites.Material.STONE, white);
        }

        int grass = applyShade(0xFF000000 | (biome.getGrassColor(x, z) & 0x00FFFFFF), sunDot);
        return new ColumnPalette(LodAtlasSprites.Material.GRASS_TOP, grass,
                LodAtlasSprites.Material.DIRT, white,
                LodAtlasSprites.Material.STONE, white);
    }

    public static int mixHash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return h ^ (h >> 16);
    }

    public static int applyShade(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = Mth.clamp((int) (((argb >> 16) & 0xFF) * factor), 0, 255);
        int g = Mth.clamp((int) (((argb >> 8) & 0xFF) * factor), 0, 255);
        int b = Mth.clamp((int) ((argb & 0xFF) * factor), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
