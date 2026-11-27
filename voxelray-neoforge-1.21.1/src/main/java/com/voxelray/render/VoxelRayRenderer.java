package com.voxelray.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.voxelray.config.VoxelRayConfig;
import com.voxelray.voxel.VoxelTreeBuilder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

/**
 * Native NeoForge post-world pass executing the 1/9th-res 64-tree raytraced shadows
 * and ACES color-grading pipeline without requiring Iris or OptiFine.
 */
public final class VoxelRayRenderer {
    private static final VoxelTreeBuilder TREE_BUILDER = new VoxelTreeBuilder();
    private static final VoxelRayShaderProgram SHADERS = new VoxelRayShaderProgram();

    private VoxelRayRenderer() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!VoxelRayConfig.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        TREE_BUILDER.update(level, mc.player.chunkPosition().x, mc.player.chunkPosition().z);
    }

    @SubscribeEvent
    public static void onBlockChanged(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel().isClientSide()) {
            TREE_BUILDER.invalidateBlock(event.getPos());
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TREE_BUILDER.destroy();
        SHADERS.destroy();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !VoxelRayConfig.isActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || TREE_BUILDER.getGridDiameter() == 0) {
            return;
        }

        RenderTarget mainTarget = mc.getMainRenderTarget();
        int width = mainTarget.viewWidth;
        int height = mainTarget.viewHeight;
        if (width <= 0 || height <= 0) {
            return;
        }

        int downscale = VoxelRayConfig.CLIENT.downscaleFactor.get();
        SHADERS.ensureInitialized(width, height, downscale);
        int voxelTexId = TREE_BUILDER.uploadIfDirty();

        // Save key GL state
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDepthMask(false);

        // 1. Copy current scene color so composite pass can read it safely
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, SHADERS.getSceneCopyTex());
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);

        // Compute sun direction from celestial angle
        float sunAngle = level.getSunAngle(event.getPartialTick().getGameTimeDeltaPartialTick(true));
        float sunX = -Mth.sin(sunAngle);
        float sunY = Mth.cos(sunAngle);
        float sunZ = 0.25f;
        float dayFactor = Mth.clamp((sunY + 0.1f) * 3.0f, 0.0f, 1.0f);
        if (sunY < 0.0f) {
            // Moonlight fallback direction
            sunX = -sunX;
            sunY = -sunY;
        }

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();

        // Build inverse (Projection * ModelView) matrix
        Matrix4f invProjView = new Matrix4f(event.getProjectionMatrix())
                .mul(event.getModelViewMatrix())
                .invert();

        // 2. Render 1/9th-res 64-tree DDA shadow mask
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, SHADERS.getShadowFbo());
        GL11.glViewport(0, 0, SHADERS.getShadowWidth(), SHADERS.getShadowHeight());

        int rayProg = SHADERS.getRayProgram();
        GL20.glUseProgram(rayProg);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTarget.getDepthTextureId());
        GL20.glUniform1i(GL20.glGetUniformLocation(rayProg, "u_SceneDepth"), 0);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, voxelTexId);
        GL20.glUniform1i(GL20.glGetUniformLocation(rayProg, "u_VoxelTree"), 1);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matBuf = stack.mallocFloat(16);
            invProjView.get(matBuf);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(rayProg, "u_InvProjView"), false, matBuf);
        }

        GL20.glUniform3f(GL20.glGetUniformLocation(rayProg, "u_CameraPos"),
                (float) camPos.x, (float) camPos.y, (float) camPos.z);
        GL20.glUniform3f(GL20.glGetUniformLocation(rayProg, "u_SunDir"), sunX, sunY, sunZ);
        GL20.glUniform3i(GL20.glGetUniformLocation(rayProg, "u_GridMinBlock"),
                TREE_BUILDER.getGridMinBlockX(),
                TREE_BUILDER.getGridMinBlockY(),
                TREE_BUILDER.getGridMinBlockZ());
        GL20.glUniform1i(GL20.glGetUniformLocation(rayProg, "u_GridDiameter"), TREE_BUILDER.getGridDiameter());
        GL20.glUniform1i(GL20.glGetUniformLocation(rayProg, "u_SectionHeight"), VoxelTreeBuilder.SECTION_HEIGHT);
        GL20.glUniform1i(GL20.glGetUniformLocation(rayProg, "u_MaxSteps"), VoxelRayConfig.CLIENT.maxRaySteps.get());

        GL30.glBindVertexArray(SHADERS.getEmptyVao());
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        // 3. Bilateral upscale + color grading pass back into mainRenderTarget
        mainTarget.bindWrite(true);
        GL11.glViewport(0, 0, width, height);

        int compProg = SHADERS.getCompositeProgram();
        GL20.glUseProgram(compProg);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, SHADERS.getSceneCopyTex());
        GL20.glUniform1i(GL20.glGetUniformLocation(compProg, "u_SceneColor"), 0);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTarget.getDepthTextureId());
        GL20.glUniform1i(GL20.glGetUniformLocation(compProg, "u_SceneDepth"), 1);

        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, SHADERS.getShadowTex());
        GL20.glUniform1i(GL20.glGetUniformLocation(compProg, "u_ShadowMask"), 2);

        GL20.glUniform2f(GL20.glGetUniformLocation(compProg, "u_ShadowTexelSize"),
                1.0f / SHADERS.getShadowWidth(), 1.0f / SHADERS.getShadowHeight());

        GL20.glUniform1f(GL20.glGetUniformLocation(compProg, "u_ShadowStrength"),
                VoxelRayConfig.CLIENT.shadowStrength.get().floatValue());
        GL20.glUniform3f(GL20.glGetUniformLocation(compProg, "u_ShadowTint"),
                VoxelRayConfig.CLIENT.shadowTintR.get().floatValue(),
                VoxelRayConfig.CLIENT.shadowTintG.get().floatValue(),
                VoxelRayConfig.CLIENT.shadowTintB.get().floatValue());
        GL20.glUniform1f(GL20.glGetUniformLocation(compProg, "u_SunWarmth"),
                VoxelRayConfig.CLIENT.sunWarmth.get().floatValue());
        GL20.glUniform1f(GL20.glGetUniformLocation(compProg, "u_DayFactor"), dayFactor);
        GL20.glUniform1i(GL20.glGetUniformLocation(compProg, "u_EnableAces"),
                VoxelRayConfig.CLIENT.enableAcesTonemap.get() ? 1 : 0);
        GL20.glUniform1f(GL20.glGetUniformLocation(compProg, "u_Exposure"),
                VoxelRayConfig.CLIENT.exposure.get().floatValue());
        GL20.glUniform1f(GL20.glGetUniformLocation(compProg, "u_Contrast"),
                VoxelRayConfig.CLIENT.contrast.get().floatValue());
        GL20.glUniform1f(GL20.glGetUniformLocation(compProg, "u_Saturation"),
                VoxelRayConfig.CLIENT.saturation.get().floatValue());
        GL20.glUniform1f(GL20.glGetUniformLocation(compProg, "u_Vibrance"),
                VoxelRayConfig.CLIENT.vibrance.get().floatValue());

        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        // Restore GL state cleanly for HUD / Sodium
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL30.glBindVertexArray(prevVao);
        GL20.glUseProgram(prevProgram);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL11.glDepthMask(true);
        if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blendWasEnabled) GL11.glEnable(GL11.GL_BLEND);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("voxelray")
                        .then(Commands.literal("toggle").executes(ctx -> {
                            boolean next = !VoxelRayConfig.CLIENT.enabled.get();
                            VoxelRayConfig.CLIENT.enabled.set(next);
                            ctx.getSource().sendSuccess(() -> Component.literal("VoxelRay " + (next ? "enabled" : "disabled")), false);
                            return 1;
                        }))
                        .then(Commands.literal("rebuild").executes(ctx -> {
                            TREE_BUILDER.invalidateAll();
                            ctx.getSource().sendSuccess(() -> Component.literal("VoxelRay 64-tree rebuilt."), false);
                            return 1;
                        }))
                        .then(Commands.literal("status").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                    "VoxelRay active=%s | Grid=%dx%d chunks | Sections=%d | Block=%dx%d",
                                    VoxelRayConfig.isActive(),
                                    TREE_BUILDER.getGridDiameter(), TREE_BUILDER.getGridDiameter(),
                                    TREE_BUILDER.getBuiltSectionCount(),
                                    VoxelRayConfig.CLIENT.downscaleFactor.get(),
                                    VoxelRayConfig.CLIENT.downscaleFactor.get())), false);
                            return 1;
                        }))
                        .then(Commands.literal("preset")
                                .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name").toLowerCase();
                                    switch (name) {
                                        case "cinematic" -> {
                                            VoxelRayConfig.CLIENT.shadowStrength.set(0.62);
                                            VoxelRayConfig.CLIENT.sunWarmth.set(0.24);
                                            VoxelRayConfig.CLIENT.exposure.set(1.10);
                                            VoxelRayConfig.CLIENT.contrast.set(1.10);
                                            VoxelRayConfig.CLIENT.saturation.set(1.14);
                                            VoxelRayConfig.CLIENT.vibrance.set(0.28);
                                            VoxelRayConfig.CLIENT.enableAcesTonemap.set(true);
                                        }
                                        case "vivid" -> {
                                            VoxelRayConfig.CLIENT.shadowStrength.set(0.48);
                                            VoxelRayConfig.CLIENT.sunWarmth.set(0.20);
                                            VoxelRayConfig.CLIENT.exposure.set(1.12);
                                            VoxelRayConfig.CLIENT.contrast.set(1.06);
                                            VoxelRayConfig.CLIENT.saturation.set(1.22);
                                            VoxelRayConfig.CLIENT.vibrance.set(0.35);
                                            VoxelRayConfig.CLIENT.enableAcesTonemap.set(true);
                                        }
                                        case "natural" -> {
                                            VoxelRayConfig.CLIENT.shadowStrength.set(0.45);
                                            VoxelRayConfig.CLIENT.sunWarmth.set(0.12);
                                            VoxelRayConfig.CLIENT.exposure.set(1.04);
                                            VoxelRayConfig.CLIENT.contrast.set(1.03);
                                            VoxelRayConfig.CLIENT.saturation.set(1.05);
                                            VoxelRayConfig.CLIENT.vibrance.set(0.15);
                                            VoxelRayConfig.CLIENT.enableAcesTonemap.set(true);
                                        }
                                        case "potato" -> {
                                            VoxelRayConfig.CLIENT.downscaleFactor.set(4);
                                            VoxelRayConfig.CLIENT.maxRaySteps.set(28);
                                            VoxelRayConfig.CLIENT.shadowStrength.set(0.45);
                                        }
                                        default -> {
                                            ctx.getSource().sendFailure(Component.literal("Unknown preset. Use: cinematic, vivid, natural, potato"));
                                            return 0;
                                        }
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal("Applied VoxelRay preset: " + name), false);
                                    return 1;
                                })))
        );
    }
}
