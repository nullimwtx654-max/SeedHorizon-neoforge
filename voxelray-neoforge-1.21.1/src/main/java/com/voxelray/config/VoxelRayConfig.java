package com.voxelray.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public final class VoxelRayConfig {
    public static final Client CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;

    static {
        Pair<Client, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = pair.getLeft();
        CLIENT_SPEC = pair.getRight();
    }

    private VoxelRayConfig() {
    }

    public static boolean isActive() {
        return CLIENT_SPEC.isLoaded() && CLIENT.enabled.get();
    }

    public static final class Client {
        public final ModConfigSpec.BooleanValue enabled;
        public final ModConfigSpec.IntValue voxelChunkRadius;
        public final ModConfigSpec.IntValue downscaleFactor;
        public final ModConfigSpec.IntValue maxRaySteps;

        public final ModConfigSpec.DoubleValue shadowStrength;
        public final ModConfigSpec.DoubleValue shadowTintR;
        public final ModConfigSpec.DoubleValue shadowTintG;
        public final ModConfigSpec.DoubleValue shadowTintB;
        public final ModConfigSpec.DoubleValue sunWarmth;

        public final ModConfigSpec.BooleanValue enableAcesTonemap;
        public final ModConfigSpec.DoubleValue exposure;
        public final ModConfigSpec.DoubleValue contrast;
        public final ModConfigSpec.DoubleValue saturation;
        public final ModConfigSpec.DoubleValue vibrance;

        Client(ModConfigSpec.Builder builder) {
            builder.push("raytracing");
            enabled = builder
                    .comment("Enable VoxelRay 64-tree raytraced shadows and color grading.")
                    .define("enabled", true);
            voxelChunkRadius = builder
                    .comment("Chunk radius around the player to index into the 64-tree (default 6 = 13x13 chunks).")
                    .defineInRange("voxelChunkRadius", 6, 3, 10);
            downscaleFactor = builder
                    .comment("Screen pixel block size for shadow raymarching (3 = 3x3 blocks = 1/9th rays).")
                    .defineInRange("downscaleFactor", 3, 1, 4);
            maxRaySteps = builder
                    .comment("Maximum 64-tree DDA steps per ray.")
                    .defineInRange("maxRaySteps", 48, 16, 96);
            builder.pop();

            builder.push("color_tuning");
            shadowStrength = builder
                    .comment("How dark raytraced shadows are (0.0 = off, 0.55 = natural, 0.8 = dramatic).")
                    .defineInRange("shadowStrength", 0.52, 0.0, 0.90);
            shadowTintR = builder.defineInRange("shadowTintR", 0.62, 0.2, 1.2);
            shadowTintG = builder.defineInRange("shadowTintG", 0.72, 0.2, 1.2);
            shadowTintB = builder.defineInRange("shadowTintB", 0.96, 0.2, 1.4);
            sunWarmth = builder
                    .comment("Warm sunlight boost on lit surfaces, stronger during golden hour.")
                    .defineInRange("sunWarmth", 0.16, 0.0, 0.60);

            enableAcesTonemap = builder
                    .comment("Apply ACES filmic tone mapping for crisp highlights and rich shadows.")
                    .define("enableAcesTonemap", true);
            exposure = builder.defineInRange("exposure", 1.08, 0.5, 2.0);
            contrast = builder.defineInRange("contrast", 1.06, 0.7, 1.5);
            saturation = builder.defineInRange("saturation", 1.10, 0.5, 1.6);
            vibrance = builder.defineInRange("vibrance", 0.22, 0.0, 0.8);
            builder.pop();
        }
    }
}
