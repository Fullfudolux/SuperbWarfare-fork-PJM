package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.client.model.entity.BedrockVehicleModel
import com.atsuishio.superbwarfare.entity.vehicle.BasicGeoVehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.Ka52Entity
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.util.Mth

// PJM: Ка-52 — соосная схема, два винта вращаются навстречу друг другу, рулевого винта нет
class Ka52Renderer<T>(manager: EntityRendererProvider.Context) :
    SbmVehicleRenderer<T>(manager) where T : Ka52Entity, T : BasicGeoVehicleEntity {
    override fun transformCustomModelPart(
        vehicle: T,
        model: BedrockVehicleModel,
        poseStack: PoseStack,
        entityYaw: Float,
        partialTicks: Float
    ) {
        super.transformCustomModelPart(vehicle, model, poseStack, entityYaw, partialTicks)

        val rot = Mth.lerp(partialTicks, vehicle.propellerRotO, vehicle.propellerRot)
        model.getBone("rotor_up")?.rotation?.rotateY(-rot)
        model.getBone("rotor_down")?.rotation?.rotateY(rot)

        // Пушка НППУ-28 и прицельный контейнер (ГОЭС) ходят за наводчиком. Кости называются
        // не turret/barrel, поэтому базовый рендер их не трогает — крутим сами по тем же
        // turretYRot/turretXRot, что базовый класс уже посчитал для башни.
        val yaw = turretYRot * Mth.DEG_TO_RAD
        val pitch = -turretXRot * Mth.DEG_TO_RAD

        model.getBone("autocannon")?.rotation?.rotateY(yaw)?.rotateX(pitch)
        // Шар ГОЭС водим только по рысканью — по тангажу он выглядит вывернутым
        model.getBone("pricel")?.rotation?.rotateY(yaw)

        // Шасси
        val gear = vehicle.gearRot(partialTicks) * Mth.DEG_TO_RAD
        if (gear != 0f) {
            model.getBone("land_L")?.rotation?.rotateX(gear)
            model.getBone("land_R")?.rotation?.rotateX(gear)
            model.getBone("land_F")?.rotation?.rotateX(gear)
        }
    }
}
