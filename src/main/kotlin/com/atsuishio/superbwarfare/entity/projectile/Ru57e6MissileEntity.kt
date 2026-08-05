package com.atsuishio.superbwarfare.entity.projectile

import com.atsuishio.superbwarfare.entity.vehicle.PantsirEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.tools.EntityFindUtil
import com.atsuishio.superbwarfare.tools.RangeTool.calculateFiringSolution
import com.atsuishio.superbwarfare.tools.VectorTool.calculateAngle
import com.atsuishio.superbwarfare.tools.angleTo
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.animal.Pig
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.max

/**
 * 57Э6 — Pantsir-S1's own SAM round. A copy of [Ru9m336MissileEntity] with one
 * fundamental difference: real command-guided missiles (this one included)
 * are NOT fire-and-forget. The real 9К330/57Э6 is guided by a radio uplink —
 * the Pantsir's fire-control radar keeps the target painted while a SEPARATE
 * channel tracks the missile itself (via a tail beacon) and radios steering
 * corrections the whole way to impact. That's the "2 radars on the front of
 * the turret" the target-tracking channel and the missile-tracking channel.
 * Break either one and the missile goes dumb.
 *
 * So unlike every other homing missile in this mod, guidance here requires
 * the SAME Pantsir that fired it to still have that SAME target under a
 * genuine, active lock ([PantsirEntity.trackedTargetUUID]) for the whole
 * flight — not just at the moment of firing. Crucially, this is checked
 * against the Pantsir's real, persistent lock state, NOT by re-measuring
 * the angle to the target from wherever the turret/gunner's aim happens to
 * be pointing this exact tick — a lock that's already been lost doesn't
 * silently resume just because the turret happens to swing back across
 * the target's bearing later.
 *
 * Once that radar lock genuinely drops, though, it's not just dead weight:
 * as long as the SAME gunner is still in the SAME Pantsir, it falls back to
 * manual ATGM-style guidance — steered every tick toward wherever the
 * barrel currently points, exactly like [WireGuideMissileEntity]. Only once
 * nobody's left to fly it (gunner dismounts, vehicle destroyed) does it
 * finally nose over and fall.
 */
open class Ru57e6MissileEntity(type: EntityType<out Ru57e6MissileEntity>, level: Level) :
    MissileProjectile(type, level), BasicGeoProjectileEntity {

    var launcherVehicleUUID: UUID? = null

    fun setLauncherVehicle(uuid: UUID?) {
        this.launcherVehicleUUID = uuid
    }

    private var lostSignalTicks = 0
    private var fallSpreadApplied = false
    private var launchPos: Vec3? = null
    // "Out of fuel" — once it's covered MAX_FLIGHT_RANGE blocks it goes
    // ballistic and drops, no matter what's currently guiding it (radar
    // lock, manual ATGM steering, doesn't matter). There was previously no
    // limit at all: a missile with an operator still present could fly
    // forever.
    private var fuelExhausted = false

    init {
        this.noCulling = true
    }

    override fun getDefaultItem(): Item {
        return ModItems.MEDIUM_ANTI_AIR_MISSILE.get()
    }

    override fun tick() {
        super.tick()

        if (launchPos == null) launchPos = position()
        if (!fuelExhausted && launchPos!!.distanceTo(position()) > MAX_FLIGHT_RANGE) {
            // Fuel's gone: force it into the same "abandoned" ballistic-fall
            // state below, overriding whatever guidance was still active —
            // setLost/clearing the target here means the isLost() branch
            // just underneath sees exactly the same state it would for an
            // actually-lost signal.
            fuelExhausted = true
            setLost(true)
            setTargetUUID("none")
        }

        // No more exhaust trail once the fuel's spent — matches the round
        // actually going dead/ballistic instead of still looking powered.
        if (!fuelExhausted) {
            mediumTrail()
        }

        // No target at all (fired without a lock — VehicleFireMessage's
        // uuid was null) is treated exactly like an already-lost signal:
        // there's nothing to command-guide toward, so it falls straight
        // into the same manual ATGM-style fallback below instead of just
        // coasting dead straight with no correction at all.
        if (isLost() || this.getTargetUUID() == "none") {
            val owner = this.owner
            val vehicle = owner?.vehicle as? PantsirEntity

            if (!fuelExhausted && owner != null && vehicle != null && launcherVehicleUUID == vehicle.uuid) {
                // Radar lock's gone, but the gunner who fired it is still
                // right there and can walk it in by eye — same manual
                // fallback WireGuideMissileEntity already uses: steer
                // toward wherever the barrel is CURRENTLY pointed, every
                // tick, continuously — genuine ATGM-style guidance instead
                // of the round just going dumb.
                this.deltaMovement = this.deltaMovement.scale(0.5).add(lookAngle.scale(2.0))
                val lookVec = vehicle.getBarrelVector(1f).scale(1.6)
                val missileVec = vehicle.getShootPos(owner, 1f).vectorTo(position()).normalize()
                turn(missileVec.vectorTo(lookVec), ((tickCount - 1) * 0.4f).coerceIn(0f, 6f))
                return
            }

            // Nobody left to steer it (gunner dismounted, vehicle
            // destroyed, etc.) — nose over and actually fall (gravity
            // re-enabled below) instead of coasting dead straight forever.
            //
            // Applied once, the instant that happens: a random horizontal
            // kick so every abandoned missile tumbles off on its own
            // heading instead of every single one falling along the exact
            // same extrapolated line and landing in the same spot.
            if (!fallSpreadApplied) {
                fallSpreadApplied = true
                val jitterDegrees = (level().random.nextFloat() - 0.5f) * 2f * FALL_SPREAD_DEGREES
                val horizontal = Vec3(deltaMovement.x, 0.0, deltaMovement.z).yRot(Math.toRadians(jitterDegrees.toDouble()).toFloat())
                deltaMovement = Vec3(horizontal.x, deltaMovement.y, horizontal.z)
            }
            val fallVec = Vec3(deltaMovement.x, -deltaMovement.horizontalDistance().coerceAtLeast(1.0), deltaMovement.z)
            turn(fallVec, 3f)
            return
        }

        val entity = EntityFindUtil.findEntity(this.level(), this.getTargetUUID())
        if (entity == null || this.getTargetUUID() == "none") return

        if ((entity.getPassengers().isNotEmpty() || entity is VehicleEntity)
            && entity.tickCount % (max(0.04 * this.distanceTo(entity), 2.0).toInt()) == 0
        ) {
            entity.level().playSound(
                null,
                entity.onPos,
                if (entity is Pig) SoundEvents.PIG_HURT else ModSounds.MISSILE_WARNING.get(),
                SoundSource.PLAYERS,
                2f,
                1f
            )
        }

        val targetPos = Vec3(
            entity.x,
            entity.y + 0.5f * entity.bbHeight + (if (entity is EnderDragon) -3 else 0),
            entity.z
        )

        if (this.tickCount <= 1) return

        if (!hasCommandLink()) {
            lostSignalTicks++
            if (lostSignalTicks > MAX_LOST_SIGNAL_TICKS) {
                setLost(true)
                setTargetUUID("none")
            }
            // Uplink briefly broken — coast on the current heading rather
            // than snapping/wobbling, no turn() this tick either way.
            return
        }
        lostSignalTicks = 0

        val toVec = calculateFiringSolution(
            position(),
            targetPos,
            entity.deltaMovement,
            deltaMovement.length(),
            0.0
        )

        setLostTarget(calculateAngle(deltaMovement, toVec) > 120 && !isLostTarget())

        if (!isLostTarget()) {
            turn(toVec, ((tickCount - 1) * 0.5f).coerceIn(0f, 15f))
            this.deltaMovement = this.deltaMovement.scale(0.05).add(lookAngle.scale(8.0))
        }

        if (isLostTarget()) {
            this.setTargetUUID("none")
        }
    }

    // Guidance is tied to the Pantsir's genuine, PERSISTENT lock state
    // (PantsirEntity.trackedTargetUUID — the same flag driving the turret's
    // own auto-tracking and the on-screen lock box), not to some angle
    // freshly re-measured against wherever the barrel happens to be
    // pointing this exact tick.
    //
    // That distinction matters: an earlier version of this recomputed the
    // angle from the CURRENT boresight every tick, which made the missile
    // behave like a wire-guided ATGM — it kept re-acquiring guidance the
    // instant the barrel/gunner's aim swept back near the target, even
    // AFTER the actual lock had already dropped (gunner looked away, lock
    // expired, then looked back without re-locking). A real command-guided
    // round doesn't work that way: once the fire-control system's lock is
    // gone, that's it — the round goes ballistic and stays that way, it
    // doesn't silently resume just because the turret happens to swing
    // back through the target's bearing.
    private fun hasCommandLink(): Boolean {
        val owner = this.owner ?: return false
        val vehicle = owner.vehicle as? PantsirEntity ?: return false
        if (launcherVehicleUUID != vehicle.uuid) return false
        if (vehicle.getSeatIndex(owner) != 2 || !vehicle.jacksDeployed) return false
        return vehicle.trackedTargetUUID == this.getTargetUUID()
    }

    // No gravity while actively radar-guided (matches every other missile
    // in the mod) — and NONE either while a gunner is still manually
    // walking it in ATGM-style, whether that's after losing lock OR because
    // it was fired with no target to begin with (same as
    // WireGuideMissileEntity, which never has gravity at all). Only falls
    // once genuinely abandoned — see the matching branch in tick().
    override fun getCustomGravity(): Float {
        if (fuelExhausted) return 0.05f
        if (!isLost() && this.getTargetUUID() != "none") return 0f
        val owner = this.owner
        val vehicle = owner?.vehicle as? PantsirEntity
        val stillManuallyControlled = owner != null && vehicle != null && launcherVehicleUUID == vehicle.uuid
        return if (stillManuallyControlled) 0f else 0.05f
    }

    override fun getSound(): SoundEvent {
        return ModSounds.ROCKET_FLY.get()
    }

    override fun getVolume(): Float {
        return 0.4f
    }

    companion object {
        // PantsirEntity.trackedTargetUUID already carries its own ~10-tick
        // grace (baseTick's heartbeat timeout) before it clears — this is
        // just a little extra slack to ride out sync-packet timing between
        // server and this entity, not a second "forgiveness window" on top.
        private const val MAX_LOST_SIGNAL_TICKS = 4
        private const val FALL_SPREAD_DEGREES = 25f
        private const val MAX_FLIGHT_RANGE = 700.0
    }
}
