package com.seedhorizon;

import com.mojang.logging.LogUtils;
import com.seedhorizon.compat.SodiumCompat;
import com.seedhorizon.config.SeedHorizonConfig;
import com.seedhorizon.lod.LodRenderHandler;
import com.seedhorizon.lod.SeedStateManager;
import com.seedhorizon.render.FogHandler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(SeedHorizonMod.MOD_ID)
public final class SeedHorizonMod {
    public static final String MOD_ID = "seedhorizon";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SeedHorizonMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, SeedHorizonConfig.CLIENT_SPEC);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(this::onClientSetup);
            NeoForge.EVENT_BUS.register(FogHandler.class);
            NeoForge.EVENT_BUS.register(LodRenderHandler.class);
            NeoForge.EVENT_BUS.register(SeedStateManager.class);
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        SodiumCompat.init();
        LOGGER.info("SeedHorizon initialized (Sodium detected: {}, Embeddium detected: {})",
                SodiumCompat.isSodiumLoaded(), SodiumCompat.isEmbeddiumLoaded());
    }
}
