package com.seedhorizon.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.seedhorizon.SeedHorizonMod;
import com.seedhorizon.compat.SodiumCompat;
import com.seedhorizon.config.SeedHorizonConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages async SeedGen LOD section generation, eviction, Sodium-compatible rendering,
 * and client commands.
 */
public final class LodRenderHandler {
    private static final int MAX_UPLOADS_PER_FRAME = 6;
    private static final int MAX_IN_FLIGHT = 24;

    private static final Map<Long, LodSection> SECTIONS = new ConcurrentHashMap<>();
    private static final Set<Long> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<LodSection> PENDING_UPLOADS = new ConcurrentLinkedQueue<>();

    private static final ExecutorService WORKER_POOL = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
            r -> {
                Thread t = new Thread(r, "SeedHorizon-LOD-Worker");
                t.setDaemon(true);
                return t;
            });

    private static volatile String lastDimensionId = "";

    private LodRenderHandler() {
    }

    private static long packKey(int sx, int sz) {
        return (((long) sx) << 32) | (sz & 0xFFFFFFFFL);
    }

    public static void clearAll() {
        IN_FLIGHT.clear();
        LodSection queued;
        while ((queued = PENDING_UPLOADS.poll()) != null) {
            queued.close();
        }
        for (LodSection sec : SECTIONS.values()) {
            sec.close();
        }
        SECTIONS.clear();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAll();
        lastDimensionId = "";
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!SeedHorizonConfig.isActive()) {
            if (!SECTIONS.isEmpty()) {
                clearAll();
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }

        String dimId = level.dimension().location().toString();
        if (!dimId.equals(lastDimensionId)) {
            clearAll();
            lastDimensionId = dimId;
        }

        SeedStateManager.SeedContext ctx = SeedStateManager.getOrCreate(level);
        if (ctx == null) {
            return;
        }

        int playerChunkX = mc.player.chunkPosition().x;
        int playerChunkZ = mc.player.chunkPosition().z;
        int playerSecX = Math.floorDiv(playerChunkX, LodSection.SIZE_CHUNKS);
        int playerSecZ = Math.floorDiv(playerChunkZ, LodSection.SIZE_CHUNKS);

        int lodChunks = SeedHorizonConfig.getLodRenderDistance();
        int realCapChunks = SeedHorizonConfig.getRealRenderDistanceCap();
        int maxSecRadius = (lodChunks / LodSection.SIZE_CHUNKS) + 1;
        int minRealSecRadius = Math.max(0, (realCapChunks - 2) / LodSection.SIZE_CHUNKS);

        // Evict out-of-range sections
        int evictRadius = maxSecRadius + 3;
        Iterator<Map.Entry<Long, LodSection>> it = SECTIONS.entrySet().iterator();
        while (it.hasNext()) {
            LodSection sec = it.next().getValue();
            if (Math.abs(sec.getSectionX() - playerSecX) > evictRadius
                    || Math.abs(sec.getSectionZ() - playerSecZ) > evictRadius) {
                sec.close();
                it.remove();
            }
        }

        // Schedule missing sections in concentric rings
        int nearStep = SeedHorizonConfig.CLIENT.nearLodStepBlocks.get();
        int farStep = SeedHorizonConfig.CLIENT.farLodStepBlocks.get();

        for (int r = minRealSecRadius; r <= maxSecRadius; r++) {
            if (IN_FLIGHT.size() >= MAX_IN_FLIGHT) {
                break;
            }
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    if (IN_FLIGHT.size() >= MAX_IN_FLIGHT) {
                        break;
                    }
                    int sx = playerSecX + dx;
                    int sz = playerSecZ + dz;
                    long key = packKey(sx, sz);
                    if (SECTIONS.containsKey(key) || !IN_FLIGHT.add(key)) {
                        continue;
                    }

                    int step = (r <= 8) ? nearStep : farStep;
                    WORKER_POOL.execute(() -> {
                        try {
                            LodSection generated = LodSection.generate(ctx, sx, sz, step);
                            PENDING_UPLOADS.add(generated);
                        } catch (Exception e) {
                            SeedHorizonMod.LOGGER.debug("LOD generation failed at ({}, {}): {}", sx, sz, e.getMessage());
                        } finally {
                            IN_FLIGHT.remove(key);
                        }
                    });
                }
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return;
        }
        if (!SeedHorizonConfig.isActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }

        // Upload a bounded number of generated sections per frame to prevent stutter
        int uploadedThisFrame = 0;
        while (uploadedThisFrame < MAX_UPLOADS_PER_FRAME) {
            LodSection sec = PENDING_UPLOADS.poll();
            if (sec == null) {
                break;
            }
            sec.uploadToGpu();
            long key = packKey(sec.getSectionX(), sec.getSectionZ());
            LodSection old = SECTIONS.put(key, sec);
            if (old != null) {
                old.close();
            }
            uploadedThisFrame++;
        }

        if (SECTIONS.isEmpty()) {
            return;
        }

        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        Frustum frustum = event.getFrustum();

        int playerChunkX = mc.player.chunkPosition().x;
        int playerChunkZ = mc.player.chunkPosition().z;
        int realCap = SeedHorizonConfig.getRealRenderDistanceCap();
        // Leave a 1-chunk overlap skirt at the real-world boundary so chunks streaming in have no gap
        int skipRealInnerChunks = Math.max(1, realCap - 1);

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        modelView.mul(poseStack.last().pose());
        Matrix4f projection = event.getProjectionMatrix();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        for (LodSection sec : SECTIONS.values()) {
            VertexBuffer vbo = sec.getVertexBuffer();
            if (vbo == null || !sec.isUploaded()) {
                continue;
            }

            int secCenterChunkX = sec.getSectionX() * LodSection.SIZE_CHUNKS + 1;
            int secCenterChunkZ = sec.getSectionZ() * LodSection.SIZE_CHUNKS + 1;
            if (Math.abs(secCenterChunkX - playerChunkX) < skipRealInnerChunks
                    && Math.abs(secCenterChunkZ - playerChunkZ) < skipRealInnerChunks) {
                continue;
            }

            if (!frustum.isVisible(sec.getBounds())) {
                continue;
            }

            vbo.bind();
            vbo.drawWithShader(modelView, projection, shader);
        }

        VertexBuffer.unbind();
        poseStack.popPose();
    }

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!SeedHorizonConfig.isActive() || !SeedHorizonConfig.CLIENT.showF3DebugInfo.get()) {
            return;
        }
        String compat = SodiumCompat.isSodiumLoaded() ? "Sodium"
                : (SodiumCompat.isEmbeddiumLoaded() ? "Embeddium" : "Vanilla");
        event.getLeft().add(String.format(
                "[SeedHorizon] LOD: %dch | Real Cap: %dch | Sections: %d | Renderer: %s",
                SeedHorizonConfig.getLodRenderDistance(),
                SeedHorizonConfig.getRealRenderDistanceCap(),
                SECTIONS.size(),
                compat));
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("seedhorizon")
                        .then(Commands.literal("status").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                    "SeedHorizon active=%s, LOD=%d chunks, RealCap=%d chunks, Sections=%d, Sodium=%s",
                                    SeedHorizonConfig.isActive(),
                                    SeedHorizonConfig.getLodRenderDistance(),
                                    SeedHorizonConfig.getRealRenderDistanceCap(),
                                    SECTIONS.size(),
                                    SodiumCompat.isAnySodiumDerivativeLoaded())), false);
                            return 1;
                        }))
                        .then(Commands.literal("reload").executes(ctx -> {
                            clearAll();
                            SeedStateManager.invalidate();
                            ctx.getSource().sendSuccess(() -> Component.literal("SeedHorizon LOD cache cleared."), false);
                            return 1;
                        }))
                        .then(Commands.literal("seed")
                                .then(Commands.argument("value", StringArgumentType.greedyString()).executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "value").trim();
                                    long parsed;
                                    try {
                                        parsed = Long.parseLong(raw);
                                    } catch (NumberFormatException ignored) {
                                        parsed = raw.hashCode();
                                    }
                                    SeedStateManager.setManualSeed(parsed);
                                    clearAll();
                                    long finalSeed = parsed;
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("SeedHorizon multiplayer seed set to " + finalSeed),
                                            false);
                                    return 1;
                                })))
        );
    }
}
