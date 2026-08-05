package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.client.model.entity.BedrockVehicleModel
import com.atsuishio.superbwarfare.entity.vehicle.AjaxEntity
import com.atsuishio.superbwarfare.entity.vehicle.BasicGeoVehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.util.Mth

// PJM: Ajax. Кости модели названы по конвенции SBW (base/turret/barrel/wheel[LR]n/track[Mov|Rot][LR]n),
// поэтому вся трансформация делается базовым рендерером.
class AjaxRenderer<T>(manager: EntityRendererProvider.Context) :
    SbmVehicleRenderer<T>(manager) where T : VehicleEntity, T : BasicGeoVehicleEntity {

    override fun hideForTurretControllerWhileZooming() = true

    override fun getBoneRotX(t: Float) = sample(TRACK_ROT, t)

    override fun getBoneMoveY(t: Float) = sample(TRACK_Y, t)

    override fun getBoneMoveZ(t: Float) = sample(TRACK_Z, t)

    override fun getTrackDistance() = 5.1811f

    override fun transformCustomModelPart(
        vehicle: T,
        model: BedrockVehicleModel,
        poseStack: PoseStack,
        entityYaw: Float,
        partialTicks: Float
    ) {
        super.transformCustomModelPart(vehicle, model, poseStack, entityYaw, partialTicks)

        // маскировочная сеть видна, только когда её накинули; часть на башне — отдельной костью
        val net = vehicle is AjaxEntity && vehicle.hasCamoNet
        model.getBone("camoNet")?.visible = net
        model.getBone("camoNetTurret")?.visible = net
    }

    companion object {
        // Контур гусеницы, снятый с катков модели (ленивец/опорные/звёздочка):
        // t — длина дуги вдоль контура, Y/Z — смещение звена от rest-позиции (юниты модели),
        // ROT — угол звена в градусах. Сгенерировано pjm-tools/track_path_gen.py по ajax.geo.json.
        private val TRACK_T = floatArrayOf(
            0.000f, 1.172f, 2.347f, 3.522f, 4.698f, 5.873f, 7.048f, 8.223f, 9.398f, 10.574f,
            11.749f, 12.924f, 14.099f, 14.770f, 27.770f, 28.945f, 29.985f, 92.985f, 94.161f, 95.336f,
            96.511f, 97.130f, 107.824f, 108.661f, 109.497f, 110.333f, 111.169f, 112.005f, 112.842f, 113.678f,
            114.514f, 115.350f, 116.187f, 117.023f, 117.390f, 201.381f, 202.062f
        )
        private val TRACK_Y = floatArrayOf(
            0.000f, -0.222f, -0.710f, -1.410f, -2.290f, -3.313f, -4.435f, -5.604f, -6.772f, -7.886f,
            -8.898f, -9.764f, -10.446f, -10.740f, -15.740f, -16.076f, -16.173f, -16.173f, -16.050f, -15.687f,
            -15.099f, -14.708f, -7.509f, -6.885f, -6.159f, -5.362f, -4.530f, -3.699f, -2.905f, -2.183f,
            -1.564f, -1.077f, -0.741f, -0.571f, -0.552f, 0.070f, 0.000f
        )
        private val TRACK_Z = floatArrayOf(
            0.000f, 1.151f, 2.220f, 3.164f, 3.942f, 4.520f, 4.873f, 4.985f, 4.851f, 4.477f,
            3.880f, 3.086f, 2.128f, 1.526f, -10.474f, -11.600f, -12.636f, -75.636f, -76.805f, -77.923f,
            -78.940f, -79.420f, -87.329f, -87.885f, -88.299f, -88.553f, -88.636f, -88.544f, -88.282f, -87.860f,
            -87.297f, -86.618f, -85.852f, -85.033f, -84.666f, -0.678f, 0.000f
        )
        private val TRACK_ROT = floatArrayOf(
            -10.910f, -17.724f, -30.539f, -42.539f, -54.539f, -66.539f, -78.539f, -90.539f, -102.539f, -114.539f,
            -126.539f, -138.539f, -149.249f, -155.670f, -160.380f, -169.035f, -177.345f, -183.000f, -192.000f, -204.000f,
            -214.578f, -220.733f, -225.311f, -234.311f, -246.311f, -258.311f, -270.311f, -282.311f, -294.311f, -306.311f,
            -318.311f, -330.311f, -342.311f, -352.627f, -358.260f, -362.724f, -370.910f
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
