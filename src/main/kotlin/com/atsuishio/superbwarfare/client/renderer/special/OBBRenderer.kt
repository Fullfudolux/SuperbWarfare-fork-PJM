package com.atsuishio.superbwarfare.client.renderer.special

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.OBB
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Quaternionf

/**
 * Codes based on @AnECanSaiTin's [HitboxAPI](https://github.com/AnECanSaiTin/HitboxAPI)
 */
object OBBRenderer {
    fun render(
        entity: VehicleEntity,
        obbList: MutableList<OBB>,
        poseStack: PoseStack,
        buffer: VertexConsumer,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        pPartialTicks: Float
    ) {
        // entity.position() is the raw, tick-end position (no interpolation) —
        // but obb.center was computed via updateOBB(pPartialTicks), which DOES
        // interpolate (Mth.lerp(pt, xo, x) etc., same as the poseStack this is
        // drawn into). Subtracting the non-interpolated position here made the
        // local offset sawtooth by up to a full tick's movement delta every
        // tick while the vehicle was moving — the box visibly jumping back at
        // the start of each tick and smoothly re-catching up until the next.
        val position = Vec3(
            Mth.lerp(pPartialTicks.toDouble(), entity.xo, entity.x),
            Mth.lerp(pPartialTicks.toDouble(), entity.yo, entity.y),
            Mth.lerp(pPartialTicks.toDouble(), entity.zo, entity.z)
        )
        for (obb in obbList) {
            val center = obb.center
            val halfExtents = obb.extents
            val rotation = obb.rotation
            // PJM: свой цвет каждому типу части, чтобы на F3+B их было видно по отдельности
            val color = PART_COLORS[obb.part] ?: floatArrayOf(red, green, blue)
            renderOBB(
                poseStack, buffer,
                center.x() - position.x(), center.y() - position.y(), center.z() - position.z(),
                rotation,
                halfExtents.x(), halfExtents.y(), halfExtents.z(),
                color[0], color[1], color[2], alpha
            )
        }
    }

    // PJM: BODY намеренно отсутствует — он рисуется цветом, который передал вызывающий
    private val PART_COLORS = mapOf(
        OBB.Part.COLLISION to floatArrayOf(1f, 0f, 0f),      // красный
        OBB.Part.INTERACTIVE to floatArrayOf(1f, 0.8f, 0f),  // оранжевый
        OBB.Part.TURRET to floatArrayOf(0f, 0.8f, 1f),       // голубой
        OBB.Part.MAIN_ENGINE to floatArrayOf(1f, 0f, 1f),    // пурпурный
        OBB.Part.SUB_ENGINE to floatArrayOf(0.6f, 0.2f, 1f), // фиолетовый
        OBB.Part.WHEEL_LEFT to floatArrayOf(0.2f, 0.4f, 1f), // синий
        OBB.Part.WHEEL_RIGHT to floatArrayOf(1f, 1f, 0f),    // жёлтый
        OBB.Part.EMPTY to floatArrayOf(0.6f, 0.6f, 0.6f),    // серый
    )

    fun renderOBB(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        centerX: Double,
        centerY: Double,
        centerZ: Double,
        rotation: Quaterniond,
        halfX: Double,
        halfY: Double,
        halfZ: Double,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float
    ) {
        poseStack.pushPose()
        poseStack.translate(centerX, centerY, centerZ)
        poseStack.mulPose(Quaternionf(rotation.x, rotation.y, rotation.z, rotation.w))
        LevelRenderer.renderLineBox(
            poseStack,
            buffer,
            -halfX,
            -halfY,
            -halfZ,
            halfX,
            halfY,
            halfZ,
            red,
            green,
            blue,
            alpha
        )
        poseStack.popPose()
    }
}
