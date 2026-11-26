package com.seedhorizon.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.seedhorizon.config.SeedHorizonConfig;
import com.seedhorizon.render.FogHandler;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Belt-and-suspenders fallback alongside {@link FogHandler} so both vanilla and Sodium
 * terrain shaders receive the pushed-back 32-chunk fog uniforms after FogRenderer.setupFog.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {

    @Inject(method = "setupFog", at = @At("RETURN"))
    private static void seedhorizon$pushTerrainFog(
            Camera camera,
            FogRenderer.FogMode fogMode,
            float farPlaneDistance,
            boolean shouldCreateFog,
            float partialTick,
            CallbackInfo ci) {
        if (!SeedHorizonConfig.isActive() || !SeedHorizonConfig.CLIENT.enableFogPushback.get()) {
            return;
        }
        if (fogMode != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        if (camera.getFluidInCamera() != FogType.NONE || FogHandler.hasVisionImpairment(camera)) {
            return;
        }

        float lodBlocks = SeedHorizonConfig.getLodRenderDistance() * 16.0F;
        float fogStart = lodBlocks * SeedHorizonConfig.CLIENT.fogStartFraction.get().floatValue();
        float fogEnd = lodBlocks * SeedHorizonConfig.CLIENT.fogEndFraction.get().floatValue();

        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(Math.max(fogStart + 16.0F, fogEnd));
    }
}
