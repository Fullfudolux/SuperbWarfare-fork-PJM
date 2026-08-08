package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.VectorTool
import com.mojang.math.Axis
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
import org.joml.Matrix4d
import org.joml.Quaterniond
import org.joml.Quaternionf
import java.util.function.Function

class KamazEntity(type: EntityType<KamazEntity>, world: Level) : VehicleEntity(type, world), BasicGeoVehicleEntity {
    init {
        registerWheelTransform("KamazWheelTurnL", FRONT_LEFT_PIVOT, true, true)
        registerWheelTransform("KamazWheelTurnR", FRONT_RIGHT_PIVOT, false, true)
        registerWheelTransform("KamazWheelCenterL", CENTER_LEFT_PIVOT, true, false)
        registerWheelTransform("KamazWheelCenterR", CENTER_RIGHT_PIVOT, false, false)
        registerWheelTransform("KamazWheelRearL", REAR_LEFT_PIVOT, true, false)
        registerWheelTransform("KamazWheelRearR", REAR_RIGHT_PIVOT, false, false)

        positionTransform["KamazSteeringWheel"] = Function { partialTicks -> steeringWheelTransform(partialTicks) }
        rotationTransform["KamazSteeringWheel"] = Function { partialTicks -> steeringWheelRotation(partialTicks) }

        positionTransform["KamazRearDoor"] = Function { partialTicks -> rearDoorTransform(partialTicks) }
        rotationTransform["KamazRearDoor"] = Function { partialTicks -> rearDoorRotation(partialTicks) }

        positionTransform["KamazLeftDoor"] = Function { partialTicks -> leftDoorTransform(partialTicks) }
        rotationTransform["KamazLeftDoor"] = Function { partialTicks -> leftDoorRotation(partialTicks) }

        positionTransform["KamazRightDoor"] = Function { partialTicks -> rightDoorTransform(partialTicks) }
        rotationTransform["KamazRightDoor"] = Function { partialTicks -> rightDoorRotation(partialTicks) }
    }

    private fun registerWheelTransform(name: String, pivot: Vec3, left: Boolean, steering: Boolean) {
        positionTransform[name] = Function { partialTicks -> wheelTransform(pivot, left, steering, partialTicks) }
        rotationTransform[name] = Function { partialTicks -> wheelRotation(left, steering, partialTicks) }
    }

    private fun wheelTransform(pivot: Vec3, left: Boolean, steering: Boolean, partialTicks: Float): Matrix4d {
        val transform = getVehicleTransform(partialTicks).translate(pivot.x, pivot.y, pivot.z)
        if (steering) transform.rotate(Axis.YP.rotation(rudderRot))
        return transform.rotate(Axis.XP.rotation(-1.5f * if (left) leftWheelRot else rightWheelRot))
    }

    private fun wheelRotation(left: Boolean, steering: Boolean, partialTicks: Float): Quaternionf {
        val rotation = VectorTool.combineRotations(partialTicks, this)
        if (steering) rotation.rotateY(rudderRot)
        return rotation.rotateX(-1.5f * if (left) leftWheelRot else rightWheelRot)
    }

    private fun steeringWheelTransform(partialTicks: Float): Matrix4d {
        // Base rotation (Rx(60°)) is here because generator skips bone rotation for Steringwhell
        val transform = getVehicleTransform(partialTicks)
            .translate(STEERING_WHEEL_PIVOT.x, STEERING_WHEEL_PIVOT.y, STEERING_WHEEL_PIVOT.z)
            .rotate(Axis.XP.rotationDegrees(60f))
        return transform.rotate(Axis.ZP.rotation(-12 * Mth.lerp(partialTicks, rudderRotO, rudderRot)))
    }

    private fun steeringWheelRotation(partialTicks: Float): Quaternionf {
        val rotation = VectorTool.combineRotations(partialTicks, this)
        rotation.rotateX(60f * Mth.DEG_TO_RAD)
        return rotation.rotateZ(-12 * Mth.lerp(partialTicks, rudderRotO, rudderRot))
    }

    // ── Door transforms (OBB follows door animation with partialTick interpolation) ─
    private fun rearDoorTransform(partialTicks: Float): Matrix4d {
        val progress = Mth.lerp(partialTicks, rearDoorProgressO, rearDoorProgress)
        val transform = getVehicleTransform(partialTicks)
            .translate(REAR_DOOR_PIVOT.x, REAR_DOOR_PIVOT.y, REAR_DOOR_PIVOT.z)
        return transform.rotate(Axis.XP.rotationDegrees(-170f * progress))
    }

    private fun rearDoorRotation(partialTicks: Float): Quaternionf {
        val progress = Mth.lerp(partialTicks, rearDoorProgressO, rearDoorProgress)
        val rotation = VectorTool.combineRotations(partialTicks, this)
        return rotation.rotateX(-170f * progress * Mth.DEG_TO_RAD)
    }

    private fun leftDoorTransform(partialTicks: Float): Matrix4d {
        val progress = Mth.lerp(partialTicks, leftDoorProgressO, leftDoorProgress)
        val transform = getVehicleTransform(partialTicks)
            .translate(LEFT_DOOR_PIVOT.x, LEFT_DOOR_PIVOT.y, LEFT_DOOR_PIVOT.z)
        return transform.rotate(Axis.YP.rotationDegrees(-70f * progress))
    }

    private fun leftDoorRotation(partialTicks: Float): Quaternionf {
        val progress = Mth.lerp(partialTicks, leftDoorProgressO, leftDoorProgress)
        val rotation = VectorTool.combineRotations(partialTicks, this)
        return rotation.rotateY(-70f * progress * Mth.DEG_TO_RAD)
    }

    private fun rightDoorTransform(partialTicks: Float): Matrix4d {
        val progress = Mth.lerp(partialTicks, rightDoorProgressO, rightDoorProgress)
        val transform = getVehicleTransform(partialTicks)
            .translate(RIGHT_DOOR_PIVOT.x, RIGHT_DOOR_PIVOT.y, RIGHT_DOOR_PIVOT.z)
        return transform.rotate(Axis.YP.rotationDegrees(70f * progress))
    }

    private fun rightDoorRotation(partialTicks: Float): Quaternionf {
        val progress = Mth.lerp(partialTicks, rightDoorProgressO, rightDoorProgress)
        val rotation = VectorTool.combineRotations(partialTicks, this)
        return rotation.rotateY(70f * progress * Mth.DEG_TO_RAD)
    }

    // ── Door state (synced) ────────────────────────────────────────────────────
    var rearDoorOpen: Boolean
        get() = entityData.get(DOOR_STATE).toInt() and 1 != 0
        set(value) { entityData.set(DOOR_STATE, ((entityData.get(DOOR_STATE).toInt() and 0b11111110) or if (value) 1 else 0).toByte()) }

    var leftDoorOpen: Boolean
        get() = entityData.get(DOOR_STATE).toInt() and 2 != 0
        set(value) { entityData.set(DOOR_STATE, ((entityData.get(DOOR_STATE).toInt() and 0b11111101) or if (value) 2 else 0).toByte()) }

    var rightDoorOpen: Boolean
        get() = entityData.get(DOOR_STATE).toInt() and 4 != 0
        set(value) { entityData.set(DOOR_STATE, ((entityData.get(DOOR_STATE).toInt() and 0b11111011) or if (value) 4 else 0).toByte()) }

    // ── Door animation progress (server-side lerp, with old value for interpolation) ─
    var rearDoorProgress = 0f
    var rearDoorProgressO = 0f
    var leftDoorProgress = 0f
    var leftDoorProgressO = 0f
    var rightDoorProgress = 0f
    var rightDoorProgressO = 0f

    // ── Open door when passenger dismounts (server-side only to prevent desync) ──
    override fun removePassenger(pPassenger: Entity) {
        val seatIndex = getSeatIndex(pPassenger)
        super.removePassenger(pPassenger)
        // Only on server — client syncs via EntityData
        if (!this.level().isClientSide) {
            when (seatIndex) {
                0 -> leftDoorOpen = true
                1 -> rightDoorOpen = true
                in 2..9 -> rearDoorOpen = true
            }
        }
    }

    // ── Block seat switching between cabin (0,1) and cargo (2+) ─────────────
    override fun changeSeat(entity: Entity, index: Int): Boolean {
        val currentSeat = getSeatIndex(entity)
        if (currentSeat >= 0) {
            // Block cross-group: cabin(0,1) <-> cargo(2+)
            val currentGroup = if (currentSeat < 2) 0 else 1
            val targetGroup = if (index < 2) 0 else 1
            if (currentGroup != targetGroup) return false
        }
        return super.changeSeat(entity, index)
    }

    // ── Right-click: OBB raycast for doors, board via open door ────────────────
    override fun interactAt(player: Player, pVec: Vec3, hand: InteractionHand): InteractionResult {
        if (hand == InteractionHand.MAIN_HAND) {
            if (this.passengers.contains(player)) return InteractionResult.PASS
            // Shift+click — не двери и не посадка: пропускаем в interact()
            // (лом, меню, бирка, ключ), как это уже делает PantsirEntity.
            if (player.isShiftKeyDown) return InteractionResult.PASS

            // Дальше — ТОЛЬКО сервер. Ванильный клиент вызывает interactAt
            // локально для предсказания, уже отправив пакет взаимодействия, то
            // есть тот же самый код исполнялся дважды, по одному разу на
            // сторону. Решение «сесть или нет» зависит от положения створок:
            // isOpen плюс расстояние от точки клика до центра OBB двери, а OBB
            // движется вместе с анимацией. Пока дверь открывается, клиент и
            // сервер считают её положение из своих значений прогресса и
            // расходятся на кадр-другой — достаточно, чтобы дистанция по одну
            // сторону влезла в порог, а по другую нет. Клиент делал
            // startRiding локально и считал игрока севшим, сервер уходил в
            // FAIL и не сажал никого: игрок «сидит» только у себя на экране.
            // Клик по створке страдал тем же — leftDoorOpen это synched data,
            // и клиентская запись всё равно затиралась ближайшей синхронизацией.
            if (this.level().isClientSide) return InteractionResult.CONSUME

            // Use OBB.getLookingObb to find which OBB the player clicked
            val hitObb = com.atsuishio.superbwarfare.tools.OBB.getLookingObb(player, 6.0)
            if (hitObb != null) {
                val obbs = this.getOBBs()
                val idx = obbs.indexOf(hitObb)
                if (idx >= 0 && idx < this.obb.size) {
                    val info = this.obb[idx]
                    val transform = info.transform
                    // Check if this OBB belongs to a door
                    when (transform) {
                        "KamazRearDoor" -> { rearDoorOpen = !rearDoorOpen; return InteractionResult.CONSUME }
                        "KamazLeftDoor" -> { leftDoorOpen = !leftDoorOpen; return InteractionResult.CONSUME }
                        "KamazRightDoor" -> { rightDoorOpen = !rightDoorOpen; return InteractionResult.CONSUME }
                    }
                }
            }

            // Not clicked on a door OBB → try to board via nearest open door.
            // Measured from the actual clicked point (pVec, relative to this
            // entity), not the player's feet — otherwise standing near one
            // open door while clicking a totally different part of the hull
            // could board you through whichever door your feet happened to
            // be closest to.
            val hitWorldPos = this.position().add(pVec)
            // Use ACTUAL OBB center positions (world space, moves with animation)
            val obbs = this.getOBBs()
            var nearestDoor = -1
            var nearestDist = Double.MAX_VALUE

            for (i in obbs.indices) {
                if (i >= this.obb.size) break
                val info = this.obb[i]
                val doorId = when (info.transform) {
                    "KamazLeftDoor" -> 1
                    "KamazRightDoor" -> 2
                    "KamazRearDoor" -> 0
                    else -> -1
                }
                if (doorId < 0) continue

                // Check if this door is open
                val isOpen = when (doorId) {
                    0 -> rearDoorOpen
                    1 -> leftDoorOpen
                    2 -> rightDoorOpen
                    else -> false
                }
                if (!isOpen) continue

                val obbWorld = obbs[i]
                val dist = hitWorldPos.distanceToSqr(Vec3(obbWorld.center.x, obbWorld.center.y, obbWorld.center.z))
                if (dist < nearestDist) {
                    nearestDist = dist
                    nearestDoor = doorId
                }
            }

            // Must click within 1.3 blocks of an open door's hitbox to board
            // through it (tightened from the old 4-block radius).
            if (nearestDoor < 0 || nearestDist > 1.69) return InteractionResult.FAIL

            val desiredSeat = when (nearestDoor) {
                1 -> 0  // LEFT door → driver seat
                2 -> 1  // RIGHT door → passenger seat
                0 -> {  // REAR door → first available cargo seat
                    var s = 2
                    while (s < this.maxPassengers) {
                        if (this.passengers.none { getSeatIndex(it) == s }) break
                        s++
                    }
                    s
                }
                else -> this.passengers.size
            }

            if (desiredSeat >= this.maxPassengers) return InteractionResult.FAIL
            if (this.passengers.any { getSeatIndex(it) == desiredSeat }) return InteractionResult.FAIL

            entityIndexOverride = java.util.function.Function { _ -> desiredSeat }
            val boarded = player.startRiding(this)
            entityIndexOverride = null

            if (boarded) {
                if (desiredSeat == 0) leftDoorOpen = false
                else if (desiredSeat == 1) rightDoorOpen = false
                return InteractionResult.CONSUME
            }
            return InteractionResult.PASS
        }
        return super.interactAt(player, pVec, hand)
    }

    override fun interact(player: Player, hand: InteractionHand): InteractionResult {
        // Non-shift clicks board through the door system (interactAt) only —
        // don't let the base class's walk-up-and-mount fallback bypass
        // doors. Shift+click (crowbar pack-away, menu, name tag, key)
        // delegates to super so it still works — same pattern PantsirEntity
        // uses. Returning PASS unconditionally (as this used to) blocked
        // onCrowbarInteract entirely, so the Kamaz could never be picked up.
        if (!player.isShiftKeyDown) return InteractionResult.PASS
        return super.interact(player, hand)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DOOR_STATE, 0.toByte())
    }

    override fun addAdditionalSaveData(compound: net.minecraft.nbt.CompoundTag) {
        super.addAdditionalSaveData(compound)
        compound.putByte("DoorState", entityData.get(DOOR_STATE))
    }

    override fun readAdditionalSaveData(compound: net.minecraft.nbt.CompoundTag) {
        super.readAdditionalSaveData(compound)
        entityData.set(DOOR_STATE, compound.getByte("DoorState"))
    }

    override fun baseTick() {
        super.baseTick()
        if (decoyInputDown) {
            horn()
        }
        // Store old progress for client-side partialTick interpolation
        rearDoorProgressO = rearDoorProgress
        leftDoorProgressO = leftDoorProgress
        rightDoorProgressO = rightDoorProgress
        // Lerp door progress
        rearDoorProgress = Mth.lerp(0.075f, rearDoorProgress, if (rearDoorOpen) 1f else 0f)
        leftDoorProgress = Mth.lerp(0.075f, leftDoorProgress, if (leftDoorOpen) 1f else 0f)
        rightDoorProgress = Mth.lerp(0.075f, rightDoorProgress, if (rightDoorOpen) 1f else 0f)
    }

    companion object {
        /**
         * Модель рисуется увеличенной (см. KamazRenderer.renderScale), а
         * координаты опорных точек ниже сняты с исходной. Растягиваем их тем
         * же множителем, иначе колёса, руль и створки крутились бы вокруг
         * точек, которых на увеличенной модели уже нет: хитбоксы дверей
         * разъезжались бы с самими дверями.
         */
        private const val MODEL_SCALE = 1.2

        private fun pivot(x: Double, y: Double, z: Double) =
            Vec3(x * MODEL_SCALE, y * MODEL_SCALE, z * MODEL_SCALE)

        private val FRONT_LEFT_PIVOT = pivot(1.018551, 0.594431, 2.499643)
        private val FRONT_RIGHT_PIVOT = pivot(-0.994887, 0.594431, 2.499643)
        private val CENTER_LEFT_PIVOT = pivot(1.013238, 0.594431, -0.953482)
        private val CENTER_RIGHT_PIVOT = pivot(-0.994887, 0.594431, -0.953482)
        private val REAR_LEFT_PIVOT = pivot(1.013238, 0.594431, -2.228482)
        private val REAR_RIGHT_PIVOT = pivot(-0.994887, 0.594431, -2.228482)
        private val STEERING_WHEEL_PIVOT = pivot(0.630, 2.090, 3.248)
        // Door pivots: original SBW convention (same as wheel pivots)
        private val REAR_DOOR_PIVOT = pivot(0.001, 1.461, -3.825)
        private val LEFT_DOOR_PIVOT = pivot(1.071, 2.005, 3.597)
        private val RIGHT_DOOR_PIVOT = pivot(-1.071, 2.005, 3.597)

        val DOOR_STATE: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(KamazEntity::class.java, EntityDataSerializers.BYTE)
    }
}
