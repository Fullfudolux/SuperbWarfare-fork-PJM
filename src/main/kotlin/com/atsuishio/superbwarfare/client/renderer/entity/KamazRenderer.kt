package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.client.model.entity.BedrockVehicleModel
import com.atsuishio.superbwarfare.entity.vehicle.KamazEntity
import com.atsuishio.superbwarfare.entity.vehicle.BasicGeoVehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import org.joml.Quaternionf

class KamazRenderer<T>(manager: EntityRendererProvider.Context) :
    SbmVehicleRenderer<T>(manager) where T : VehicleEntity, T : BasicGeoVehicleEntity {

    // Чисто визуальное увеличение модели: хитбокс, OBB, посадочные места и
    // точки выстрела считаются от данных сущности и не масштабируются.
    override fun renderScale(): Float {
        return 1.2f
    }

    override fun transformCustomModelPart(
        vehicle: T,
        model: BedrockVehicleModel,
        poseStack: PoseStack,
        entityYaw: Float,
        partialTicks: Float
    ) {
        super.transformCustomModelPart(vehicle, model, poseStack, entityYaw, partialTicks)

        // Fix right wheels: use rotateX (MUL) not rotationX (SET)
        model.rightWheels.forEach {
            it.rotation.rotateX(-3.0f * vehicle.rightWheelRot)
        }
        model.rightWheelsTurn.forEach {
            it.rotation.rotateX(-3.0f * vehicle.rightWheelRot)
        }

        // Steering wheel
        model.getBone("Steringwhell")?.let { bone ->
            bone.rotation.mul(Quaternionf().rotationZ(12 * Mth.lerp(partialTicks, vehicle.rudderRotO, vehicle.rudderRot)))
        }

        // Door animations: synced with OBB (interpolated progress)
        if (vehicle is KamazEntity) {
            val rearP = Mth.lerp(partialTicks, vehicle.rearDoorProgressO, vehicle.rearDoorProgress)
            val leftP = Mth.lerp(partialTicks, vehicle.leftDoorProgressO, vehicle.leftDoorProgress)
            val rightP = Mth.lerp(partialTicks, vehicle.rightDoorProgressO, vehicle.rightDoorProgress)

            model.getBone("bord5")?.let {
                it.rotation.rotateX(170f * Mth.DEG_TO_RAD * rearP)
            }
            model.getBone("door1")?.let {
                it.rotation.rotateY(-70f * Mth.DEG_TO_RAD * leftP)
            }
            model.getBone("door2")?.let {
                it.rotation.rotateY(70f * Mth.DEG_TO_RAD * rightP)
            }
        }
    }

    override fun renderDepthMask(
        entity: T,
        model: BedrockVehicleModel,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        texture: ResourceLocation,
        packedLight: Int,
        partialTick: Float
    ) {
        // No depth mask — head clipping handled via OBB raycast in HumanoidModelMixin
    }

    companion object {
        private val DEPTH_MASK_BONES = listOf("pehota2", "pehota3")
    }
}
