package com.github.mcmodderanchor.simplebedrockmodel.v1.util;

import com.mojang.blaze3d.vertex.PoseStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

@OnlyIn(Dist.CLIENT)
public final class RenderDistance {
    private static long GUI_RENDER_TIMESTAMP = -1L;

    public static boolean inRenderHighPolyModelDistance(PoseStack poseStack, double distance) {
        if (isGuiRender()) {
            return true;
        }
        // todo 让配置项生效
        if (distance <= 0) {
            return false;
        }
        Matrix4f matrix4f = poseStack.last().pose();
        float viewDistance = matrix4f.m30() * matrix4f.m30() + matrix4f.m31() * matrix4f.m31() + matrix4f.m32() * matrix4f.m32();
        return viewDistance < distance * distance;
    }

    public static void markGuiRenderTimestamp() {
        GUI_RENDER_TIMESTAMP = System.currentTimeMillis();
    }

    private static boolean isGuiRender() {
        return System.currentTimeMillis() - GUI_RENDER_TIMESTAMP < 100;
    }
}
