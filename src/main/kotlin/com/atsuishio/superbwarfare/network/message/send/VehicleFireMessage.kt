package com.atsuishio.superbwarfare.network.message.send

import com.atsuishio.superbwarfare.entity.vehicle.PantsirEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.network.ServerPacketPayload
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedUUID
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVector3f
import kotlinx.serialization.Serializable
import net.minecraft.world.phys.Vec3

@Serializable
data class VehicleFireMessage(
    val uuid: SerializedUUID?,
    val targetPos: SerializedVector3f?,
) : ServerPacketPayload() {
    override fun PayloadContext.handler() {
        val player = sender()
        val vehicle = player.vehicle as? VehicleEntity ?: return

        // Пуск по захваченной цели — сам по себе подтверждение захвата. Раньше
        // серверный trackedTargetUUID выставлялся ИСКЛЮЧИТЕЛЬНО периодическим
        // heartbeat'ом (SeekingWeaponWarningMessage раз в два тика), и если к
        // моменту схода ракеты он не дошёл или запоздал, командная радиолиния
        // 57Э6 не сходилась: Ru57e6MissileEntity.hasCommandLink сравнивает
        // цель ракеты именно с trackedTargetUUID, четыре тика без совпадения —
        // и ракета навсегда считает сигнал потерянным и доводится вручную по
        // стволу, то есть летит как ПТУР. Пакет пуска приходит по тому же
        // каналу и с тем же UUID цели, так что этого рассогласования быть
        // просто не должно.
        if (uuid != null && vehicle is PantsirEntity && vehicle.getSeatIndex(player) == 2) {
            vehicle.refreshTrackedTarget(uuid.toString())
        }

        if (targetPos != null) {
            vehicle.vehicleShoot(player, uuid, Vec3(targetPos))
        } else {
            vehicle.vehicleShoot(player, uuid, null)
        }
    }
}
