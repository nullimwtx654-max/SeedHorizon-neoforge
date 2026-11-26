package com.seedhorizon.mixin;

import com.seedhorizon.config.SeedHorizonConfig;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends the camera projection far plane so the 32-chunk LOD horizon is never
 * clipped when the real-world render distance is capped at 6 chunks.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
    private void seedhorizon$extendDepthFar(CallbackInfoReturnable<Float> cir) {
        if (!SeedHorizonConfig.isActive()) {
            return;
        }
        float lodFar = SeedHorizonConfig.getLodRenderDistance() * 16.0F * 2.5F;
        if (cir.getReturnValueF() < lodFar) {
            cir.setReturnValue(lodFar);
        }
    }
}
