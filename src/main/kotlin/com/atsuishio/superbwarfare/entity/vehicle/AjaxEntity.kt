package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.entity.getValue
import com.atsuishio.superbwarfare.entity.setValue
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level

// PJM: Ajax
class AjaxEntity(type: EntityType<AjaxEntity>, world: Level) : VehicleEntity(type, world) {

    /**
     * Навесная маскировочная сеть (кости `camoNet` на корпусе и `camoNetTurret` на башне).
     * Ставится и снимается предметом [com.atsuishio.superbwarfare.item.misc.CamoNetItem].
     * Синхронизируется ради рендера.
     */
    var hasCamoNet by CAMO_NET

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(CAMO_NET, false)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)
        compound.putBoolean("CamoNet", hasCamoNet)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        hasCamoNet = compound.getBoolean("CamoNet")
    }

    // длительность start_ext.ogg набора ajax (1.75 c)
    override fun engineStartupDurationTicks() = 35

    override fun getTurretMaxHealth() = 80f

    override fun getWheelMaxHealth() = 80f

    override fun getEngineMaxHealth() = 110f

    // длина контура гусеницы в юнитах модели (см. AjaxRenderer.TRACK_T)
    override fun getTrackAnimationLength() = 202

    companion object {
        val CAMO_NET: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(AjaxEntity::class.java, EntityDataSerializers.BOOLEAN)
    }
}
