package dev.guildquests.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.guildquests.platform.PlatformClient;
import dev.guildquests.platform.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Client-side boss pin: a rotating red beacon beam at the boss spawn.
 * Driven by the guildquests:boss_pin S2C packet (lifetime 0 clears).
 */
public final class BossPinRenderer {
    private static final int LIFETIME_TICKS = 6000;
    private static boolean active;
    private static double x, y, z;
    private static int life;

    private BossPinRenderer() {
    }

    public static void init() {
        PlatformClient.onWorldRender(BossPinRenderer::render);
        PlatformClient.onClientTick(screen -> tickLife());
    }

    /** Counts the beam down once per client tick (not once per rendered frame). */
    private static void tickLife() {
        if (!active) return;
        if (life > 0) life--;
        if (life <= 0) active = false;
    }

    public static void set(int px, int py, int pz, int lifetimeTicks) {
        if (lifetimeTicks <= 0) {
            active = false;
            return;
        }
        x = px + 0.5;
        y = py;
        z = pz + 0.5;
        life = Math.min(lifetimeTicks, LIFETIME_TICKS);
        active = true;
    }

    public static void clear() {
        active = false;
    }

    private static void render(WorldRenderContext context) {
        if (!active) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        if (!client.level.dimension().location().toString().equals("minecraft:overworld")) return;

        float alpha = life < 200 ? life / 200f : 1.0f;
        Camera camera = context.camera();
        Vec3 camPos = camera.getPosition();
        PoseStack matrices = context.poseStack();
        if (matrices == null) return;

        matrices.pushPose();
        matrices.translate(x - camPos.x, y - camPos.y, z - camPos.z);
        float rot = (System.currentTimeMillis() % 36000L) / 100f;
        matrices.mulPose(new org.joml.Quaternionf().fromAxisAngleDeg(new Vector3f(0, 1, 0), rot));
        drawBeam(matrices, alpha);
        matrices.popPose();
    }

    private static void drawBeam(PoseStack matrices, float alpha) {
        Matrix4f matrix = matrices.last().pose();
        float half = 0.15f;
        float top = 128f;
        float r = 1f, g = 0.15f, b = 0.15f;
        float baseA = 0.35f * alpha;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableCull();

        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // -Z
        quad(buffer, matrix, -half, half, 0, -half, top, r, g, b, baseA);
        // +Z
        quad(buffer, matrix, -half, half, 0, half, top, r, g, b, baseA);
        // -X / +X use the same builder with the horizontal axis swapped
        buffer.addVertex(matrix, -half, 0, -half).setColor(r, g, b, baseA);
        buffer.addVertex(matrix, -half, 0, half).setColor(r, g, b, baseA);
        buffer.addVertex(matrix, -half, top, half).setColor(r, g, b, 0f);
        buffer.addVertex(matrix, -half, top, -half).setColor(r, g, b, 0f);
        buffer.addVertex(matrix, half, 0, -half).setColor(r, g, b, baseA);
        buffer.addVertex(matrix, half, 0, half).setColor(r, g, b, baseA);
        buffer.addVertex(matrix, half, top, half).setColor(r, g, b, 0f);
        buffer.addVertex(matrix, half, top, -half).setColor(r, g, b, 0f);
        BufferUploader.drawWithShader(buffer.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** Vertical quad facing +/-Z. */
    private static void quad(BufferBuilder buffer, Matrix4f matrix,
                             float x1, float x2, float y, float z, float top,
                             float r, float g, float b, float baseA) {
        buffer.addVertex(matrix, x1, y, z).setColor(r, g, b, baseA);
        buffer.addVertex(matrix, x2, y, z).setColor(r, g, b, baseA);
        buffer.addVertex(matrix, x2, top, z).setColor(r, g, b, 0f);
        buffer.addVertex(matrix, x1, top, z).setColor(r, g, b, 0f);
    }
}
