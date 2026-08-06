package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.OBB
import com.atsuishio.superbwarfare.tools.VectorTool
import com.mojang.math.Axis
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import java.util.function.Function

/**
 * Seats: 0 driver, 1 gunner, 2..9 dismounts.
 *
 * Crew board by right-clicking anywhere on the hull; the rear ramp is the only
 * way into the troop compartment, and it has to be dropped first.
 */
class Cv90Entity(type: EntityType<Cv90Entity>, world: Level) : VehicleEntity(type, world) {

    init {
        positionTransform[RAMP_TRANSFORM] = Function { partialTicks ->
            val p = Mth.lerp(partialTicks, rampProgressO, rampProgress)
            getVehicleTransform(partialTicks)
                .translate(RAMP_PIVOT.x, RAMP_PIVOT.y, RAMP_PIVOT.z)
                .rotate(Axis.XP.rotationDegrees(-RAMP_OPEN_ANGLE * p))
                .translate(-RAMP_PIVOT.x, -RAMP_PIVOT.y, -RAMP_PIVOT.z)
        }
        rotationTransform[RAMP_TRANSFORM] = Function { partialTicks ->
            val p = Mth.lerp(partialTicks, rampProgressO, rampProgress)
            val rot = VectorTool.combineRotations(partialTicks, this)
            if (p > 0.01f) rot.mul(Quaterniond(Axis.XP.rotationDegrees(-RAMP_OPEN_ANGLE * p)))
            rot
        }

        // `no_rotation` is parented to the ramp in the model but must stay put
        // when it drops. It gets its own transform purely so its boxes are
        // still identifiable as part of the ramp opening when boarding.
        positionTransform[RAMP_FRAME_TRANSFORM] = Function { partialTicks -> getVehicleTransform(partialTicks) }
        rotationTransform[RAMP_FRAME_TRANSFORM] = Function { partialTicks ->
            VectorTool.combineRotations(partialTicks, this)
        }
    }

    // ── Ramp state ────────────────────────────────────────────────────────────
    var rampOpen: Boolean
        get() = entityData.get(RAMP_OPEN)
        set(value) = entityData.set(RAMP_OPEN, value)

    /** Animation progress, with the previous tick kept for partialTick lerp. */
    var rampProgress = 0f
    var rampProgressO = 0f

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(RAMP_OPEN, false)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)
        compound.putBoolean("RampOpen", rampOpen)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        entityData.set(RAMP_OPEN, compound.getBoolean("RampOpen"))
    }

    override fun baseTick() {
        super.baseTick()
        rampProgressO = rampProgress
        rampProgress = Mth.lerp(0.07f, rampProgress, if (rampOpen) 1f else 0f)
    }

    // ── Пересадка ─────────────────────────────────────────────────────────────
    /**
     * Экипаж и десант — два изолированных отсека, между собой не сообщающиеся.
     * Механик-водитель может пересесть за пушку и обратно (места 0 и 1 — одна
     * боевая рубка), но не в десантное отделение; десант ходит только между
     * своими местами и за органы управления машиной не садится. Попасть в
     * «чужой» отсек можно лишь честно: выйти и зайти через нужную дверь — через
     * аппарель в десант, через люки экипажа в рубку (см. interactAt).
     *
     * Та же схема, что у [KamazEntity] с его кабиной и кузовом.
     */
    override fun changeSeat(entity: Entity, index: Int): Boolean {
        val currentSeat = getSeatIndex(entity)
        if (currentSeat >= 0 && isTroopSeat(currentSeat) != isTroopSeat(index)) return false
        return super.changeSeat(entity, index)
    }

    private fun isTroopSeat(index: Int): Boolean = index >= DISMOUNT_FIRST_SEAT

    // ── Boarding ──────────────────────────────────────────────────────────────
    override fun interactAt(player: Player, pVec: Vec3, hand: InteractionHand): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) return super.interactAt(player, pVec, hand)
        // Let shift-click fall through to interact() for the crowbar and menus.
        if (player.isShiftKeyDown) return InteractionResult.PASS
        if (this.passengers.contains(player)) return InteractionResult.PASS

        // Посадкой распоряжается только сервер — разбор в KamazEntity.interactAt.
        // Здесь на стороны расходится и попадание по аппарели (её OBB едет
        // вместе с анимацией rampProgress), и выбор свободного места.
        if (this.level().isClientSide) return InteractionResult.CONSUME

        if (isLookingAtRamp(player)) {
            // The ramp is raised and lowered by the driver on the ramp key, not
            // by clicking it; clicking only ever loads troops, and only once
            // it is actually down.
            if (!rampOpen) return InteractionResult.FAIL
            return board(player, DISMOUNT_FIRST_SEAT until maxPassengers)
        }

        // Anywhere else on the vehicle is a crew station.
        return board(player, 0 until DISMOUNT_FIRST_SEAT)
    }

    /** True when the player's crosshair is on the ramp itself or its surround. */
    private fun isLookingAtRamp(player: Player): Boolean {
        val hit = OBB.getLookingObb(player, 6.0) ?: return false
        val idx = this.getOBBs().indexOf(hit)
        if (idx < 0 || idx >= this.obb.size) return false
        val transform = this.obb[idx].transform
        return transform == RAMP_TRANSFORM || transform == RAMP_FRAME_TRANSFORM
    }

    /** Put the player in the first free seat of [range]. */
    private fun board(player: Player, range: IntRange): InteractionResult {
        val seat = range.firstOrNull { index -> this.passengers.none { getSeatIndex(it) == index } }
            ?: return InteractionResult.FAIL

        entityIndexOverride = Function { _ -> seat }
        val boarded = player.startRiding(this)
        entityIndexOverride = null

        return if (boarded) InteractionResult.CONSUME else InteractionResult.PASS
    }

    override fun interact(player: Player, hand: InteractionHand): InteractionResult {
        // Boarding goes through interactAt so it can tell the ramp from the hull;
        // shift-click still falls through to the base class for the crowbar,
        // inventory menu, name tag and key. Same split KamazEntity uses.
        if (!player.isShiftKeyDown) return InteractionResult.PASS
        return super.interact(player, hand)
    }

    companion object {
        const val RAMP_TRANSFORM = "Cv90DesantDoor"
        const val RAMP_FRAME_TRANSFORM = "Cv90DesantFrame"

        /** Seats 0..1 are crew, everything from here up is the troop compartment. */
        private const val DISMOUNT_FIRST_SEAT = 2

        private const val RAMP_OPEN_ANGLE = 85f

        /** `door_desant` pivot, model [7.975, 7.5, 54] converted to config space. */
        private val RAMP_PIVOT = Vec3(0.498438, 0.46875, -3.375)

        val RAMP_OPEN: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(Cv90Entity::class.java, EntityDataSerializers.BOOLEAN)
    }
}
