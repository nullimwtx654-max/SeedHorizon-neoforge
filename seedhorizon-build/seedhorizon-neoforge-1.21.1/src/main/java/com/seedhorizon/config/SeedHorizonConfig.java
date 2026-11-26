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
        public final ModConfigSpec.IntValue nearLodStepBlocks;
        public final ModConfigSpec.IntValue farLodStepBlocks;
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

            nearLodStepBlocks = builder
                    .comment("Horizontal sampling step in blocks for near LODs (chunks 5..16). Lower = sharper, higher = faster.")
                    .translation("seedhorizon.configuration.nearLodStepBlocks")
                    .defineInRange("nearLodStepBlocks", 4, 2, 8);

            farLodStepBlocks = builder
                    .comment("Horizontal sampling step in blocks for far LODs (chunks 17..32+).")
                    .translation("seedhorizon.configuration.farLodStepBlocks")
                    .defineInRange("farLodStepBlocks", 8, 4, 16);

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
                    .comment("Fraction of LOD horizon distance where terrain fog begins (default: 0.72).")
                    .translation("seedhorizon.configuration.fogStartFraction")
                    .defineInRange("fogStartFraction", 0.72, 0.10, 0.95);

            fogEndFraction = builder
                    .comment("Fraction of LOD horizon distance where terrain fog reaches full opacity (default: 1.00).")
                    .translation("seedhorizon.configuration.fogEndFraction")
                    .defineInRange("fogEndFraction", 1.00, 0.50, 1.25);

            builder.pop();
        }
    }
}
