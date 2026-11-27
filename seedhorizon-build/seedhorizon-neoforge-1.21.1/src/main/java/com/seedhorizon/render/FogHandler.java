package com.seedhorizon.render;

import com.mojang.blaze3d.shaders.FogShape;
import com.seedhorizon.config.SeedHorizonConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Adjusts NeoForge's RenderFog viewport event so terrain fog wraps the 32-chunk LOD
 * horizon rather than clipping at the 6-chunk real render distance.
 */
public final class FogHandler {
    private FogHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!SeedHorizonConfig.isActive() || !SeedHorizonConfig.CLIENT.enableFogPushback.get()) {
            return;
        }
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE || hasVisionImpairment(camera)) {
            return;
        }

        float lodBlocks = SeedHorizonConfig.getLodRenderDistance() * 16.0F;
        float fogStart = lodBlocks * SeedHorizonConfig.CLIENT.fogStartFraction.get().floatValue();
        float fogEnd = Math.max(fogStart + 16.0F, lodBlocks * SeedHorizonConfig.CLIENT.fogEndFraction.get().floatValue());

        event.setNearPlaneDistance(fogStart);
        event.setFarPlaneDistance(fogEnd);
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true);
    }

    public static boolean hasVisionImpairment(Camera camera) {
        Entity entity = camera.getEntity();
        if (entity instanceof LivingEntity living) {
            return living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS);
        }
        return false;
    }
}
