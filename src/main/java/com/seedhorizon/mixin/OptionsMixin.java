package com.seedhorizon.mixin;

import com.seedhorizon.config.SeedHorizonConfig;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps the effective real-world chunk render distance (default 6 chunks) for both
 * vanilla Minecraft and Sodium, while keeping the user's render distance option
 * intact for the SeedHorizon LOD horizon.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {

    @Inject(method = "getEffectiveRenderDistance", at = @At("RETURN"), cancellable = true)
    private void seedhorizon$capEffectiveRenderDistance(CallbackInfoReturnable<Integer> cir) {
        if (!SeedHorizonConfig.isActive()) {
            return;
        }
        int current = cir.getReturnValueI();
        int cap = SeedHorizonConfig.getRealRenderDistanceCap();
        if (current > cap) {
            cir.setReturnValue(cap);
        }
    }
}
