package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.EntityFindUtil
import com.atsuishio.superbwarfare.tools.VectorTool
import com.atsuishio.superbwarfare.tools.deltaFrameTime
import com.atsuishio.superbwarfare.tools.mc
import com.atsuishio.superbwarfare.tools.angleTo
import com.mojang.math.Axis
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Quaterniond
import java.util.UUID
import java.util.function.Function

class PantsirEntity(type: EntityType<PantsirEntity>, world: Level) : VehicleEntity(type, world), BasicGeoVehicleEntity {
    // Helper: compute body lift offset (shared by all transforms). Uses the
    // same BODY_LIFT_BLOCKS constant as PantsirRenderer so the mesh and the
    // hitboxes rise by exactly the same amount when the jacks deploy.
    // Proportional to jp directly (not gated to the second half of the
    // animation) so the hull rises IN STEP with the legs extending, instead
    // of the legs reaching all the way down first with no lift compensating
    // — which is what made them look like they were digging into the ground
    // during the first half of the deploy/retract animation.
    private fun bodyLift(partialTicks: Float): Double {
        val p = Mth.lerp(partialTicks, jacksProgressO, jacksProgress)
        return p * BODY_LIFT_BLOCKS.toDouble()
    }

    // Every OBB transform (wheels, turret, barrel, doors, jacks, the plain
    // "Vehicle"/"Default" hull transform) is built on top of this one — adding
    // the lift here once makes every hitbox rise with the model on jack-up,
    // instead of only the few transforms that explicitly added it themselves.
    override fun getVehicleYOffsetTransform(partialTicks: Float): Matrix4d {
        val transform = super.getVehicleYOffsetTransform(partialTicks)
        val lift = bodyLift(partialTicks)
        if (lift > 0.0001) transform.translate(0.0, lift, 0.0)
        return transform
    }

    // The Collision-part OBB has no "Transform" of its own in the generated
    // data, so it falls back to "Default" — the SAME transform every other
    // hitbox uses, which now includes the lift above. Lifting Collision too
    // meant the box gravity/ground-collision actually use to hold the
    // vehicle up floated along with the jack animation; the engine then kept
    // pulling the vehicle's real position back down to "settle" against
    // that floating box, fighting the lift every tick — the shaking, and the
    // model never visibly rising. Undo the lift on Collision specifically;
    // every other hitbox (and the mesh, via the renderer's own poseStack
    // translate) still rises normally.
    override fun updateOBB(partialTicks: Float) {
        super.updateOBB(partialTicks)
        val lift = bodyLift(partialTicks)
        if (lift > 0.0001) {
            this.obb.forEach { info ->
                val obb = info.getOBB()
                if (obb.part == com.atsuishio.superbwarfare.tools.OBB.Part.COLLISION) {
                    obb.center.y -= lift
                }
            }
        }
    }

    init {
        // Spawn with the guns already in their travel/stow orientation
        // (muzzle over the tailgate) instead of the default forward (0°).
        turretYRot = 180f
        turretYRotO = 180f

        registerWheelTransform("PantsirWheelTurnL", FRONT_LEFT_PIVOT, true, true)
        registerWheelTransform("PantsirWheelTurnR", FRONT_RIGHT_PIVOT, false, true)
        registerWheelTransform("PantsirWheelCenterL", CENTER_LEFT_PIVOT, true, false)
        registerWheelTransform("PantsirWheelCenterR", CENTER_RIGHT_PIVOT, false, false)
        registerWheelTransform("PantsirWheelRear1L", REAR1_LEFT_PIVOT, true, false)
        registerWheelTransform("PantsirWheelRear1R", REAR1_RIGHT_PIVOT, false, false)
        registerWheelTransform("PantsirWheelRear2L", REAR2_LEFT_PIVOT, true, false)
        registerWheelTransform("PantsirWheelRear2R", REAR2_RIGHT_PIVOT, false, false)

        positionTransform["PantsirSteeringWheel"] = Function { partialTicks -> steeringWheelTransform(partialTicks) }
        rotationTransform["PantsirSteeringWheel"] = Function { partialTicks -> steeringWheelRotation(partialTicks) }

        // Door OBB: rotate around door pivot (body lift comes from
        // getVehicleYOffsetTransform, included in getVehicleTransform already)
        positionTransform["PantsirLeftDoor"] = Function { partialTicks ->
            val p = Mth.lerp(partialTicks, leftDoorProgressO, leftDoorProgress)
            getVehicleTransform(partialTicks)
                .translate(LEFT_DOOR_PIVOT.x, LEFT_DOOR_PIVOT.y, LEFT_DOOR_PIVOT.z)
                .rotate(Axis.YP.rotationDegrees(-70f * p))
                .translate(-LEFT_DOOR_PIVOT.x, -LEFT_DOOR_PIVOT.y, -LEFT_DOOR_PIVOT.z)
        }
        rotationTransform["PantsirLeftDoor"] = Function { partialTicks ->
            val p = Mth.lerp(partialTicks, leftDoorProgressO, leftDoorProgress)
            val rot = VectorTool.combineRotations(partialTicks, this)
            if (p > 0.01f) rot.mul(Quaterniond(Axis.YP.rotationDegrees(-70f * p)))
            rot
        }
        positionTransform["PantsirRightDoor"] = Function { partialTicks ->
            val p = Mth.lerp(partialTicks, rightDoorProgressO, rightDoorProgress)
            getVehicleTransform(partialTicks)
                .translate(RIGHT_DOOR_PIVOT.x, RIGHT_DOOR_PIVOT.y, RIGHT_DOOR_PIVOT.z)
                .rotate(Axis.YP.rotationDegrees(70f * p))
                .translate(-RIGHT_DOOR_PIVOT.x, -RIGHT_DOOR_PIVOT.y, -RIGHT_DOOR_PIVOT.z)
        }
        rotationTransform["PantsirRightDoor"] = Function { partialTicks ->
            val p = Mth.lerp(partialTicks, rightDoorProgressO, rightDoorProgress)
            val rot = VectorTool.combineRotations(partialTicks, this)
            if (p > 0.01f) rot.mul(Quaterniond(Axis.YP.rotationDegrees(70f * p)))
            rot
        }

        // LADDER OBB: folds out AWAY from the hull around its own local Z
        // axis. The renderer's runtime bone.rotation.rotateZ() and this
        // Axis.ZP-based matrix chain need OPPOSITE signs to move in the same
        // visual direction (same asymmetry documented for RADAR above) —
        // renderer uses +155°, so the OBB uses -155° to match. -155 and
        // +205 land on the same final angle, but -155 takes the short way
        // (matching the renderer's short-way +155) instead of clipping
        // through the hull on the long way around.
        positionTransform["PantsirLadder"] = Function { partialTicks ->
            val p = Mth.lerp(partialTicks, ladderProgressO, ladderProgress)
            getVehicleTransform(partialTicks)
                .translate(LADDER_PIVOT.x, LADDER_PIVOT.y, LADDER_PIVOT.z)
                .rotate(Axis.ZP.rotationDegrees(-155f * p))
                .translate(-LADDER_PIVOT.x, -LADDER_PIVOT.y, -LADDER_PIVOT.z)
        }
        rotationTransform["PantsirLadder"] = Function { partialTicks ->
            val p = Mth.lerp(partialTicks, ladderProgressO, ladderProgress)
            val rot = VectorTool.combineRotations(partialTicks, this)
            if (p > 0.01f) rot.mul(Quaterniond(Axis.ZP.rotationDegrees(-155f * p)))
            rot
        }

        // OPORY OBB: jacks offset (body lift comes from getVehicleYOffsetTransform)
        positionTransform["PantsirJacks"] = Function { partialTicks ->
            val p = Mth.lerp(partialTicks, jacksProgressO, jacksProgress)
            val jackExtend = (p * 2f).coerceAtMost(1f)
            getVehicleTransform(partialTicks)
                .translate(OPORY_PIVOT.x, OPORY_PIVOT.y, OPORY_PIVOT.z)
                .translate(0.0, -jackExtend * 0.667, 0.0)
                .translate(-OPORY_PIVOT.x, -OPORY_PIVOT.y, -OPORY_PIVOT.z)
        }
        rotationTransform["PantsirJacks"] = Function { partialTicks ->
            VectorTool.combineRotations(partialTicks, this)
        }

        // RADAR OBB: derived directly from BedrockModel.java's bone loader
        // (negates authored rotation X/Y, keeps Z: rotateZYX(rz, -ry, -rx))
        // and PantsirRenderer's runtime fold/spin, then converted into the
        // OBB's own sbw conventions (generate_vehicle_obbs.py: Position/pivot
        // values only negate Z; baked CustomRotate goes through sbw_conj(),
        // which negates X and Z). RADAR's authored rotation is [-70,0,0], so
        // the loaded bind pose is actually Rx(+70) (negated) — X-axis terms
        // flip sign under EITHER sbw convention, but Y (the spin axis) only
        // flips under the position convention, not the rotation one — hence
        // "+spin" for position but "-spin" for rotation below. Verified
        // numerically against the exact mesh formula (both position and
        // rotation, all spin angles) before writing this; at spin=0 both
        // reduce identically to the pre-existing, known-good non-spin
        // formula (Rx(jp*70) / CRT*Rx(jp*70)).
        positionTransform["PantsirRadar"] = Function { partialTicks ->
            val jp = Mth.lerp(partialTicks, jacksProgressO, jacksProgress)
            val spin = if (jp > 0.95f) radarSpin else 0f
            getTurretTransform(partialTicks)
                .translate(RADAR_PIVOT.x, RADAR_PIVOT.y, RADAR_PIVOT.z)
                .rotate(Axis.XP.rotationDegrees(jp * 70f - 70f))
                .translate(RADAR_SPIN_OFFSET.x, RADAR_SPIN_OFFSET.y, RADAR_SPIN_OFFSET.z)
                .rotate(Axis.YP.rotationDegrees(spin))
                .translate(-RADAR_SPIN_OFFSET.x, -RADAR_SPIN_OFFSET.y, -RADAR_SPIN_OFFSET.z)
                .rotate(Axis.XP.rotationDegrees(70f))
        }
        rotationTransform["PantsirRadar"] = Function { partialTicks ->
            val jp = Mth.lerp(partialTicks, jacksProgressO, jacksProgress)
            val spin = if (jp > 0.95f) radarSpin else 0f
            VectorTool.combineRotationsTurret(partialTicks, this)
                .mul(Quaterniond(Axis.XP.rotationDegrees(jp * 70f)))
                .mul(Quaterniond(Axis.XP.rotationDegrees(-70f)))
                .mul(Quaterniond(Axis.YP.rotationDegrees(-spin)))
                .mul(Quaterniond(Axis.XP.rotationDegrees(70f)))
        }
    }

    private fun registerWheelTransform(name: String, pivot: Vec3, left: Boolean, steering: Boolean) {
        positionTransform[name] = Function { partialTicks -> wheelTransform(pivot, left, steering, partialTicks) }
        rotationTransform[name] = Function { partialTicks -> wheelRotation(left, steering, partialTicks) }
    }

    private fun wheelTransform(pivot: Vec3, left: Boolean, steering: Boolean, partialTicks: Float): Matrix4d {
        val transform = getVehicleTransform(partialTicks).translate(pivot.x, pivot.y, pivot.z)
        if (steering) transform.rotate(Axis.YP.rotation(rudderRot))
        return transform
    }

    private fun wheelRotation(left: Boolean, steering: Boolean, partialTicks: Float): Quaterniond {
        val rotation = VectorTool.combineRotations(partialTicks, this)
        if (steering) rotation.mul(Quaterniond(Axis.YP.rotation(rudderRot)))
        return rotation
    }

    private fun steeringWheelTransform(partialTicks: Float): Matrix4d {
        val transform = getVehicleTransform(partialTicks)
            .translate(STEERING_WHEEL_PIVOT.x, STEERING_WHEEL_PIVOT.y, STEERING_WHEEL_PIVOT.z)
            .rotate(Axis.XP.rotationDegrees(60f))
        return transform.rotate(Axis.ZP.rotation(-12 * Mth.lerp(partialTicks, rudderRotO, rudderRot)))
    }

    private fun steeringWheelRotation(partialTicks: Float): Quaterniond {
        val rotation = VectorTool.combineRotations(partialTicks, this)
        rotation.mul(Quaterniond(Axis.XP.rotationDegrees(60f)))
        return rotation.mul(Quaterniond(Axis.ZP.rotation(-12 * Mth.lerp(partialTicks, rudderRotO, rudderRot))))
    }

    // ── State ─────────────────────────────────────────────────────────────────
    var jacksDeployed: Boolean
        get() = entityData.get(JACKS_STATE).toInt() and 1 != 0
        set(value) { entityData.set(JACKS_STATE, ((entityData.get(JACKS_STATE).toInt() and 0xFE) or if (value) 1 else 0).toByte()) }
    var leftDoorOpen: Boolean
        get() = entityData.get(JACKS_STATE).toInt() and 2 != 0
        set(value) { entityData.set(JACKS_STATE, ((entityData.get(JACKS_STATE).toInt() and 0xFD) or if (value) 2 else 0).toByte()) }
    var rightDoorOpen: Boolean
        get() = entityData.get(JACKS_STATE).toInt() and 4 != 0
        set(value) { entityData.set(JACKS_STATE, ((entityData.get(JACKS_STATE).toInt() and 0xFB) or if (value) 4 else 0).toByte()) }
    var ladderOpen: Boolean
        get() = entityData.get(JACKS_STATE).toInt() and 8 != 0
        set(value) { entityData.set(JACKS_STATE, ((entityData.get(JACKS_STATE).toInt() and 0xF7) or if (value) 8 else 0).toByte()) }
    // True while the missile launch rail is cranking up to the minimum
    // launch elevation (see missileExtraPitch below) — blocks re-firing
    // until the pending shot actually goes out.
    var missileWindupActive: Boolean
        get() = entityData.get(JACKS_STATE).toInt() and 16 != 0
        set(value) { entityData.set(JACKS_STATE, ((entityData.get(JACKS_STATE).toInt() and 0xEF) or if (value) 16 else 0).toByte()) }
    // Which rail (RocketL = true) the CURRENT/last shot actually left from.
    // Set once, right when a shot is initiated, from the real ammo-indexed
    // fire position — and held there for that shot's whole wind-up+decay
    // animation. Reading the fire position live every frame instead (as
    // this used to) meant that the instant the shot actually fired and
    // ammo decremented, firePosition() jumped to the NEXT round's index —
    // flipping the rail mid-decay while the current one was still
    // animating back down.
    var missileFireSide: Boolean
        get() = entityData.get(JACKS_STATE).toInt() and 32 != 0
        set(value) { entityData.set(JACKS_STATE, ((entityData.get(JACKS_STATE).toInt() and 0xDF) or if (value) 32 else 0).toByte()) }

    var jacksProgress = 0f; var jacksProgressO = 0f
    var leftDoorProgress = 0f; var leftDoorProgressO = 0f
    var rightDoorProgress = 0f; var rightDoorProgressO = 0f
    var ladderProgress = 0f; var ladderProgressO = 0f
    var radarSpin = 0f

    // Состояние сглаживания камеры наводчика, только клиентское.
    private var smoothedCameraDirection: Vec3? = null
    private var smoothedCameraTarget: String? = null

    // Extra pitch (position-space sign convention: negative = up, same as
    // turretXRot) added ON TOP of the current turretXRot/barrel pitch, ONLY
    // for the missile rail — the gun's own pitch/reticle never moves for
    // this. Independently lerped toward 0/target on both sides each tick
    // from the same synced inputs (turretXRot, missileWindupActive), the
    // same "no float sync needed" trick jacksProgress/ladderProgress use.
    var missileExtraPitch = 0f; var missileExtraPitchO = 0f
    private var pendingMissileLiving: LivingEntity? = null
    private var pendingMissileUuid: UUID? = null
    private var pendingMissileTargetPos: Vec3? = null

    // Extra elevation the rail needs to wind up to, FROZEN at the exact
    // turretXRot the instant wind-up starts — not recomputed from the
    // live turretXRot every tick. Against a moving target the turret is
    // continuously re-aiming (auto-aim chasing a shifting lead solution),
    // so a live recompute made this a moving target for the lerp to chase,
    // and against a target whose required elevation kept climbing it could
    // take a very long time (or never) get within the "close enough"
    // tolerance to actually fire. Freezing it guarantees the lerp
    // converges in a bounded number of ticks regardless of what the turret
    // does afterward. missileWindupTicks is a hard timeout on top, in case
    // some other edge case still stalls it.
    private var missileWindupTargetExtra = 0f
    private var missileWindupTicks = 0
    // Edge-detects missileWindupActive's rising edge independently on
    // whichever side is running this tick (client or server) — see
    // baseTick(), where the freeze actually happens.
    private var missileWindupActivePrevTick = false

    // ── Soft lock state ──────────────────────────────────────────────────
    // Persistent, target-relative aim offset the gunner builds up by moving
    // the mouse while a target is under lock. The turret tracks the locked
    // target directly (base = barrel → target, NO lead — the 57Э6 steers
    // itself onto the target in flight via proportional navigation, so the
    // rail only needs to point roughly at it) and adds this offset on top,
    // so the gunner can nudge the aim ahead/behind/above/below without
    // losing the track. Mouse-still → offset frozen → turret keeps following
    // the target at that same relative angle. Reset the instant the lock
    // drops, the gunner dismounts, or the weapon switches away from Missile.
    private var lockOffsetYaw = 0f
    private var lockOffsetPitch = 0f
    private var prevGunnerYaw = 0f
    private var prevGunnerPitch = 0f
    private var softLockArmed = false
    // Last desired direction computed by softLockTurret — used by
    // missileLauncherOnTarget() to check the barrel has reached its
    // (target + offset) aim, not the raw target direction.
    private var lastSoftLockDesired: Vec3? = null
    // Dampened Y of the tracked target — lerps toward the real Y at
    // Y_TRACK_DAMP_FACTOR per tick, so vertical movement is followed at
    // 20% rate. Prevents a diving target from yanking the rail down.
    private var softLockDampenedY: Double? = null

    // Turret target-tracking: once the gunner has a full radar lock (X key),
    // the turret leads the locked target itself instead of just following
    // raw mouse look — refreshed by a small heartbeat sent from the client
    // every couple ticks while locked (SeekingWeaponWarningMessage, the same
    // packet that already plays the "locked" warning sound on the target).
    // If that heartbeat stops arriving (lock lost, gunner leaves the seat,
    // target dies) for more than TRACK_TIMEOUT_TICKS, tracking auto-expires
    // and control reverts to normal mouse look — no explicit "unlock"
    // message needed. Synced (not a plain var) so the CONTROLLING player's
    // own client runs the identical adjustTurretAngle() auto-aim math the
    // server does — otherwise the client's own turret prediction would keep
    // showing raw mouse look (it never sees trackedTargetUUID) while the
    // server's synced turretXRot/turretYRot snap to the lead-aim solution
    // every sync tick, fighting each other visibly.
    var trackedTargetUUID: String?
        get() = entityData.get(TRACKED_TARGET).let { if (it == "none") null else it }
        set(value) { entityData.set(TRACKED_TARGET, value ?: "none") }

    // Only meaningful server-side (the heartbeat only ever arrives there) —
    // the timeout check itself is gated to the server too, so the client
    // never races its own (always-stale) copy of this against the synced
    // trackedTargetUUID it just received.
    private var trackedTargetRefreshTick = Int.MIN_VALUE / 2

    fun refreshTrackedTarget(uuid: String) {
        trackedTargetUUID = uuid
        trackedTargetRefreshTick = tickCount
    }

    // ── Seat 3 → deploy jacks ─────────────────────────────────────────────────
    override fun addPassenger(pPassenger: Entity) {
        super.addPassenger(pPassenger)
        if (getSeatIndex(pPassenger) == 2) jacksDeployed = true
    }

    override fun removePassenger(pPassenger: Entity) {
        val seatIndex = getSeatIndex(pPassenger)
        super.removePassenger(pPassenger)
        // Seat 2's jacksDeployed=false is set by baseTick's staged retract once
        // the turret has wound back down and the radar has stopped — not here.
        when (seatIndex) {
            0 -> if (!level().isClientSide) leftDoorOpen = true
            1 -> if (!level().isClientSide) rightDoorOpen = true
            2 -> if (!level().isClientSide) ladderOpen = true
        }
    }

    override fun changeSeat(entity: Entity, index: Int): Boolean {
        // The gunner seat (2) is reachable ONLY through the ladder click
        // (interactAt, which boards via addPassenger) — not by shift+number
        // seat-switching from the driver cabin (0/1), and you can't
        // shift+number your way back out of it into the cabin either.
        // Dismounting and re-boarding through a door/the ladder still works
        // fine, since that's addPassenger, not this method.
        val currentIndex = getSeatIndex(entity)
        val cabinToGunner = index == 2 && (currentIndex == 0 || currentIndex == 1)
        val gunnerToCabin = currentIndex == 2 && (index == 0 || index == 1)
        if (cabinToGunner || gunnerToCabin) return false

        val result = super.changeSeat(entity, index)
        // addPassenger only runs once, when a player first mounts the vehicle
        // (usually into seat 0/1 via a door) — switching seats afterwards goes
        // through changeSeat instead, which never set jacksDeployed. If the
        // gunner seat is reached by boarding elsewhere and then switching seats,
        // this was the missing trigger for the whole jacks-deploy sequence.
        if (result && index == 2) jacksDeployed = true
        return result
    }

    // Soft lock: when the gunner has a target under lock AND the Missile
    // weapon is selected, the turret tracks the target directly (NO lead —
    // the 57Э6 is command-guided with proportional navigation, it steers
    // itself onto the target in flight, so the rail only needs to point
    // roughly at it, not at a pre-computed lead point) and the gunner's
    // mouse movement builds up a persistent offset on top of that base
    // direction — so they can nudge the aim ahead/behind/above/below the
    // target without losing the track. Falls back to normal mouse-look the
    // instant the target is lost, the lock expires, or the weapon switches.
    //
    // The autocannon is NEVER auto-aimed, even with a lock: real CIWS-style
    // autocannons need the gunner to walk the reticle onto the lead point
    // themselves; it only gets a lead-point MARKER
    // (VehicleCrosshairOverlay.drawLeadMarker) showing where that point is,
    // never an auto-aimed barrel.
    override fun adjustTurretAngle() {
        val uuid = trackedTargetUUID
        val gunner = getNthEntity(2) as? LivingEntity
        if (uuid != null && gunner != null && getGunName(2) == "Missile") {
            val target = EntityFindUtil.findEntity(level(), uuid)
            if (target != null) {
                softLockTurret(gunner, target)
                return
            }
        }
        softLockReset()
        super.adjustTurretAngle()
    }

    private fun softLockReset() {
        softLockArmed = false
        lockOffsetYaw = 0f
        lockOffsetPitch = 0f
        lastSoftLockDesired = null
        softLockDampenedY = null
    }

    /**
     * Сопровождение с ручным отклонением: башня ведёт цель (прямо в неё, без
     * упреждения — 57Э6 доводится пропорциональным наведением в полёте, так
     * что направляющей достаточно смотреть на цель), а мышью стрелок
     * накладывает поверх своё отклонение — вперёд/назад/вниз/вверх. Мышь
     * не трогает — отклонение держится, башня едет за целью.
     */
    private fun softLockTurret(gunner: LivingEntity, target: Entity) {
        val barrelRoot = getShootPos(gunner, 1f)
        val targetCenter = target.boundingBox.center

        // Dampen the Y tracking to 20%: the turret follows the target's
        // horizontal position at full rate, but only 20% of its vertical
        // movement per tick. A diving target can't yank the rail down —
        // the 57Э6 corrects its trajectory in flight, so the launcher
        // doesn't need to chase vertical movement at full speed.
        val targetY = targetCenter.y
        val prevY = softLockDampenedY
        val aimY = if (prevY != null) {
            prevY + (targetY - prevY) * Y_TRACK_DAMP_FACTOR
        } else {
            targetY
        }
        softLockDampenedY = aimY
        val aimPos = Vec3(targetCenter.x, aimY, targetCenter.z)
        val baseDir = barrelRoot.vectorTo(aimPos).normalize()

        // First tick of soft lock: seed prev-gunner-angles from the current
        // view so the very first mouse delta isn't a giant jump from zero.
        if (!softLockArmed) {
            softLockArmed = true
            prevGunnerYaw = gunner.yRot
            prevGunnerPitch = gunner.xRot
        }

        // Mouse delta this tick → accumulates into the persistent offset.
        // wrapDegrees on yaw handles the ±180° seam; pitch doesn't wrap.
        val dYaw = Mth.wrapDegrees(gunner.yRot - prevGunnerYaw)
        val dPitch = gunner.xRot - prevGunnerPitch
        prevGunnerYaw = gunner.yRot
        prevGunnerPitch = gunner.xRot

        lockOffsetYaw = Mth.clamp(lockOffsetYaw + dYaw, -LOCK_OFFSET_MAX_DEG, LOCK_OFFSET_MAX_DEG)
        lockOffsetPitch = Mth.clamp(lockOffsetPitch + dPitch, -LOCK_OFFSET_MAX_DEG, LOCK_OFFSET_MAX_DEG)

        // Desired turret direction: base yaw + offset, pitch + offset.
        // Computed in ANGLE space, not by rotating the vector — Vec3.xRot
        // rotates around the WORLD X axis, which inverts the pitch sign
        // when the base direction faces -Z or ±X (the bug: "up/down
        // inverts on negative coordinates"). Working in angle space is
        // sign-safe regardless of which way the launcher is pointing.
        val baseYawDeg = Math.toDegrees(kotlin.math.atan2(-baseDir.x, baseDir.z)).toFloat()
        val basePitchDeg = -Math.toDegrees(kotlin.math.asin(baseDir.y.coerceIn(-1.0, 1.0))).toFloat()
        val desired = Vec3.directionFromRotation(basePitchDeg + lockOffsetPitch, baseYawDeg + lockOffsetYaw)

        lastSoftLockDesired = desired
        turretAutoAimFromVector(desired)
    }

    // Guns folded to the stowed travel position (yaw 180° from the nose,
    // muzzle over the tailgate — same orientation the vehicle spawns in —
    // pitch level). Shared by baseTick's staged retract and the drive-lock
    // below so both use the exact same "home" definition.
    private val turretHome: Boolean
        get() = kotlin.math.abs(Mth.wrapDegrees(180f - turretYRot)) < 0.5f && kotlin.math.abs(turretXRot) < 0.5f

    // Routes through the SAME "engine not ready" path used for the actual
    // engine-off/starting states, instead of reactively zeroing deltaMovement
    // after travel() already applied it (which still let the vehicle creep
    // forward a little every tick, and left power/rotation accumulating
    // from the wheel-engine code while "blocked", causing a jolt once the
    // turret came home and driving was allowed again). This way, while the
    // turret isn't home, travel() takes the parked branch: steerWhileParked()
    // still turns the wheel, and the vehicle coasts to a smooth stop instead
    // of just refusing to move.
    // jacksDeployed is included here too — with only turretHome checked, the
    // instant the gunner's aim happened to pass back through 180°/0° (well
    // within the 0.5° tolerance) engineReady() went true for that one tick,
    // travel() ran the full wheel-engine code and added real velocity, and
    // only THEN did tick()'s reactive zeroing below catch it — the exact
    // "creeps forward every tick" bug this was supposed to fix in the first
    // place, just moved from turretHome to jacksDeployed.
    // jacksProgress on top of that — jacksDeployed itself flips to false the
    // instant the staged retract finishes, but jacksProgress (what the jacks
    // actually visually are, and what drives jackExtend = jp*2 in the
    // renderer) then takes several more seconds to lerp/decay down to 0, so
    // driving was unlocked while the legs were still visibly extended (e.g.
    // at jp=0.05 the legs are still 10% extended). baseTick snaps jacksProgress
    // to EXACTLY 0f once it decays below 0.02 while retracting — checking
    // for that exact 0f (rather than some looser threshold) guarantees the
    // legs are fully, visibly stowed before driving unlocks.
    override fun engineReady(): Boolean = super.engineReady() && turretHome && !jacksDeployed && jacksProgress <= 0f

    /**
     * Довёрнута ли пусковая на захваченную цель настолько, чтобы ракета сошла
     * в её сторону. Без захвата (свободный пуск по стволу) ограничения нет —
     * там куда ствол, туда и ракета, это и есть намерение стрелка.
     *
     * При мягком захвате ствол ведётся не на прямую до цели, а на
     * (цель + отклонение стрелка) — проверяем доворот до этого желаемого
     * направления, а не до самой цели, иначе намеренный уход вперёд/вбок
     * блокировал бы пуск до таймаута.
     */
    private fun missileLauncherOnTarget(): Boolean {
        val uuid = trackedTargetUUID ?: return true
        EntityFindUtil.findEntity(level(), uuid) ?: return true
        pendingMissileLiving ?: return true
        val reference = lastSoftLockDesired ?: return true
        return getBarrelVector(1f).angleTo(reference) <= MISSILE_LAUNCH_CONE_DEGREES
    }

    /**
     * Куда смотрит камера наводчика. При мягком захвате камера едет за
     * башней — смотрит туда, куда башня НАЦЕЛЕНА (цель + отклонение
     * стрелка), а не жёстко на саму цель. Двигаешь мышь — отклонение
     * растёт — башня и камера вместе уходят в сторону, и стрелок ВИДИТ,
     * куда направляющая смотрит.
     *
     * На первом кадре после захвата (lastSoftLockDesired ещё не посчитан
     * — adjustTurretAngle не отработал) смотрим прямо на цель, как раньше.
     */
    override fun cameraDirection(entity: Entity, partialTicks: Float): Vec3 {
        val uuid = trackedTargetUUID
        if (uuid != null && getSeatIndex(entity) == 2 && getGunName(2) == "Missile") {
            val desired = lastSoftLockDesired
            if (desired != null) {
                return smoothCameraDirection(uuid, desired)
            }
            val target = EntityFindUtil.findEntity(level(), uuid)
            if (target != null) {
                val from = getCameraPos(entity, partialTicks)
                val to = VectorTool.lerpGetEntityBoundingBoxCenter(target, partialTicks)
                val direction = to.subtract(from)
                if (direction.lengthSqr() > 1.0E-6) return smoothCameraDirection(uuid, direction.normalize())
            }
        }
        smoothedCameraTarget = null
        return super.cameraDirection(entity, partialTicks)
    }

    /**
     * Сглаживание хода камеры. Позиция цели на клиенте меняется рывками, и
     * ничего с этим не сделать: у прогруженной цели её присылает трекинг
     * порциями, у загоризонтной — пакет радарной синхронизации раз в несколько
     * тиков, а между ними позиция экстраполируется по скорости и на очередном
     * пакете скачком поправляется. Для быстрой цели вроде Искандера, идущей по
     * дуге, экстраполяция по прямой за эти тики успевает набрать ощутимую
     * ошибку — вот эту поправку и видно как подёргивание.
     *
     * Гасим её сервоприводом: камера идёт не строго в цель, а подтягивается к
     * ней с постоянной скоростью. Настоящая оптическая станция ведёт цель ровно
     * так же — приводом с конечной скоростью, а не мгновенным перебросом.
     */
    private fun smoothCameraDirection(targetUuid: String, desired: Vec3): Vec3 {
        val previous = smoothedCameraDirection
        // Смена цели или первый кадр сопровождения — встаём на неё сразу, без
        // проезда через полнеба.
        if (previous == null || smoothedCameraTarget != targetUuid) {
            smoothedCameraDirection = desired
            smoothedCameraTarget = targetUuid
            return desired
        }

        // Доля за кадр, приведённая к времени кадра: на 20 к/с шаг крупнее, на
        // 120 — мельче, скорость наведения от частоты кадров не зависит.
        val step = (CAMERA_SMOOTHING * mc.deltaFrameTime).coerceIn(0f, 1f).toDouble()
        val smoothed = previous.add(desired.subtract(previous).scale(step))
        if (smoothed.lengthSqr() < 1.0E-6) return desired

        val result = smoothed.normalize()
        smoothedCameraDirection = result
        return result
    }

    // Absolute world-space compass bearing the radar dish is CURRENTLY
    // pointing — the turret's own aim direction (hull yaw + turretYRot,
    // approximated via the turret transform's own local +Z axis, ignoring
    // pitch/roll which don't matter for a top-down bearing) plus the dish's
    // own independent 360° search spin on top of that. Used by
    // PantsirRadarScreen so its sweep line tracks the actual visible dish
    // rotation instead of an unrelated arbitrary angle.
    fun radarWorldBearing(partialTicks: Float = 1f): Float {
        return Mth.wrapDegrees(turretFacingBearing(partialTicks) + radarSpin)
    }

    // Absolute world-space bearing the TURRET itself is aimed at (hull yaw +
    // turretYRot), without the dish's own independent search spin on top.
    // This is the direction the gunner is actually looking/aiming — used by
    // PantsirRadarScreen as the screen's "front" so the display rotates
    // with the turret instead of staying locked to world-north.
    fun turretFacingBearing(partialTicks: Float = 1f): Float {
        val transform = getTurretTransform(partialTicks)
        val origin = transformPosition(transform, 0.0, 0.0, 0.0)
        val forward = transformPosition(transform, 0.0, 0.0, 1.0)
        val dx = forward.x - origin.x
        val dz = forward.z - origin.z
        return Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()
    }

    // World-space position the radar dish is mounted at — used by
    // PantsirRadarScreen as the origin for line-of-sight raycasts against
    // candidate targets.
    fun radarWorldPos(partialTicks: Float = 1f): Vec3 {
        val transform = getTurretTransform(partialTicks)
        val origin = transformPosition(transform, 0.0, 0.0, 0.0)
        return Vec3(origin.x, origin.y, origin.z)
    }

    // Barrel-style world direction (turret yaw + turretXRot pitch), but with
    // missileExtraPitch added on top — used ONLY for the missile's actual
    // launch direction while the rail is wound up above its resting angle,
    // so a shot fired before the wind-up finishes still leaves at the
    // elevated angle instead of the gunner's unmodified aim.
    private fun missileTransform(partialTicks: Float): Matrix4d {
        val transform = getTurretTransform(partialTicks)
        val pitch = Mth.lerp(partialTicks, turretXRotO, turretXRot) +
                Mth.lerp(partialTicks, missileExtraPitchO, missileExtraPitch)
        transform.rotate(Axis.XP.rotationDegrees(pitch))
        return transform
    }

    private fun missileShootVec(partialTicks: Float): Vec3 {
        val transform = missileTransform(partialTicks)
        val origin = transformPosition(transform, 0.0, 0.0, 0.0)
        val forward = transformPosition(transform, 0.0, 0.0, 1.0)
        return Vec3(origin.x, origin.y, origin.z).vectorTo(Vec3(forward.x, forward.y, forward.z)).normalize()
    }

    override fun getShootVec(entity: Entity?, partialTicks: Float): Vec3 {
        if (entity != null && kotlin.math.abs(missileExtraPitch) > 0.05f &&
            getGunName(getSeatIndex(entity)) == "Missile"
        ) {
            return missileShootVec(partialTicks)
        }
        return super.getShootVec(entity, partialTicks)
    }

    // Missiles need at least 25° of upward elevation to launch — if the lock
    // happened with the gun aimed lower than that, wind the RAIL (not the
    // gun/reticle, which stays exactly where the gunner is aiming) up to
    // 25° first, THEN actually fire. baseTick() drives missileExtraPitch
    // toward the needed delta every tick and fires once it arrives.
    override fun vehicleShoot(living: LivingEntity?, uuid: UUID?, targetPos: Vec3?) {
        val seatIndex = getSeatIndex(living)
        if (getGunName(seatIndex) == "Missile") {
            if (missileWindupActive) return
            // Which rail THIS shot leaves from — the "Missile" weapon's
            // ShootPos.Positions is ammo-indexed (BoundUpWithAmmoAmount),
            // 12 tubes total: the first 6 rounds (ammo 12→7) sit on the +X
            // side (RocketL, whose model pivot is at +X), the last 6
            // (ammo 6→1) on the -X side (RocketR) — 6 in a row per rail,
            // not a strict every-shot alternation. Read and frozen here,
            // BEFORE this shot's ammo is spent, so it stays put for the
            // whole wind-up+decay animation instead of jumping to the
            // NEXT round's rail the instant ammo decrements mid-decay.
            getGunData("Missile")?.firePosition()?.let { missileFireSide = it.x >= 0.0 }
            if (turretXRot > -25f) {
                missileWindupActive = true
                // NOT frozen here — vehicleShoot() only ever runs
                // SERVER-SIDE (VehicleFireMessage's handler), so a value
                // set here would never exist on the client at all and the
                // rail would never visually rise for it. Frozen instead in
                // baseTick() below, off the rising edge of the SYNCED
                // missileWindupActive flag, which both sides observe
                // independently.
                pendingMissileLiving = living
                pendingMissileUuid = uuid
                pendingMissileTargetPos = targetPos
                return
            }
        }
        super.vehicleShoot(living, uuid, targetPos)
    }

    override fun tick() {
        super.tick()
        // Belt-and-suspenders on top of engineReady(): jacksDeployed should
        // hold the vehicle fully still (no coasting/rolling down a slope
        // either) rather than just cutting power like a normal "parked"
        // state.
        if (level().isClientSide.not() && jacksDeployed) deltaMovement = Vec3.ZERO
    }

    // ── Right-click: OBB raycast for doors + board ───────────────────────────
    override fun interactAt(player: Player, pVec: Vec3, hand: InteractionHand): InteractionResult {
        if (hand == InteractionHand.MAIN_HAND) {
            if (this.passengers.contains(player)) return InteractionResult.PASS
            // Shift+click is never door/board — let it fall through to interact()
            // (crowbar pack-away, menu, name tag, key, etc.), same as every other vehicle.
            if (player.isShiftKeyDown) return InteractionResult.PASS

            // Двери, трап и посадка — только сервер, см. подробный разбор в
            // KamazEntity.interactAt: клиент выполнял этот же код для
            // предсказания, и на открывающейся створке его решение расходилось
            // с серверным — игрок оказывался севшим только на своём экране.
            if (this.level().isClientSide) return InteractionResult.CONSUME

            val hitObb = com.atsuishio.superbwarfare.tools.OBB.getLookingObb(player, 6.0)
            if (hitObb != null) {
                val obbs = this.getOBBs()
                val idx = obbs.indexOf(hitObb)
                if (idx >= 0 && idx < this.obb.size) {
                    when (this.obb[idx].transform) {
                        "PantsirLeftDoor" -> { leftDoorOpen = !leftDoorOpen; return InteractionResult.CONSUME }
                        "PantsirRightDoor" -> { rightDoorOpen = !rightDoorOpen; return InteractionResult.CONSUME }
                        "PantsirLadder" -> { ladderOpen = !ladderOpen; return InteractionResult.CONSUME }
                    }
                }
            }

            // Measured from the actual clicked point (pVec, relative to this
            // entity), not the player's feet — the ladder sits right behind
            // the cab, so clicking the cab's back wall was closer to the
            // player standing near it than to the actual door they clicked
            // near, and boarded them through the ladder into the gunner seat
            // instead of through the door into the cabin.
            val hitWorldPos = this.position().add(pVec)
            val obbs2 = this.getOBBs()
            var nearestDoor = -1; var nearestDist = Double.MAX_VALUE
            for (i in obbs2.indices) {
                if (i >= this.obb.size) break
                val info = this.obb[i]
                val doorId = when (info.transform) {
                    "PantsirLeftDoor" -> 1; "PantsirRightDoor" -> 2; "PantsirLadder" -> 3; else -> -1
                }
                if (doorId < 0) continue
                val isOpen = when (doorId) { 1 -> leftDoorOpen; 2 -> rightDoorOpen; 3 -> ladderOpen; else -> false }
                if (!isOpen) continue
                val dist = hitWorldPos.distanceToSqr(Vec3(obbs2[i].center.x, obbs2[i].center.y, obbs2[i].center.z))
                // Only board through a door/ladder if the click landed within
                // 1.3 blocks of its hitbox — tighter than the old 4-block
                // radius, so clicking elsewhere on the hull (even near an
                // open door) no longer accidentally boards you through it.
                if (dist < nearestDist && dist < 1.69) { nearestDist = dist; nearestDoor = doorId }
            }
            if (nearestDoor < 0) return InteractionResult.FAIL
            val desiredSeat = when (nearestDoor) { 1 -> 0; 2 -> 1; 3 -> 2; else -> passengers.size }
            if (desiredSeat >= maxPassengers) return InteractionResult.FAIL
            if (passengers.any { getSeatIndex(it) == desiredSeat }) return InteractionResult.FAIL
            entityIndexOverride = java.util.function.Function { _ -> desiredSeat }
            val boarded = player.startRiding(this)
            entityIndexOverride = null
            if (boarded) {
                if (desiredSeat == 0) leftDoorOpen = false
                else if (desiredSeat == 1) rightDoorOpen = false
                else if (desiredSeat == 2) ladderOpen = false
                return InteractionResult.CONSUME
            }
            return InteractionResult.PASS
        }
        return super.interactAt(player, pVec, hand)
    }

    override fun interact(player: Player, hand: InteractionHand): InteractionResult {
        // Non-shift clicks board through the door system (interactAt) only —
        // don't let the base class's walk-up-and-mount fallback bypass doors.
        // Shift+click (crowbar pack-away, menu, name tag, key) delegates as normal.
        if (!player.isShiftKeyDown) return InteractionResult.PASS
        return super.interact(player, hand)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(JACKS_STATE, 0.toByte())
        builder.define(TRACKED_TARGET, "none")
        builder.define(MISSILE_SEMI_AUTO, false)
    }

    var missileSemiAuto: Boolean
        get() = entityData.get(MISSILE_SEMI_AUTO)
        set(value) { entityData.set(MISSILE_SEMI_AUTO, value) }

    override fun addAdditionalSaveData(compound: net.minecraft.nbt.CompoundTag) {
        super.addAdditionalSaveData(compound)
        compound.putByte("JacksState", entityData.get(JACKS_STATE))
        compound.putBoolean("MissileSemiAuto", entityData.get(MISSILE_SEMI_AUTO))
    }

    override fun readAdditionalSaveData(compound: net.minecraft.nbt.CompoundTag) {
        super.readAdditionalSaveData(compound)
        entityData.set(JACKS_STATE, compound.getByte("JacksState"))
        entityData.set(MISSILE_SEMI_AUTO, compound.getBoolean("MissileSemiAuto"))
    }

    override fun baseTick() {
        super.baseTick()
        if (decoyInputDown) horn()

        if (!level().isClientSide && trackedTargetUUID != null && tickCount - trackedTargetRefreshTick > TRACK_TIMEOUT_TICKS) {
            trackedTargetUUID = null
        }

        jacksProgressO = jacksProgress
        leftDoorProgressO = leftDoorProgress
        rightDoorProgressO = rightDoorProgress
        ladderProgressO = ladderProgress
        leftDoorProgress = Mth.lerp(0.08f, leftDoorProgress, if (leftDoorOpen) 1f else 0f)
        rightDoorProgress = Mth.lerp(0.08f, rightDoorProgress, if (rightDoorOpen) 1f else 0f)
        ladderProgress = Mth.lerp(0.08f, ladderProgress, if (ladderOpen) 1f else 0f)

        // Missile rail wind-up: target is the extra (negative = up) delta
        // needed on top of turretXRot to reach -25° total, FROZEN at
        // whatever turretXRot was the instant wind-up started rather than
        // recomputed every tick — against a target under continuous
        // auto-aim, recomputing live meant the lerp was chasing a moving
        // goalpost and could take a very long time (or never) get close
        // enough to actually fire.
        //
        // Frozen HERE, off the rising edge of missileWindupActive (a
        // SYNCED field), not inside vehicleShoot() — that only ever runs
        // server-side (VehicleFireMessage's handler), so a value captured
        // there would never exist on the client and the rail would never
        // visually rise there at all (the actual missile would still fire
        // correctly, server-authoritative, just the client's rail stayed
        // put). Detecting the edge here means BOTH sides independently
        // capture their OWN locally-predicted turretXRot the instant they
        // observe the flag turn on — turretXRot is itself already
        // consistently predicted on both sides, so this needs no new
        // synced state.
        if (missileWindupActive && !missileWindupActivePrevTick) {
            missileWindupTargetExtra = minOf(0f, -25f - turretXRot)
            missileWindupTicks = 0
        }
        missileWindupActivePrevTick = missileWindupActive

        // Once windupActive is cleared (by the fire below, or if the
        // gunner's own aim reached 25° first) the target becomes 0 and
        // this same lerp smoothly lowers the rail back down to match the
        // gun again.
        missileExtraPitchO = missileExtraPitch
        val missileTargetExtra = if (missileWindupActive) missileWindupTargetExtra else 0f
        missileExtraPitch = Mth.lerp(0.15f, missileExtraPitch, missileTargetExtra)
        if (missileWindupActive) missileWindupTicks++
        // Hard timeout (1.5s) as a safety net on top of the frozen target —
        // fires anyway once it's close enough OR it's simply taken too
        // long, so a wind-up can never hang indefinitely.
        // Пуск разрешён, когда направляющая поднялась И пусковая довёрнута на
        // цель. Второе условие появилось вместе с захватом по отметке радара:
        // захват теперь можно взять с любого положения ствола, хоть по цели за
        // кормой, и башня после этого доворачивается несколько секунд. Раньше
        // выстрел в этот момент уходил ТУДА, КУДА СТВОЛ СМОТРИТ СЕЙЧАС — то
        // есть в сторону от цели и, поскольку точка схода задана относительно
        // башни, ещё и с той трубы, которая в этот момент оказалась не с той
        // стороны. Дальше ракета доворачивала на цель, но крюк получался
        // огромный. Таймаут ниже остался страховкой: если довернуть физически
        // не выходит (упор по углам, цель за спиной у ограниченного сектора),
        // пуск всё равно состоится, как и раньше.
        val railReady = missileExtraPitch <= missileTargetExtra + 0.3f
        if (missileWindupActive && !level().isClientSide &&
            ((railReady && missileLauncherOnTarget()) || missileWindupTicks > MISSILE_WINDUP_TIMEOUT_TICKS)
        ) {
            val living = pendingMissileLiving
            val uuid = pendingMissileUuid
            val targetPos = pendingMissileTargetPos
            missileWindupActive = false
            missileWindupTicks = 0
            pendingMissileLiving = null
            pendingMissileUuid = null
            pendingMissileTargetPos = null
            super.vehicleShoot(living, uuid, targetPos)
        }

        if (jacksDeployed && getNthEntity(2) == null) {
            // Gunner seat empty but still deployed: staged retract, same order
            // as deployment but reversed — swing the guns to the stowed
            // travel position (muzzle over the tailgate, i.e. yaw 180° from
            // the vehicle's nose, not pointing dead ahead) first, then let the
            // radar finish its current lap and stop, only then fold radar +
            // jacks — so nothing snaps or pops mid-animation.
            if (!turretHome) {
                val yawDiff = Mth.wrapDegrees(180f - turretYRot)
                turretYRotO = turretYRot
                turretXRotO = turretXRot
                turretYRot += Mth.clamp(yawDiff, -turretTurnYSpeed, turretTurnYSpeed)
                turretXRot -= Mth.clamp(turretXRot, -turretTurnXSpeed, turretTurnXSpeed)
            } else if (radarSpin != 0f) {
                radarSpin += RADAR_SPIN_SPEED
                if (radarSpin >= 360f) radarSpin = 0f
            } else {
                jacksDeployed = false
            }
        } else if (jacksProgress > 0.95f && jacksDeployed) {
            radarSpin += RADAR_SPIN_SPEED
            if (radarSpin > 360f) radarSpin -= 360f
        } else if (!jacksDeployed && getNthEntity(2) == null && !turretHome) {
            // No gunner ever sat down (jacksDeployed never went true, so the
            // staged-retract branch above never runs) but the turret is off
            // its home heading anyway — e.g. AA auto-aim swung it onto a
            // target. engineReady() requires turretHome, and with nothing
            // above to bring it back, driving would stay locked out forever
            // once the target was lost/out of range. Swing it home the same
            // way the staged retract does.
            val yawDiff = Mth.wrapDegrees(180f - turretYRot)
            turretYRotO = turretYRot
            turretXRotO = turretXRot
            turretYRot += Mth.clamp(yawDiff, -turretTurnYSpeed, turretTurnYSpeed)
            turretXRot -= Mth.clamp(turretXRot, -turretTurnXSpeed, turretTurnXSpeed)
        }

        jacksProgress = Mth.lerp(0.05f, jacksProgress, if (jacksDeployed) 1f else 0f)
        // Mth.lerp(0.05) is asymptotic — it creeps toward the target but never
        // reaches it, so a "deployed" radar would sit at jp≈0.99 forever, i.e.
        // the fold (Rx(jp*70-70)) keeps a ~0.5° residual tilt. Spinning in that
        // slightly tilted plane makes the dish (and its hitboxes) bob back and
        // forth. Snap to the exact endpoints so the deployed dish spins dead
        // flat and the stowed one folds fully home.
        if (jacksDeployed && jacksProgress > 0.96f) jacksProgress = 1f
        if (!jacksDeployed && jacksProgress < 0.02f) jacksProgress = 0f
    }

    companion object {
        // Скорость вращения антенны, град/тик. Полный оборот — 360/4.5 = 80
        // тиков (было 3 град/тик, то есть 120 тиков): в 1.5 раза быстрее.
        // Вместе с ней меняется и время затухания отметок на экране радара —
        // PantsirRadarScreen.FADE_TICKS, там отметка гаснет ровно за один
        // оборот развёртки.
        private const val RADAR_SPIN_SPEED = 4.5f

        // Скорость подтягивания камеры к цели, долей рассогласования за тик.
        // 0.5 — камера догоняет цель примерно за три тика: рывки синхронизации
        // размазываются, а отставание на глаз незаметно.
        private const val CAMERA_SMOOTHING = 0.5f

        // Допустимое рассогласование пусковой с направлением на цель в момент
        // схода, град.
        private const val MISSILE_LAUNCH_CONE_DEGREES = 8.0

        // Предохранитель на случай, если доворот невозможен (упор по углам,
        // цель ушла за сектор): пуск состоится и без выполнения условий. Три
        // секунды — компромисс: хватает на доворот башни почти из любого
        // положения, но зависанием «поднял направляющую и ждёт» не выглядит.
        private const val MISSILE_WINDUP_TIMEOUT_TICKS = 60

        private const val TRACK_TIMEOUT_TICKS = 10

        // Максимальное отклонение башни от направления на цель при мягком
        // захвате, град. Стрелок может увести направляющую на эту величину
        // вперёд/назад/вниз/вверх для ручного упреждения или выбора точки
        // прицеливания, не теряя сопровождения.
        private const val LOCK_OFFSET_MAX_DEG = 20f

        // Доля вертикального движения цели, которую башня отрабатывает
        // каждый тик (экспоненциальное приближение). 0.2 = 20% — цель
        // может резко снижаться, а башню не утащит вниз.
        private const val Y_TRACK_DAMP_FACTOR = 0.2
        private val FRONT_LEFT_PIVOT = Vec3(1.202, 0.6993, 1.9358)
        private val FRONT_RIGHT_PIVOT = Vec3(-1.202, 0.6993, 1.9358)
        private val CENTER_LEFT_PIVOT = Vec3(1.197, 0.6993, 0.0571)
        private val CENTER_RIGHT_PIVOT = Vec3(-1.197, 0.6993, 0.0571)
        private val REAR1_LEFT_PIVOT = Vec3(1.202, 0.6993, -3.8142)
        private val REAR1_RIGHT_PIVOT = Vec3(-1.202, 0.6993, -3.8142)
        private val REAR2_LEFT_PIVOT = Vec3(1.202, 0.6993, -5.3142)
        private val REAR2_RIGHT_PIVOT = Vec3(-1.202, 0.6993, -5.3142)
        private val STEERING_WHEEL_PIVOT = Vec3(0.7414, 2.4584, 2.815)
        private val LEFT_DOOR_PIVOT = Vec3(1.260, 2.359, 3.226)
        private val RIGHT_DOOR_PIVOT = Vec3(-1.260, 2.359, 3.226)
        // Raw model pivot [22.27924, 29.40432, 7.85459] / 16, Z negated —
        // same conversion verified exactly against the door bones above.
        private val LADDER_PIVOT = Vec3(1.3925, 1.8378, -0.4909)
        private val OPORY_PIVOT = Vec3(0.0, 0.814, -4.05)
        private val RADAR_PIVOT = Vec3(-0.0505, 0.8156, -1.2579)
        private val RADAR_SPIN_OFFSET = Vec3(0.0056, 0.6654, -0.3493)

        // How far (in blocks) the hull jacks up once the supports are fully
        // extended. Shared by the renderer (mesh) and bodyLift() (hitboxes) so
        // both rise together. Measured from the model directly (Blockbench
        // world AABBs, raw units): OPORY's foot pads sit at y=8.6 folded, and
        // the -0.667*16=-10.672 drop applied on full jackExtend puts them at
        // y=-2.072 with no lift — 2.102 raw units (0.1314 blocks) BELOW the
        // wheels' ground-contact height (y≈0.03). That's exactly how far the
        // hull needs to rise for the feet to land flush on the ground instead
        // of clipping through it or (if lifted too far, e.g. the old 0.5)
        // floating above it with nothing actually touching down.
        const val BODY_LIFT_BLOCKS = 0.1314f

        val JACKS_STATE: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(PantsirEntity::class.java, EntityDataSerializers.BYTE)
        val TRACKED_TARGET: EntityDataAccessor<String> =
            SynchedEntityData.defineId(PantsirEntity::class.java, EntityDataSerializers.STRING)
        val MISSILE_SEMI_AUTO: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(PantsirEntity::class.java, EntityDataSerializers.BOOLEAN)
    }
}
