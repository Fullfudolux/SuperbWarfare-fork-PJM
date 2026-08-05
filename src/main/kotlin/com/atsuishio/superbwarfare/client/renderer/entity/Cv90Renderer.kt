package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.client.model.entity.BedrockVehicleModel
import com.atsuishio.superbwarfare.entity.vehicle.BasicGeoVehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.Cv90Entity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.util.Mth

/**
 * CV-90 track contour.
 *
 * Same scheme as [BradleyRenderer]/[M1A2Renderer]: every link sits at the same
 * rest pose in the model and gets pushed around a closed belt by t, which runs
 * 0..100 over one lap. The belt was derived from the model's rolling gear
 * (7 road wheels at y=6.5 r=5.5, front sprocket at z=-55, rear idler at z=50),
 * so t=0 is the rear end of the bottom run and t grows towards the nose at -Z.
 *
 *  - t 0..35    bottom run, flat, travelling forward
 *  - t 35..45   around the front sprocket
 *  - t 45..89   top run, travelling back, links upside down
 *  - t 89..100  around the rear idler, back to the start
 */
class Cv90Renderer<T>(manager: EntityRendererProvider.Context) :
    SbmVehicleRenderer<T>(manager) where T : VehicleEntity, T : BasicGeoVehicleEntity {

    override fun hideForTurretControllerWhileZooming(): Boolean {
        return true
    }

    override fun transformCustomModelPart(
        vehicle: T,
        model: BedrockVehicleModel,
        poseStack: PoseStack,
        entityYaw: Float,
        partialTicks: Float
    ) {
        super.transformCustomModelPart(vehicle, model, poseStack, entityYaw, partialTicks)

        // Rear ramp. The sign is opposite the one the OBB transform uses, the
        // same way Kamaz's rear door is +170 here and -170 on its hitbox.
        if (vehicle is Cv90Entity) {
            val p = Mth.lerp(partialTicks, vehicle.rampProgressO, vehicle.rampProgress)
            val angle = RAMP_OPEN_ANGLE * Mth.DEG_TO_RAD * p
            model.getBone("door_desant")?.rotation?.rotateX(angle)

            // `no_rotation` is a child of the ramp in the model but has to stay
            // welded to the hull. Cancelling the parent's rotation alone is not
            // enough -- the bone is also carried around the ramp pivot -- so
            // offset it back by however far that swing moved it.
            model.getBone("no_rotation")?.let { bone ->
                bone.rotation.rotateX(-angle)
                val cos = Mth.cos(-angle)
                val sin = Mth.sin(-angle)
                bone.y += FRAME_OFFSET_Y * cos - FRAME_OFFSET_Z * sin - FRAME_OFFSET_Y
                bone.z += FRAME_OFFSET_Y * sin + FRAME_OFFSET_Z * cos - FRAME_OFFSET_Z
            }
        }
    }

    override fun getBoneRotX(t: Float): Float {
        if (t <= 34.375F) return 0F
        if (t <= 35F) return Mth.lerp((t - 34.375F) / 0.625F, 0F, -7.042F)
        if (t <= 36.25F) return Mth.lerp((t - 35F) / 1.25F, -7.042F, -35.941F)
        if (t <= 40.625F) return Mth.lerp((t - 36.25F) / 4.375F, -35.941F, -36.696F)
        if (t <= 41.25F) return Mth.lerp((t - 40.625F) / 0.625F, -36.696F, -45.78F)
        if (t <= 45.625F) return Mth.lerp((t - 41.25F) / 4.375F, -45.78F, -180.644F)
        if (t <= 88.75F) return -180.85F
        if (t <= 89.375F) return Mth.lerp((t - 88.75F) / 0.625F, -180.85F, -192.32F)
        if (t <= 93.75F) return Mth.lerp((t - 89.375F) / 4.375F, -192.32F, -327.183F)
        if (t <= 98.75F) return Mth.lerp((t - 93.75F) / 5F, -327.183F, -331.101F)

        return Mth.lerp((t - 98.75F) / 1.25F, -331.101F, -360F)
    }

    override fun getBoneMoveY(t: Float): Float {
        if (t <= 35F) return 0F
        if (t <= 35.625F) return Mth.lerp((t - 35F) / 0.625F, 0F, 0.406F)
        if (t <= 36.25F) return Mth.lerp((t - 35.625F) / 0.625F, 0.406F, 1.124F)
        if (t <= 41.25F) return Mth.lerp((t - 36.25F) / 5F, 1.124F, 8.345F)
        if (t <= 41.875F) return Mth.lerp((t - 41.25F) / 0.625F, 8.345F, 9.58F)
        if (t <= 43.75F) return Mth.lerp((t - 41.875F) / 1.875F, 9.58F, 13.921F)
        if (t <= 44.375F) return Mth.lerp((t - 43.75F) / 0.625F, 13.921F, 15.037F)
        if (t <= 45F) return Mth.lerp((t - 44.375F) / 0.625F, 15.037F, 15.757F)
        if (t <= 45.625F) return Mth.lerp((t - 45F) / 0.625F, 15.757F, 16F)
        if (t <= 88.75F) return Mth.lerp((t - 45.625F) / 43.125F, 16F, 15.006F)
        if (t <= 89.375F) return Mth.lerp((t - 88.75F) / 0.625F, 15.006F, 14.901F)
        if (t <= 90F) return Mth.lerp((t - 89.375F) / 0.625F, 14.901F, 14.346F)
        if (t <= 90.625F) return Mth.lerp((t - 90F) / 0.625F, 14.346F, 13.359F)
        if (t <= 91.25F) return Mth.lerp((t - 90.625F) / 0.625F, 13.359F, 12.052F)
        if (t <= 92.5F) return Mth.lerp((t - 91.25F) / 1.25F, 12.052F, 9.083F)
        if (t <= 93.125F) return Mth.lerp((t - 92.5F) / 0.625F, 9.083F, 7.753F)
        if (t <= 93.75F) return Mth.lerp((t - 93.125F) / 0.625F, 7.753F, 6.731F)
        if (t <= 98.75F) return Mth.lerp((t - 93.75F) / 5F, 6.731F, 0.762F)
        if (t <= 99.375F) return Mth.lerp((t - 98.75F) / 0.625F, 0.762F, 0.198F)

        return Mth.lerp((t - 99.375F) / 0.625F, 0.198F, 0F)
    }

    override fun getBoneMoveZ(t: Float): Float {
        if (t <= 35.625F) return Mth.lerp(t / 35.625F, 0F, -86.2F)
        if (t <= 36.875F) return Mth.lerp((t - 35.625F) / 1.25F, -86.2F, -88.746F)
        if (t <= 41.25F) return Mth.lerp((t - 36.875F) / 4.375F, -88.746F, -97.24F)
        if (t <= 41.875F) return Mth.lerp((t - 41.25F) / 0.625F, -97.24F, -98.101F)
        if (t <= 42.5F) return Mth.lerp((t - 41.875F) / 0.625F, -98.101F, -98.507F)
        if (t <= 43.125F) return Mth.lerp((t - 42.5F) / 0.625F, -98.507F, -98.411F)
        if (t <= 43.75F) return Mth.lerp((t - 43.125F) / 0.625F, -98.411F, -97.825F)
        if (t <= 44.375F) return Mth.lerp((t - 43.75F) / 0.625F, -97.825F, -96.814F)
        if (t <= 45F) return Mth.lerp((t - 44.375F) / 0.625F, -96.814F, -95.491F)
        if (t <= 89.375F) return Mth.lerp((t - 45F) / 44.375F, -95.491F, 11.906F)
        if (t <= 90F) return Mth.lerp((t - 89.375F) / 0.625F, 11.906F, 13.305F)
        if (t <= 90.625F) return Mth.lerp((t - 90F) / 0.625F, 13.305F, 14.443F)
        if (t <= 91.25F) return Mth.lerp((t - 90.625F) / 0.625F, 14.443F, 15.192F)
        if (t <= 91.875F) return Mth.lerp((t - 91.25F) / 0.625F, 15.192F, 15.468F)
        if (t <= 92.5F) return Mth.lerp((t - 91.875F) / 0.625F, 15.468F, 15.24F)
        if (t <= 93.125F) return Mth.lerp((t - 92.5F) / 0.625F, 15.24F, 14.533F)
        if (t <= 93.75F) return Mth.lerp((t - 93.125F) / 0.625F, 14.533F, 13.427F)
        if (t <= 98.75F) return Mth.lerp((t - 93.75F) / 5F, 13.427F, 2.896F)

        return Mth.lerp((t - 98.75F) / 1.25F, 2.896F, 0F)
    }

    /** 44 links per side, evenly spread over the 0..100 lap. */
    override fun getTrackDistance(): Float {
        return 100F / 44F
    }

    companion object {
        private const val RAMP_OPEN_ANGLE = 85F

        // `no_rotation` pivot [0.5, 33, 59] relative to the ramp pivot
        // [7.975, 7.5, 54] -- the lever arm its counter-rotation swings around.
        private const val FRAME_OFFSET_Y = 25.5F
        private const val FRAME_OFFSET_Z = 5F
    }
}
