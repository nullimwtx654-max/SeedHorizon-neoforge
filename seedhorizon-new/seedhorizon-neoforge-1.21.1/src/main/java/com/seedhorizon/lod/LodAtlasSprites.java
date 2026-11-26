package com.seedhorizon.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves live UV coordinates from Minecraft's {@link InventoryMenu#BLOCK_ATLAS}
 * (matching Distant Horizons 3.3's real block texturing) so LOD chunks render with
 * real Minecraft block textures and automatically support resource packs.
 */
public final class LodAtlasSprites {
    public enum Material {
        GRASS_TOP("block/grass_block_top"),
        DIRT("block/dirt"),
        STONE("block/stone"),
        SAND("block/sand"),
        RED_SAND("block/red_sand"),
        SNOW("block/snow"),
        GRAVEL("block/gravel"),
        PODZOL("block/podzol_top"),
        MYCELIUM("block/mycelium_top"),
        MUD("block/mud"),
        TERRACOTTA("block/terracotta"),
        ICE("block/packed_ice"),
        WATER("block/water_still"),
        NETHERRACK("block/netherrack"),
        END_STONE("block/end_stone"),
        OAK_LEAVES("block/oak_leaves"),
        SPRUCE_LEAVES("block/spruce_leaves"),
        BIRCH_LEAVES("block/birch_leaves"),
        JUNGLE_LEAVES("block/jungle_leaves"),
        CHERRY_LEAVES("block/cherry_leaves"),
        OAK_LOG("block/oak_log"),
        SPRUCE_LOG("block/spruce_log"),
        BIRCH_LOG("block/birch_log");

        final ResourceLocation spriteId;

        Material(String path) {
            this.spriteId = ResourceLocation.withDefaultNamespace(path);
        }
    }

    public record SpriteUv(float u0, float v0, float u1, float v1) {
    }

    private static final Map<Material, SpriteUv> CACHE = new EnumMap<>(Material.class);
    private static int cachedAtlasTexId = -1;

    private LodAtlasSprites() {
    }

    public static void invalidate() {
        CACHE.clear();
        cachedAtlasTexId = -1;
    }

    public static int getBlockAtlasTextureId() {
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
        int id = atlas.getId();
        if (id != cachedAtlasTexId) {
            CACHE.clear();
            cachedAtlasTexId = id;
        }
        return id;
    }

    public static SpriteUv getUv(Material material) {
        SpriteUv cached = CACHE.get(material);
        if (cached != null) {
            return cached;
        }
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
        TextureAtlasSprite sprite = atlas.getSprite(material.spriteId);
        // Inset by half a texel to prevent atlas bleeding at mipmap boundaries
        float du = (sprite.getU1() - sprite.getU0()) * 0.03f;
        float dv = (sprite.getV1() - sprite.getV0()) * 0.03f;
        SpriteUv uv = new SpriteUv(
                sprite.getU0() + du,
                sprite.getV0() + dv,
                sprite.getU1() - du,
                sprite.getV1() - dv
        );
        CACHE.put(material, uv);
        return uv;
    }
}
