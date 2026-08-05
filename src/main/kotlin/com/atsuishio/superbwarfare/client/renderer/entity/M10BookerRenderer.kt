package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.client.model.entity.BedrockVehicleModel
import com.atsuishio.superbwarfare.entity.vehicle.BasicGeoVehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.M10BookerApsEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.util.Mth
import org.joml.Quaterniond
import org.joml.Quaternionf

// PJM: M10 Booker
//
// У модели кость root повёрнута на 180° по Y (так её собрал моделлер, геометрия не трогается).
// Из-за этого X/Z-вращения и X/Z-смещения, которые SbmVehicleRenderer задаёт в «мировых»
// терминах (катки, ствол, станция, отдача), визуально инвертируются — после super()
// переустанавливаем их с обратным знаком.
class M10BookerRenderer<T>(manager: EntityRendererProvider.Context) :
    SbmVehicleRenderer<T>(manager) where T : VehicleEntity, T : BasicGeoVehicleEntity {

    override fun hideForTurretControllerWhileZooming(): Boolean {
        return true
    }

    override fun rotateVehicleAxis(entityIn: T, poseStack: PoseStack, entityYaw: Float, partialTicks: Float) {
        super.rotateVehicleAxis(entityIn, poseStack, entityYaw, partialTicks)
        // низ гусеницы в модели лежит на y = -2 юнита — приподнимаем, чтобы не тонул в земле
        poseStack.translate(0f, 2f / 16f, 0f)
    }

    override fun getBoneRotX(t: Float) = sample(TRACK_ROT, t)

    override fun getBoneMoveY(t: Float) = sample(TRACK_Y, t)

    override fun getBoneMoveZ(t: Float) = sample(TRACK_Z, t)

    override fun getTrackDistance() = 2.4495f

    override fun transformCustomModelPart(
        vehicle: T,
        model: BedrockVehicleModel,
        poseStack: PoseStack,
        entityYaw: Float,
        partialTicks: Float
    ) {
        // super() пишет base.x/z абсолютно (качание от отдачи), затирая bind-смещение
        // кости. У этой модели пивот base не совпадает с пивотом root (dz = 8.55 юнита),
        // из-за чего корпус уезжал назад на 0.53 блока. Запоминаем bind до super().
        val base = model.getBone("base")
        val baseBindX = base?.x ?: 0f
        val baseBindZ = base?.z ?: 0f

        super.transformCustomModelPart(vehicle, model, poseStack, entityYaw, partialTicks)

        // блоки КАЗ есть только у версии с комплексом активной защиты
        model.getBone("APS")?.visible = vehicle is M10BookerApsEntity

        // каждая пусковая доворачивается независимо и остаётся в наведённом положении
        if (vehicle is M10BookerApsEntity) {
            model.getBone("APSR")?.rotation?.rotationY(vehicle.apsAimYawRight * Mth.DEG_TO_RAD)
            model.getBone("APSL")?.rotation?.rotationY(vehicle.apsAimYawLeft * Mth.DEG_TO_RAD)
        }

        // катки — крутим в обратную сторону
        model.leftWheels.forEach {
            it.rotation.rotationX(-1.5f * leftWheelRot)
        }
        model.rightWheels.forEach {
            it.rotation.rotationX(-1.5f * rightWheelRot)
        }

        // наклон ствола
        val barrel = model.getBone("barrel")
        if (barrel != null) {
            val rot = Mth.clamp(-turretXRot, vehicle.turretMinPitch, vehicle.turretMaxPitch) * Mth.DEG_TO_RAD
            barrel.rotation.rotationX(-rot)
        }

        // наклон пулемётной станции
        model.getBone("passengerWeaponStationPitch")?.rotation?.rotationX(
            -Mth.clamp(
                -Mth.lerp(
                    partialTicks,
                    vehicle.gunXRotO,
                    vehicle.gunXRot
                ) * Mth.DEG_TO_RAD,
                vehicle.passengerWeaponMinPitch * Mth.DEG_TO_RAD,
                vehicle.passengerWeaponMaxPitch * Mth.DEG_TO_RAD
            )
        )

        // качание корпуса от выстрела — поверх bind-смещения, с обратным знаком (root-180)
        if (base != null) {
            val a = vehicle.yawWhileShoot
            val r = (Mth.abs(a) - 90f) / 90f

            val r2 = if (Mth.abs(a) <= 90f) {
                a / 90f
            } else {
                if (a < 0) {
                    -(180f + a) / 90f
                } else {
                    (180f - a) / 90f
                }
            }

            base.x = baseBindX + r2 * recoilShake * 0.5f
            base.z = baseBindZ - r * recoilShake

            val pitch = Axis.XP.rotationDegrees(-r * recoilShake)
            val roll = Axis.ZP.rotationDegrees(-r2 * recoilShake)
            base.rotation.set(Quaternionf(Quaterniond(pitch).mul(Quaterniond(roll))))
        }
    }

    companion object {
        // Контур гусеницы, снятый с катков модели (звёздочка/опорные/ленивец/ролики):
        // t — длина дуги вдоль контура, Y/Z — смещение звена от rest-позиции (юниты модели),
        // ROT — угол звена в градусах. Сгенерировано по m_10_booker.geo.json.
        private val TRACK_T = floatArrayOf(
            0.000f, 0.994f, 1.993f, 2.992f, 3.992f, 4.991f, 5.990f, 6.990f, 7.989f, 8.988f,
            9.987f, 10.987f, 11.986f, 12.225f, 25.264f, 26.796f, 28.329f, 29.826f, 97.826f, 99.358f,
            100.890f, 101.812f, 116.267f, 117.336f, 118.404f, 119.472f, 120.540f, 121.609f, 122.677f, 123.745f,
            124.814f, 125.882f, 126.950f, 128.018f, 129.087f, 129.412f, 144.840f, 171.892f, 198.892f, 222.908f
        )
        private val TRACK_Y = floatArrayOf(
            0.000f, 0.046f, -0.219f, -0.679f, -1.314f, -2.095f, -2.989f, -3.956f, -4.954f, -5.940f,
            -6.870f, -7.704f, -8.406f, -8.550f, -16.162f, -16.922f, -17.389f, -17.541f, -17.541f, -17.381f,
            -16.908f, -16.480f, -8.990f, -8.344f, -7.535f, -6.599f, -5.576f, -4.511f, -3.452f, -2.443f,
            -1.529f, -0.751f, -0.141f, 0.273f, 0.473f, 0.489f, 0.798f, 0.799f, 0.799f, 0.000f
        )
        private val TRACK_Z = floatArrayOf(
            0.000f, -0.993f, -1.956f, -2.843f, -3.615f, -4.238f, -4.686f, -4.937f, -4.982f, -4.819f,
            -4.454f, -3.904f, -3.192f, -3.001f, 7.585f, 8.916f, 10.375f, 11.864f, 79.864f, 81.388f,
            82.846f, 83.663f, 96.025f, 96.876f, 97.574f, 98.089f, 98.397f, 98.486f, 98.352f, 98.001f,
            97.447f, 96.716f, 95.838f, 94.853f, 93.804f, 93.480f, 78.054f, 51.002f, 24.002f, 0.000f
        )
        private val TRACK_ROT = floatArrayOf(
            -2.681f, 6.368f, 21.416f, 33.416f, 45.416f, 57.416f, 69.416f, 81.416f, 93.416f, 105.416f,
            117.416f, 129.416f, 139.132f, 143.564f, 147.280f, 156.280f, 168.210f, 177.070f, 183.000f, 192.000f,
            202.803f, 209.408f, 214.211f, 223.211f, 235.211f, 247.211f, 259.211f, 271.211f, 283.211f, 295.211f,
            307.211f, 319.211f, 331.211f, 343.211f, 353.122f, 357.943f, 359.426f, 359.999f, 360.953f, 357.319f
        )

        private fun sample(values: FloatArray, t: Float): Float {
            if (t <= TRACK_T.first()) return values.first()
            for (i in 1 until TRACK_T.size) {
                if (t <= TRACK_T[i]) {
                    val f = (t - TRACK_T[i - 1]) / (TRACK_T[i] - TRACK_T[i - 1])
                    return Mth.lerp(f, values[i - 1], values[i])
                }
            }
            return values.last()
        }
    }
}
