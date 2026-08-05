package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.ParticleTool
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.*

// PJM: M10 Booker
open class M10BookerEntity(type: EntityType<out M10BookerEntity>, world: Level) : VehicleEntity(type, world) {
    // длительность start_ext.ogg набора booker (1.75 c)
    override fun engineStartupDurationTicks() = 35

    override fun vehicleShoot(living: LivingEntity?, uuid: UUID?, targetPos: Vec3?) {
        val level = living?.level()
        if (level is ServerLevel && living == firstPassenger && getWeaponIndex(0) == 0) {
            ParticleTool.spawnBigCannonMuzzleParticles(getShootVec(living, 1f), getShootPos(living, 1f), level, this)
        }
        super.vehicleShoot(living, uuid, targetPos)
    }

    override fun getTurretMaxHealth() = 90f

    override fun getWheelMaxHealth() = 90f

    override fun getEngineMaxHealth() = 130f

    // длина контура гусеницы в юнитах модели (см. M10BookerRenderer.TRACK_T)
    override fun getTrackAnimationLength() = 223
}
