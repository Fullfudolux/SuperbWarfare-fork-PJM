package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.client.model.entity.BedrockVehicleModel
import com.atsuishio.superbwarfare.entity.vehicle.BasicGeoVehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.util.Mth

// PJM: БМПТ-72 «Терминатор». Модель нормализована под конвенцию SBW скриптом
// pjm-tools/terminator_model_fixup.py (катки wheel[LR]n, звенья гусеницы trackMov[LR]n/
// trackRot[LR]n) — поэтому катки и гусеницу двигает базовый рендерер, а здесь остаётся только
// контур гусеницы (запечён pjm-tools/track_path_gen.py по terminator.geo.json) и наклон
// спаренных пушек (люлька `turrets`, штатной кости `barrel` в модели нет).
// root без разворота (rotation отсутствует) — знаки как у Ajax, без инверсии.
class TerminatorRenderer<T>(manager: EntityRendererProvider.Context) :
    SbmVehicleRenderer<T>(manager) where T : VehicleEntity, T : BasicGeoVehicleEntity {

    override fun hideForTurretControllerWhileZooming() = true

    // перёд = -Z (дуло `turrets` смотрит в -Z), root без разворота → конвенция Ajax, без инверсий
    override fun getBoneRotX(t: Float) = sample(TRACK_ROT, t)

    override fun getBoneMoveY(t: Float) = sample(TRACK_Y, t)

    override fun getBoneMoveZ(t: Float) = sample(TRACK_Z, t)

    override fun getTrackDistance() = 5.1594f

    override fun transformCustomModelPart(
        vehicle: T,
        model: BedrockVehicleModel,
        poseStack: PoseStack,
        entityYaw: Float,
        partialTicks: Float
    ) {
        super.transformCustomModelPart(vehicle, model, poseStack, entityYaw, partialTicks)

        // наклон спаренных пушек (люлька `turrets`, штатной кости `barrel` в модели нет) —
        // как base для barrel, без инверсии
        model.getBone("turrets")?.rotation?.rotationX(
            Mth.clamp(-turretXRot, vehicle.turretMinPitch, vehicle.turretMaxPitch) * Mth.DEG_TO_RAD
        )

        // PJM: ПТУР пропадает с направляющей после пуска. BoundUpWithAmmoAmount пускает ракету
        // с индекса (ammo-1), поэтому ракета raketa<i> видна, пока боезапас больше её индекса.
        val ammo = vehicle.getGunData(MISSILE_WEAPON)?.ammo?.get() ?: 0
        for (i in 0..3) model.getBone("raketa$i")?.visible = ammo > i
    }

    companion object {
        private const val MISSILE_WEAPON = "Missile"

        // Контур гусеницы, снятый с катков модели (ленивец/опорные/ролики/звёздочка):
        // t — длина дуги вдоль контура, Y/Z — смещение звена от rest-позиции (юниты модели),
        // ROT — угол звена в градусах. Сгенерировано pjm-tools/track_path_gen.py по terminator.geo.json.
        private val TRACK_T = floatArrayOf(
            0.000f, 1.171f, 2.341f, 3.512f, 4.683f, 5.854f, 7.024f, 8.195f, 9.366f, 10.536f,
            11.707f, 12.878f, 14.049f, 14.884f, 27.456f, 29.009f, 30.562f, 31.009f, 96.009f, 97.562f,
            99.115f, 100.668f, 100.883f, 112.841f, 113.698f, 114.555f, 115.413f, 116.270f, 117.127f, 117.984f,
            118.841f, 119.698f, 120.555f, 121.412f, 122.270f, 123.008f, 140.508f, 166.508f, 192.508f, 211.535f
        )
        private val TRACK_Y = floatArrayOf(
            0.000f, -0.122f, -0.484f, -1.070f, -1.853f, -2.800f, -3.870f, -5.015f, -6.185f, -7.330f,
            -8.400f, -9.347f, -10.130f, -10.570f, -16.364f, -16.932f, -17.187f, -17.200f, -17.200f, -17.038f,
            -16.558f, -15.781f, -15.652f, -8.346f, -7.754f, -7.046f, -6.253f, -5.410f, -4.553f, -3.720f,
            -2.948f, -2.269f, -1.715f, -1.308f, -1.067f, -1.000f, -1.000f, -1.000f, -1.000f, 0.000f
        )
        private val TRACK_Z = floatArrayOf(
            0.000f, 1.164f, 2.278f, 3.292f, 4.162f, 4.850f, 5.326f, 5.569f, 5.569f, 5.326f,
            4.850f, 4.162f, 3.292f, 2.581f, -8.576f, -10.022f, -11.554f, -12.000f, -77.000f, -78.544f,
            -80.021f, -81.366f, -81.539f, -91.005f, -91.625f, -92.109f, -92.434f, -92.588f, -92.563f, -92.361f,
            -91.990f, -91.466f, -90.813f, -90.058f, -89.236f, -88.500f, -71.000f, -45.000f, -19.000f, 0.000f
        )
        private val TRACK_ROT = floatArrayOf(
            -6.000f, -12.000f, -24.000f, -36.000f, -48.000f, -60.000f, -72.000f, -84.000f, -96.000f, -108.000f,
            -120.000f, -132.000f, -143.139f, -150.417f, -155.556f, -164.556f, -174.417f, -179.139f, -183.000f, -192.000f,
            -204.000f, -213.416f, -217.247f, -220.662f, -229.662f, -241.662f, -253.662f, -265.662f, -277.662f, -289.662f,
            -301.662f, -313.662f, -325.662f, -337.662f, -349.247f, -357.416f, -360.000f, -360.000f, -358.494f, -366.000f
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
