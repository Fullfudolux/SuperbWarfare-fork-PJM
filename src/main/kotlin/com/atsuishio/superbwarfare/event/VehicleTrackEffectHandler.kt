package com.atsuishio.superbwarfare.event

import com.atsuishio.superbwarfare.client.particle.CustomCloudOption
import com.atsuishio.superbwarfare.client.particle.CustomFlareOption
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.receive.ShakeClientMessage
import com.atsuishio.superbwarfare.tools.ParticleTool
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.tick.EntityTickEvent

/**
 * PJM: пыль из-под гусениц и лёгкая тряска при резком старте/торможении гусеничной техники.
 * Считаем горизонтальное ускорение за тик (deltaMovement - deltaMovementO) на сервере и, если
 * рывок выше порога, рассыпаем пыль по линии гусениц и шлём короткую тряску стоящим рядом.
 */
@EventBusSubscriber
object VehicleTrackEffectHandler {

    // ponytail: пороги подобраны на глаз, откалибровать по игре. ACCEL — «резкость» рывка за тик,
    // SPEED — минимальная скорость контекста, чтобы микродрожь стоящего танка не пылила.
    private const val ACCEL_THRESHOLD = 0.02
    private const val SPEED_THRESHOLD = 0.05

    @SubscribeEvent
    fun onEntityTick(event: EntityTickEvent.Post) {
        val vehicle = event.entity as? VehicleEntity ?: return
        val level = vehicle.level() as? ServerLevel ?: return
        if (vehicle.isWreck || !vehicle.onGround()) return
        when (vehicle.vehicleType) {
            VehicleType.TANK, VehicleType.APC, VehicleType.AA -> {}
            else -> return
        }
        // не молотим эффектами каждый тик — раз в 3
        if (vehicle.tickCount % 3 != 0) return

        val dv = vehicle.deltaMovement
        val dvo = vehicle.deltaMovementO
        val accelH = Math.hypot(dv.x - dvo.x, dv.z - dvo.z)
        val speedH = maxOf(Math.hypot(dv.x, dv.z), Math.hypot(dvo.x, dvo.z))
        if (accelH < ACCEL_THRESHOLD || speedH < SPEED_THRESHOLD) return

        val strength = ((accelH - ACCEL_THRESHOLD) / ACCEL_THRESHOLD).coerceIn(0.0, 2.0)
        val right = vehicle.getRightVec(1f)
        // пыль сдувается назад по ходу; если скорость почти нулевая (старт) — вдоль корпуса
        val fwd = if (speedH > 1e-4) Vec3(dv.x, 0.0, dv.z).normalize() else Vec3(-right.z, 0.0, right.x)
        val back = fwd.scale(-1.0)
        val gy = vehicle.y + 0.1
        val half = 0.42 * vehicle.bbWidth

        for (side in intArrayOf(-1, 1)) {
            for (along in doubleArrayOf(-0.6, 0.0, 0.6)) {
                val px = vehicle.x + right.x * side * half + fwd.x * along * vehicle.bbWidth
                val pz = vehicle.z + right.z * side * half + fwd.z * along * vehicle.bbWidth
                ParticleTool.sendParticle(
                    level,
                    CustomFlareOption(
                        0.667f, 0.631f, 0.592f,
                        24, 0.9f,
                        (6 + 10 * Math.random()).toInt(), 0.015f,
                        size = 0.4f + 0.5f * Math.random().toFloat()
                    ),
                    px, gy, pz,
                    1 + strength.toInt(),
                    0.25, 0.05, 0.25, 0.02, true
                )
                // низкая волна пыли, уносимая назад
                ParticleTool.sendParticle(
                    level,
                    CustomCloudOption(0xA89E86, 18, 2f, 0f, cooldown = false, light = false),
                    px, gy, pz,
                    // ponytail: CustomCloudParticle гасит скорость в 0.833/тик, т.е. путь ≈ v0 * 6.
                    // 0.25..0.75 → волна отходит назад на 1.5-4.5 блока и оседает.
                    0, back.x, 0.1, back.z, 0.25 + 0.25 * strength, true
                )
            }
        }

        // короткая тряска только у тех, кто на танке/вплотную к нему
        ShakeClientMessage.sendToNearbyPlayers(
            level, vehicle.x, vehicle.y, vehicle.z,
            12.0, 2.0 + 2.0 * strength, 0.7 + 0.7 * strength
        )
    }
}
