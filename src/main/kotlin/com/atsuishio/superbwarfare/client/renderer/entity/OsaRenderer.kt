package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.client.model.entity.BedrockVehicleModel
import com.atsuishio.superbwarfare.entity.vehicle.OsaEntity
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.util.Mth

// PJM: 9К33 «Оса-АКМ». Кости приведены к конвенции SBW, но модель собрана «носом по +X»
// и доворачивается через rotation root [0,90,0], поэтому локальные оси костей повёрнуты:
// ось вращения колеса — локальная Z, а не X, как ждёт базовый рендерер.
class OsaRenderer(manager: EntityRendererProvider.Context) : SbmVehicleRenderer<OsaEntity>(manager) {

    override fun hideForTurretControllerWhileZooming() = true

    override fun transformCustomModelPart(
        vehicle: OsaEntity,
        model: BedrockVehicleModel,
        poseStack: PoseStack,
        entityYaw: Float,
        partialTicks: Float
    ) {
        super.transformCustomModelPart(vehicle, model, poseStack, entityYaw, partialTicks)

        // перекрываем вращение колёс из super: у него ось X, у этой модели — Z.
        // Знак обратный: локальная Z смотрит влево, а локальная X у обычных моделей — вправо.
        val rudder = Mth.lerp(partialTicks, vehicle.rudderRotO, vehicle.rudderRot)
        model.leftWheels.forEach { it.rotation.rotationZ(-1.5f * leftWheelRot) }
        model.rightWheels.forEach { it.rotation.rotationZ(-1.5f * rightWheelRot) }
        model.leftWheelsTurn.forEach { it.rotation.rotationY(rudder).rotateZ(-1.5f * leftWheelRot) }
        model.rightWheelsTurn.forEach { it.rotation.rotationY(rudder).rotateZ(-1.5f * rightWheelRot) }

        model.getBone("radar")?.rotation?.rotationY(
            Mth.lerp(partialTicks, vehicle.radarRotO, vehicle.radarRot) * Mth.DEG_TO_RAD
        )
    }
}
