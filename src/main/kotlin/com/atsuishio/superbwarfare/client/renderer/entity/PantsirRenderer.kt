package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.client.model.entity.BedrockVehicleModel
import com.atsuishio.superbwarfare.entity.vehicle.BasicGeoVehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.util.Mth
import org.joml.Quaternionf
import org.joml.Vector3f

class PantsirRenderer<T>(manager: EntityRendererProvider.Context) :
    SbmVehicleRenderer<T>(manager) where T : VehicleEntity, T : BasicGeoVehicleEntity {

    override fun transformCustomModelPart(
        vehicle: T,
        model: BedrockVehicleModel,
        poseStack: PoseStack,
        entityYaw: Float,
        partialTicks: Float
    ) {
        super.transformCustomModelPart(vehicle, model, poseStack, entityYaw, partialTicks)

        // Fix right wheels
        model.rightWheels.forEach { it.rotation.rotateX(-3.0f * vehicle.rightWheelRot) }
        model.rightWheelsTurn.forEach { it.rotation.rotateX(-3.0f * vehicle.rightWheelRot) }

        // Steering wheel
        model.getBone("Steringwhell")?.let { bone ->
            bone.rotation.mul(Quaternionf().rotationZ(12 * Mth.lerp(partialTicks, vehicle.rudderRotO, vehicle.rudderRot)))
        }

        // Pantsir animation
        if (vehicle is com.atsuishio.superbwarfare.entity.vehicle.PantsirEntity) {
            val jp = Mth.lerp(partialTicks, vehicle.jacksProgressO, vehicle.jacksProgress)
            val lp = Mth.lerp(partialTicks, vehicle.leftDoorProgressO, vehicle.leftDoorProgress)
            val rp = Mth.lerp(partialTicks, vehicle.rightDoorProgressO, vehicle.rightDoorProgress)
            val ldp = Mth.lerp(partialTicks, vehicle.ladderProgressO, vehicle.ladderProgress)

            // Jacks: legs extend fully by jp=0.5, body lift tracks jp directly
            // (matches PantsirEntity.bodyLift() so hitboxes rise the same way) —
            // rising in step with the legs instead of only starting once they're
            // already fully extended, which left them looking like they were
            // digging into the ground for the first half of the animation.
            val jackExtend = (jp * 2f).coerceAtMost(1f)
            val bodyLift = jp
            if (bodyLift > 0.01f) poseStack.translate(0f, bodyLift * com.atsuishio.superbwarfare.entity.vehicle.PantsirEntity.BODY_LIFT_BLOCKS, 0f)
            model.getBone("OPORY")?.let { it.y = it.y + (-(jackExtend * 0.667f) * 16f) }

            // Door animations (smooth)
            model.getBone("door1")?.let { it.rotation.rotateY(-70f * Mth.DEG_TO_RAD * lp) }
            model.getBone("door2")?.let { it.rotation.rotateY(70f * Mth.DEG_TO_RAD * rp) }

            // Missile rail wind-up: extra pitch on top of whatever the
            // "barrel" bone (turretXRot, handled by the base renderer)
            // already applied — only the rail lifts, the gun/reticle don't
            // move. Opposite sign from missileTransform()'s position-space
            // chain, same asymmetry as the "barrel" bone rotation above and
            // the ladder/radar bones (position-space Matrix4d chains vs.
            // runtime Quaternionf bone mutations need opposite-signed
            // angles in this pipeline).
            val mep = Mth.lerp(partialTicks, vehicle.missileExtraPitchO, vehicle.missileExtraPitch)
            if (kotlin.math.abs(mep) > 0.01f) {
                val rocketBone = if (vehicle.missileFireSide) "RocketL" else "RocketR"
                model.getBone(rocketBone)?.let { it.rotation.rotateX(-mep * Mth.DEG_TO_RAD) }
            }

            // Ladder: folds out AWAY from the hull around its own local Z
            // axis. -205° and +155° land on the exact same final angle
            // (-205 == 155 mod 360), but -205 gets there the "long way"
            // through the far side, clipping through the hull mid-swing;
            // +155 takes the short way straight out to the door instead.
            model.getBone("lader")?.let { it.rotation.rotateZ(155f * Mth.DEG_TO_RAD * ldp) }

            // RADAR: fold from -70° (authored rest) further to -140°, then spin
            model.getBone("RADAR")?.let { bone ->
                // Raise/lower uses the bone's own authored pivot — unchanged.
                bone.rotation.rotateX(-jp * 70f * Mth.DEG_TO_RAD)
                // Spin only after fully deployed (jp > 0.95)
                if (jp > 0.95f) {
                    // The dish's own rotation center sits away from the RADAR
                    // bone's pivot (model pivot [-0.24521,73.66362,89.54102] vs
                    // bone pivot [-0.33462,63.01671,83.95267], raw units), so
                    // spinning the bone in place orbits it instead of spinning
                    // it around its own axis. Compensate with a matching
                    // position offset so the spin happens around the dish's
                    // real center while the raise/lower pivot stays untouched.
                    val offset = Vector3f(0.08941f, 10.64691f, 5.58835f).mul(1f / 16f)
                    val spin = Quaternionf().rotationY(-vehicle.radarSpin * Mth.DEG_TO_RAD)
                    val spunOffset = Vector3f(offset)
                    spin.transform(spunOffset)
                    val posDelta = Vector3f(offset).sub(spunOffset)
                    bone.rotation.transform(posDelta)
                    bone.x += posDelta.x * 16f
                    bone.y += posDelta.y * 16f
                    bone.z += posDelta.z * 16f
                    bone.rotation.mul(spin)
                }
            }
        }
    }
}
