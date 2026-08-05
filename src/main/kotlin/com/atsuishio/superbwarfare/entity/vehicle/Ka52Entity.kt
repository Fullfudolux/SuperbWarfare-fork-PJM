package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleMotionUtils
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level

// PJM: Ка-52 «Аллигатор»
open class Ka52Entity(type: EntityType<Ka52Entity>, world: Level) : VehicleEntity(type, world) {

    /**
     * Шасси убирается/выпускается само по высоте над землёй.
     * Штатный механизм [com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo.Aircraft.hasGear]
     * доступен только самолётам и требует ручного нажатия, поэтому ведём цикл здесь.
     */
    override fun baseTick() {
        super.baseTick()

        if (!level().isClientSide) {
            val height = VehicleMotionUtils.getHeightAboveGround(this)
            if (isWreck || height < GEAR_DOWN_HEIGHT) {
                gearUp = false
            } else if (height > GEAR_UP_HEIGHT) {
                gearUp = true
            }
            synchedGearRot = if (gearUp) {
                Mth.clamp(synchedGearRot + GEAR_SPEED, 0f, 1f)
            } else {
                Mth.clamp(synchedGearRot - GEAR_SPEED, 0f, 1f)
            }
        }

        gearRot = synchedGearRot * GEAR_ANGLE
    }

    companion object {
        /** Гистерезис, чтобы шасси не «дёргалось» у самой земли */
        private const val GEAR_DOWN_HEIGHT = 4.0
        private const val GEAR_UP_HEIGHT = 8.0

        /** Доля цикла уборки за тик — полный ход ~1.7 с */
        private const val GEAR_SPEED = 0.03f

        /** Угол уборки стоек. Если стойки уезжают не в ту сторону — поменять знак. */
        private const val GEAR_ANGLE = 85f
    }
}
