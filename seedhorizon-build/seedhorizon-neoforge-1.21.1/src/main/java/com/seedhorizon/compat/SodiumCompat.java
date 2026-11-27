package com.seedhorizon.compat;

import net.neoforged.fml.ModList;

/**
 * Runtime detection and compatibility helpers for Sodium / Embeddium on NeoForge 1.21.1.
 *
 * <p>SeedHorizon intentionally avoids fragile Mixins into Sodium's internal classes:
 * <ul>
 *   <li>Sodium respects {@code Options#getEffectiveRenderDistance()} for its chunk grid,
 *       so capping that method at 6 chunks keeps Sodium fast without touching its internals.</li>
 *   <li>Sodium reads {@code RenderSystem.getShaderFogStart()} / {@code getShaderFogEnd()}
 *       after {@code FogRenderer.setupFog} and {@code ViewportEvent.RenderFog}, so pushing fog
 *       out to 32 chunks works seamlessly in Sodium's chunk shaders.</li>
 *   <li>LOD geometry renders in {@code RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS} using
 *       the shared depth buffer and {@code GameRenderer.getPositionColorShader()}, which is
 *       compatible with Sodium's framebuffer and depth pass.</li>
 * </ul>
 */
public final class SodiumCompat {
    private static boolean sodiumLoaded = false;
    private static boolean embeddiumLoaded = false;

    private SodiumCompat() {
    }

    public static void init() {
        ModList modList = ModList.get();
        sodiumLoaded = modList.isLoaded("sodium");
        embeddiumLoaded = modList.isLoaded("embeddium");
    }

    public static boolean isSodiumLoaded() {
        return sodiumLoaded;
    }

    public static boolean isEmbeddiumLoaded() {
        return embeddiumLoaded;
    }

    public static boolean isAnySodiumDerivativeLoaded() {
        return sodiumLoaded || embeddiumLoaded;
    }
}
