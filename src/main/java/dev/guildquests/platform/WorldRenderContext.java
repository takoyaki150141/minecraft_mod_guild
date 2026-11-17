package dev.guildquests.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;

/** Loader-neutral view of one world-render callback. */
public interface WorldRenderContext {
    Camera camera();

    PoseStack poseStack();
}
