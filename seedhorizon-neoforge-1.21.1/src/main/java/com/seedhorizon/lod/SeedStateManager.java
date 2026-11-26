package com.seedhorizon.lod;

import com.seedhorizon.SeedHorizonMod;
import com.seedhorizon.config.SeedHorizonConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalLong;

/**
 * Resolves and caches the active dimension's {@link RandomState}, {@link BiomeSource},
 * and {@link NoiseGeneratorSettings} for cheap seed-based LOD sampling.
 */
public final class SeedStateManager {
    private static volatile SeedContext activeContext;
    private static volatile OptionalLong manualMultiplayerSeed = OptionalLong.empty();
    private static volatile int lastCappedServerDistance = -1;

    private SeedStateManager() {
    }

    public record SeedContext(
            ResourceKey<Level> dimension,
            long seed,
            RandomState randomState,
            BiomeSource biomeSource,
            NoiseGeneratorSettings settings
    ) {
    }

    public static void setManualSeed(long seed) {
        manualMultiplayerSeed = OptionalLong.of(seed);
        activeContext = null;
    }

    public static void clearManualSeed() {
        manualMultiplayerSeed = OptionalLong.empty();
        activeContext = null;
    }

    public static void invalidate() {
        activeContext = null;
        lastCappedServerDistance = -1;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        invalidate();
        manualMultiplayerSeed = OptionalLong.empty();
    }

    @Nullable
    public static SeedContext getOrCreate(ClientLevel clientLevel) {
        ResourceKey<Level> dim = clientLevel.dimension();
        SeedContext current = activeContext;
        if (current != null && current.dimension().equals(dim)) {
            return current;
        }

        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            ServerLevel serverLevel = server.getLevel(dim);
            if (serverLevel != null) {
                if (SeedHorizonConfig.isActive()
                        && SeedHorizonConfig.CLIENT.syncIntegratedServerViewDistance.get()) {
                    int cap = SeedHorizonConfig.getRealRenderDistanceCap();
                    if (lastCappedServerDistance != cap) {
                        server.execute(() -> server.getPlayerList().setViewDistance(cap));
                        lastCappedServerDistance = cap;
                    }
                }

                ServerChunkCache chunkSource = serverLevel.getChunkSource();
                ChunkGenerator generator = chunkSource.getGenerator();
                if (generator instanceof NoiseBasedChunkGenerator noiseGen) {
                    SeedContext ctx = new SeedContext(
                            dim,
                            serverLevel.getSeed(),
                            chunkSource.randomState(),
                            generator.getBiomeSource(),
                            noiseGen.generatorSettings().value()
                    );
                    activeContext = ctx;
                    return ctx;
                }
            }
        }

        if (manualMultiplayerSeed.isPresent()) {
            try {
                SeedContext mpCtx = createClientContext(clientLevel, dim, manualMultiplayerSeed.getAsLong());
                activeContext = mpCtx;
                return mpCtx;
            } catch (Exception e) {
                SeedHorizonMod.LOGGER.warn("Failed to initialize client-side SeedContext for {}", dim.location(), e);
            }
        }

        return null;
    }

    private static SeedContext createClientContext(ClientLevel clientLevel, ResourceKey<Level> dim, long seed) {
        HolderLookup.Provider registries = clientLevel.registryAccess();
        ResourceKey<NoiseGeneratorSettings> settingsKey;
        BiomeSource biomeSource;

        if (dim.equals(Level.NETHER)) {
            settingsKey = NoiseGeneratorSettings.NETHER;
            var presetLookup = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
            biomeSource = MultiNoiseBiomeSource.createFromPreset(
                    presetLookup.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
        } else if (dim.equals(Level.END)) {
            settingsKey = NoiseGeneratorSettings.END;
            var biomeLookup = registries.lookupOrThrow(Registries.BIOME);
            biomeSource = TheEndBiomeSource.create(biomeLookup);
        } else {
            settingsKey = NoiseGeneratorSettings.OVERWORLD;
            var presetLookup = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
            biomeSource = MultiNoiseBiomeSource.createFromPreset(
                    presetLookup.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        }

        Holder<NoiseGeneratorSettings> settingsHolder = registries
                .lookupOrThrow(Registries.NOISE_GENERATOR_SETTINGS)
                .getOrThrow(settingsKey);
        RandomState randomState = RandomState.create(registries, settingsKey, seed);
        return new SeedContext(dim, seed, randomState, biomeSource, settingsHolder.value());
    }
}
