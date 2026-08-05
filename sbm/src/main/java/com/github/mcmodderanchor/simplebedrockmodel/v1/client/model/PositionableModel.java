package com.github.mcmodderanchor.simplebedrockmodel.v1.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemDisplayContext;

public interface PositionableModel {
    void applyTransform(PoseStack poseStack, ItemDisplayContext ctx);
}
