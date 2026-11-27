package com.seedhorizon.lod;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.seedhorizon.SeedHorizonMod;
import com.seedhorizon.compat.SodiumCompat;
import com.seedhorizon.config.SeedHorizonConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages 1x1-chunk async SeedGen LOD generation, pixel-exact camera alignment via
 * {@code event.getModelViewMatrix()}, and Distant-Horizons-style depth-occluded rendering.
 */
public final class LodRenderHandler {
    private static final int MAX_UPLOADS_PER_FRAME = 16;
    private static final int MAX_IN_FLIGHT = 40;

    private static final Map<Long, LodSection> SECTIONS = new ConcurrentHashMap<>();
    private static final Set<Long> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<LodSection> PENDING_UPLOADS = new ConcurrentLinkedQueue<>();
    private static final LodShader LOD_SHADER = new LodShader();
    private static final LodFramebuffer LOD_FBO = new LodFramebuffer();

    private static final ExecutorService WORKER_POOL = Executors.newFixedThreadPool(
            Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 2)),
            r -> {
                Thread t = new Thread(r, "SeedHorizon-LOD-Worker");
                t.setDaemon(true);
                return t;
            });

    private static volatile String lastDimensionId = "";

    private LodRenderHandler() {
    }

    private static long packKey(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
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
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!SeedHorizonConfig.isActive() || !event.getLevel().isClientSide()) {
            return;
        }
        if (event.getLevel() instanceof ClientLevel clientLevel && event.getChunk() instanceof LevelChunk levelChunk) {
            Minecraft.getInstance().execute(() -> {
                LodSection snap = RealChunkSnapshot.captureFromRealChunk(clientLevel, levelChunk);
                if (snap != null) {
                    long key = packKey(snap.getChunkX(), snap.getChunkZ());
                    LodSection old = SECTIONS.put(key, snap);
                    if (old != null) old.close();
                }
            });
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAll();
        LOD_SHADER.destroy();
        LOD_FBO.destroy();
        LodAtlasSprites.invalidate();
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

        int lodChunks = SeedHorizonConfig.getLodRenderDistance();
        int realCapChunks = SeedHorizonConfig.getRealRenderDistanceCap();
        // Start generating well inside the cap so unloaded/circular edge chunks have zero sky gap
        int minRadius = Math.max(1, realCapChunks - 3);

        // Evict out-of-range chunks
        int evictRadius = lodChunks + 4;
        Iterator<Map.Entry<Long, LodSection>> it = SECTIONS.entrySet().iterator();
        while (it.hasNext()) {
            LodSection sec = it.next().getValue();
            if (Math.abs(sec.getChunkX() - playerChunkX) > evictRadius
                    || Math.abs(sec.getChunkZ() - playerChunkZ) > evictRadius) {
                sec.close();
                it.remove();
            }
        }

        int fullDetailMaxRadius = realCapChunks + SeedHorizonConfig.CLIENT.fullDetailExtraChunks.get();
        int midStep = SeedHorizonConfig.CLIENT.midLodStepBlocks.get();
        int farStep = SeedHorizonConfig.CLIENT.farLodStepBlocks.get();

        for (int r = minRadius; r <= lodChunks; r++) {
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
                    int cx = playerChunkX + dx;
                    int cz = playerChunkZ + dz;
                    long key = packKey(cx, cz);
                    if (SECTIONS.containsKey(key) || !IN_FLIGHT.add(key)) {
                        continue;
                    }

                    int step = (r <= fullDetailMaxRadius) ? 1 : ((r <= 20) ? midStep : farStep);
                    WORKER_POOL.execute(() -> {
                        try {
                            LodSection generated = LodSection.generate(ctx, cx, cz, step);
                            PENDING_UPLOADS.add(generated);
                        } catch (Exception e) {
                            SeedHorizonMod.LOGGER.debug("LOD generation failed at ({}, {}): {}", cx, cz, e.getMessage());
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
        // Render right after translucent/cutout terrain so mainTarget depth buffer contains ALL real blocks
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
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

        int uploadedThisFrame = 0;
        while (uploadedThisFrame < MAX_UPLOADS_PER_FRAME) {
            LodSection sec = PENDING_UPLOADS.poll();
            if (sec == null) {
                break;
            }
            sec.uploadToGpu();
            long key = packKey(sec.getChunkX(), sec.getChunkZ());
            LodSection existing = SECTIONS.get(key);
            if (existing != null && existing.isRealSnapshot()) {
                sec.close();
            } else {
                LodSection old = SECTIONS.put(key, sec);
                if (old != null) old.close();
            }
            uploadedThisFrame++;
        }

        if (SECTIONS.isEmpty() || !LOD_SHADER.ensureInitialized()) {
            return;
        }

        RenderTarget mainTarget = mc.getMainRenderTarget();
        int viewW = mainTarget.viewWidth;
        int viewH = mainTarget.viewHeight;
        if (viewW <= 0 || viewH <= 0 || !LOD_FBO.ensureInitialized(viewW, viewH)) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        Frustum frustum = event.getFrustum();

        // CRITICAL FIX: Use event.getModelViewMatrix() (actual camera rotation), NOT RenderSystem.getModelViewMatrix(),
        // then translate by -camPos so LOD world coordinates lock pixel-for-pixel onto real Minecraft chunks!
        Matrix4f modelView = new Matrix4f(event.getModelViewMatrix())
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);
        // Build dedicated DH-style deep projection (near=8.0, far=lodDist*2.5) for >160x better depth precision
        Matrix4f mcProj = event.getProjectionMatrix();
        float dhNear = 8.0f;
        float dhFar = Math.max(512.0f, SeedHorizonConfig.getLodRenderDistance() * 16.0f * 2.5f);
        Matrix4f projection = new Matrix4f(mcProj);
        projection.m22(-(dhFar + dhNear) / (dhFar - dhNear));
        projection.m32(-(2.0f * dhFar * dhNear) / (dhFar - dhNear));

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        float sunAngle = level.getSunAngle(partialTick);
        float sunCos = Mth.cos(sunAngle);
        float day = Mth.clamp(sunCos * 1.6f + 0.35f, 0.0f, 1.0f);
        float sunset = Mth.clamp(1.0f - Math.abs(sunCos) * 4.0f, 0.0f, 1.0f);
        float rain = level.getRainLevel(partialTick);

        float lightR = Mth.lerp(day, 0.24f, 1.00f) + sunset * 0.14f;
        float lightG = Mth.lerp(day, 0.28f, 0.99f) + sunset * 0.04f;
        float lightB = Mth.lerp(day, 0.38f, 0.98f) - sunset * 0.08f;
        float rainDim = 1.0f - rain * 0.22f;
        lightR *= rainDim;
        lightG *= rainDim;
        lightB *= rainDim;

        float[] fogColor = RenderSystem.getShaderFogColor();
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        float clipDist = Math.max(16.0f, (SeedHorizonConfig.getRealRenderDistanceCap() - 1.5f) * 16.0f);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        LOD_FBO.bindAndClear();
        GL20.glUseProgram(LOD_SHADER.getProgramId());

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, LodAtlasSprites.getBlockAtlasTextureId());
        GL20.glUniform1i(LOD_SHADER.getBlockAtlasLoc(), 0);
        GL20.glUniform1f(LOD_SHADER.getClipDistanceLoc(), clipDist);
        GL20.glUniform1f(LOD_SHADER.getSeaLevelLoc(), (float) level.getSeaLevel());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer mvBuf = stack.mallocFloat(16);
            FloatBuffer pBuf = stack.mallocFloat(16);
            modelView.get(mvBuf);
            projection.get(pBuf);
            GL20.glUniformMatrix4fv(LOD_SHADER.getModelViewMatLoc(), false, mvBuf);
            GL20.glUniformMatrix4fv(LOD_SHADER.getProjMatLoc(), false, pBuf);
        }

        GL20.glUniform1f(LOD_SHADER.getFogStartLoc(), fogStart);
        GL20.glUniform1f(LOD_SHADER.getFogEndLoc(), fogEnd);
        GL20.glUniform4f(LOD_SHADER.getFogColorLoc(), fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        GL20.glUniform3f(LOD_SHADER.getLightTintLoc(), lightR, lightG, lightB);

        int playerChunkX = mc.player.chunkPosition().x;
        int playerChunkZ = mc.player.chunkPosition().z;
        int innerSkipSq = Math.max(1, SeedHorizonConfig.getRealRenderDistanceCap() - 3);
        innerSkipSq = innerSkipSq * innerSkipSq;

        // Pass 1: Opaque terrain, underwater seabed, and organic 3D trees
        java.util.List<LodSection> visibleWithWater = new java.util.ArrayList<>();
        for (LodSection sec : SECTIONS.values()) {
            if (!sec.isUploaded()) {
                continue;
            }
            int dcx = sec.getChunkX() - playerChunkX;
            int dcz = sec.getChunkZ() - playerChunkZ;
            if (dcx * dcx + dcz * dcz < innerSkipSq) {
                continue;
            }
            if (!frustum.isVisible(sec.getBounds())) {
                continue;
            }
            VertexBuffer opaque = sec.getOpaqueVbo();
            if (opaque != null) {
                opaque.bind();
                opaque.draw();
            }
            if (sec.getWaterVbo() != null) {
                visibleWithWater.add(sec);
            }
        }

        // Pass 2: Translucent water surface over underwater seabed
        if (!visibleWithWater.isEmpty()) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
            for (LodSection sec : visibleWithWater) {
                VertexBuffer water = sec.getWaterVbo();
                if (water != null) {
                    water.bind();
                    water.draw();
                }
            }
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }

        VertexBuffer.unbind();
        LOD_FBO.compositeOntoMain(prevFbo, mainTarget.getDepthTextureId());
        GL20.glUseProgram(prevProgram);
    }

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!SeedHorizonConfig.isActive() || !SeedHorizonConfig.CLIENT.showF3DebugInfo.get()) {
            return;
        }
        String compat = SodiumCompat.isSodiumLoaded() ? "Sodium"
                : (SodiumCompat.isEmbeddiumLoaded() ? "Embeddium" : "Vanilla");
        event.getLeft().add(String.format(
                "[SeedHorizon] LOD: %dch | Real Cap: %dch | Chunks: %d | Renderer: %s",
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
                                    "SeedHorizon active=%s, LOD=%d chunks, RealCap=%d chunks, Chunks=%d, Sodium=%s",
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
