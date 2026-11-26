package com.seedhorizon.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public final class SeedHorizonConfig {
    public static final Client CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;

    static {
        Pair<Client, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = pair.getLeft();
        CLIENT_SPEC = pair.getRight();
    }

    private SeedHorizonConfig() {
    }

    public static boolean isActive() {
        return CLIENT_SPEC.isLoaded() && CLIENT.enabled.get();
    }

    public static int getLodRenderDistance() {
        return CLIENT_SPEC.isLoaded() ? CLIENT.lodRenderDistance.get() : 32;
    }

    public static int getRealRenderDistanceCap() {
        return CLIENT_SPEC.isLoaded() ? CLIENT.realRenderDistanceCap.get() : 6;
    }

    public static final class Client {
        public final ModConfigSpec.BooleanValue enabled;
        public final ModConfigSpec.IntValue lodRenderDistance;
        public final ModConfigSpec.IntValue realRenderDistanceCap;
        public final ModConfigSpec.IntValue fullDetailExtraChunks;
        public final ModConfigSpec.IntValue midLodStepBlocks;
        public final ModConfigSpec.IntValue farLodStepBlocks;
        public final ModConfigSpec.BooleanValue enableLodTrees;
        public final ModConfigSpec.BooleanValue enableFogPushback;
        public final ModConfigSpec.DoubleValue fogStartFraction;
        public final ModConfigSpec.DoubleValue fogEndFraction;
        public final ModConfigSpec.BooleanValue syncIntegratedServerViewDistance;
        public final ModConfigSpec.BooleanValue showF3DebugInfo;

        Client(ModConfigSpec.Builder builder) {
            builder.push("general");

            enabled = builder
                    .comment("Enable SeedHorizon standalone LOD horizon rendering and 6x real render distance capping.")
                    .translation("seedhorizon.configuration.enabled")
                    .define("enabled", true);

            lodRenderDistance = builder
                    .comment("Extended LOD render distance in chunks (default: 32).")
                    .translation("seedhorizon.configuration.lodRenderDistance")
                    .defineInRange("lodRenderDistance", 32, 8, 64);

            realRenderDistanceCap = builder
                    .comment("Maximum real Minecraft chunk render distance in chunks (default: 6).")
                    .translation("seedhorizon.configuration.realRenderDistanceCap")
                    .defineInRange("realRenderDistanceCap", 6, 2, 16);

            fullDetailExtraChunks = builder
                    .comment("Number of fake LOD chunks beyond the real render cap rendered at full 1x1 block detail (default: 4).")
                    .defineInRange("fullDetailExtraChunks", 4, 0, 10);

            midLodStepBlocks = builder
                    .comment("Horizontal sampling step in blocks for mid LODs (chunks 11..20, default: 2).")
                    .defineInRange("midLodStepBlocks", 2, 1, 8);

            farLodStepBlocks = builder
                    .comment("Horizontal sampling step in blocks for far LODs (chunks 21..32+, default: 4).")
                    .translation("seedhorizon.configuration.farLodStepBlocks")
                    .defineInRange("farLodStepBlocks", 4, 2, 16);

            enableLodTrees = builder
                    .comment("Render multi-tier 3D LOD trees (spruce cones, rounded oak/birch canopies, trunks) in forested biomes.")
                    .define("enableLodTrees", true);

            syncIntegratedServerViewDistance = builder
                    .comment("Automatically cap the singleplayer integrated server view distance to match realRenderDistanceCap.")
                    .translation("seedhorizon.configuration.syncIntegratedServerViewDistance")
                    .define("syncIntegratedServerViewDistance", true);

            showF3DebugInfo = builder
                    .comment("Show SeedHorizon LOD stats in the F3 debug overlay.")
                    .translation("seedhorizon.configuration.showF3DebugInfo")
                    .define("showF3DebugInfo", true);

            builder.pop();
            builder.push("fog");

            enableFogPushback = builder
                    .comment("Push terrain distance fog out to the LOD horizon so fog does not clip at the 6x real chunk boundary.")
                    .translation("seedhorizon.configuration.enableFogPushback")
                    .define("enableFogPushback", true);

            fogStartFraction = builder
                    .comment("Fraction of LOD horizon distance where terrain fog begins (default: 0.60).")
                    .translation("seedhorizon.configuration.fogStartFraction")
                    .defineInRange("fogStartFraction", 0.60, 0.10, 0.95);

            fogEndFraction = builder
                    .comment("Fraction of LOD horizon distance where terrain fog reaches full opacity (default: 0.98).")
                    .translation("seedhorizon.configuration.fogEndFraction")
                    .defineInRange("fogEndFraction", 0.98, 0.50, 1.25);

            builder.pop();
        }
    }
}
