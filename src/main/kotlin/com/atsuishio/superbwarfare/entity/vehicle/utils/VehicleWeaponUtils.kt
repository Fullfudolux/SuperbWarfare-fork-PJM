package com.atsuishio.superbwarfare.entity.vehicle.utils

import com.atsuishio.superbwarfare.Mod.Companion.queueServerWork
import com.atsuishio.superbwarfare.entity.projectile.FlareDecoyEntity
import com.atsuishio.superbwarfare.entity.projectile.SmokeDecoyEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils.getXRotFromVector
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils.getYRotFromVector
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils.transformPosition
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.tools.EntityFindUtil.findEntity
import com.atsuishio.superbwarfare.tools.RangeTool.calculateFiringSolution
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import com.atsuishio.superbwarfare.tools.angleTo
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * 用于处理载具武器瞄准或其他战斗相关方法的工具类
 */
object VehicleWeaponUtils {
    /**
     * 根据操控者调整载具炮塔角度
     * 
     * @param vehicle 载具
     */
    @JvmStatic
    fun adjustTurretAngle(vehicle: VehicleEntity) {
        if (vehicle.isWreck) return
        val driver = vehicle.getNthEntity(vehicle.turretControllerIndex)
        val pos = vehicle.barrelPosition
        if (driver != null && pos != null) {
            val aimPos = vehicle.boundingBox.center.add(driver.getViewVector(1f).scale(512.0))

            val transform = vehicle.getTurretTransform(1f)
            val worldPosition = transformPosition(transform, pos.x, pos.y, pos.z)

            val aimVec = Vec3(worldPosition.x, worldPosition.y, worldPosition.z).vectorTo(aimPos)
            turretAutoAimFromVector(vehicle, aimVec)
        }
    }

    /**
     * 根据方向向量，使炮塔自动瞄准
     * 
     * @param shootVec 需要让炮塔以这个角度发射的向量
     */
    @JvmStatic
    fun turretAutoAimFromVector(vehicle: VehicleEntity, shootVec: Vec3) {
        if (vehicle.isWreck) return
        var ySpeed = vehicle.turretTurnYSpeed
        var xSpeed = vehicle.turretTurnXSpeed

        val barrelVector = vehicle.getBarrelVector(1f)
        val diffY = Mth.wrapDegrees(-getYRotFromVector(shootVec) + getYRotFromVector(barrelVector)).toFloat()
        val diffX = Mth.wrapDegrees(-getXRotFromVector(shootVec) + getXRotFromVector(barrelVector)).toFloat()

        if (vehicle.getEntityData().get(VehicleEntity.TURRET_DAMAGED)) {
            ySpeed *= 0.2f
            xSpeed *= 0.2f
        }

        val min = -ySpeed
        val max = ySpeed

        vehicle.turretXRot = Mth.clamp(
            vehicle.turretXRot + Mth.clamp(0.75f * diffX, -xSpeed, xSpeed),
            -vehicle.turretMaxPitch,
            -vehicle.turretMinPitch
        )
        val newYaw = vehicle.turretYRot - Mth.clamp(1f * diffY, min, max)
        // A turret whose configured range spans the full circle (e.g. -180..180)
        // must wrap through the ±180 seam instead of clamping there — clamping
        // hard-stops rotation the instant it reaches -180 (or 180) and can never
        // come back around from the other side, even though +180 and -180 are
        // the same physical angle. Turrets with a genuine partial traverse limit
        // still clamp as before.
        vehicle.turretYRot = if (vehicle.turretMaxYaw - vehicle.turretMinYaw >= 359f) {
            val wrapped = Mth.wrapDegrees(newYaw)
            // turretYRotO was snapshotted before this update and is still on the
            // pre-wrap side (e.g. 179 while the new value becomes -179) — render
            // and OBB rotation both lerp between turretYRotO and turretYRot, and
            // a naive lerp across that 358° gap sweeps the long way around in a
            // single frame instead of the actual ~2° step. Shift turretYRotO onto
            // the same loop as the new value so the interpolation stays short.
            if (Mth.abs(wrapped - vehicle.turretYRotO) > 180f) {
                vehicle.turretYRotO += if (wrapped > vehicle.turretYRotO) 360f else -360f
            }
            wrapped
        } else {
            Mth.clamp(newYaw, -vehicle.turretMaxYaw, -vehicle.turretMinYaw)
        }

        vehicle.turretTurnSound(vehicle.turretXRot - vehicle.turretXRotO, vehicle.turretYRot - vehicle.turretYRotO, 0.95f)

        vehicle.turretYRotLock = Mth.clamp(-1f * diffY, min, max)
    }

    /**
     * 根据UUID，使炮塔自动瞄准
     * 
     * @param uuid    目标的UUID字符串
     * @param pLiving 操控载具的实体
     */
    @JvmStatic
    fun turretAutoAimFromUuid(vehicle: VehicleEntity, uuid: String?, pLiving: LivingEntity) {
        if (vehicle.isWreck) return
        var target = findEntity(vehicle.level(), uuid) ?: return

        if (target.vehicle != null) {
            target = target.vehicle!!
        }

        val targetPos = target.boundingBox.center
        var targetVel = target.deltaMovement

        if (target is LivingEntity) {
            val gravity = target.getAttributeValue(Attributes.GRAVITY)
            targetVel = targetVel.add(0.0, gravity, 0.0)
        }

        if (target is Player) {
            targetVel = targetVel.multiply(2.0, 1.0, 2.0)
        }

        val launchPos = vehicle.getShootPos(pLiving, 1f).subtract(
            vehicle.getShootVec(pLiving, 1f).scale(vehicle.getShootPos(pLiving, 1f).distanceTo(pLiving.position()))
        )
        val muzzleVelocity = vehicle.getProjectileVelocity(pLiving).toDouble()
        val gravity = vehicle.getProjectileGravity(pLiving).toDouble()

        val toTarget = targetPos.subtract(launchPos)
        val targetVec = calculateFiringSolution(launchPos, targetPos, targetVel, muzzleVelocity, gravity)
        vehicle.turretAutoAimFromVector(
            if (isSolutionSane(targetVec, muzzleVelocity, toTarget)) targetVec
            else pursuitVector(launchPos, targetPos, muzzleVelocity, gravity)
        )
    }

    /**
     * Годится ли решение задачи встречи. Оно ищется численно (Ньютон) как
     * корень уравнения «снаряд и цель окажутся в одной точке», и корень
     * существует далеко не всегда: против цели быстрее самого снаряда встречи
     * нет вовсе. Итерация в этом случае всё равно возвращает какое-то число, но
     * упреждение получается абсурдным — башню уводит вдоль вектора движения
     * цели куда-то за горизонт. Ровно это и происходило при захвате быстрой
     * ракеты: у ЗУР 57Э6 стартовая скорость 3.6 бл/тик, а маршевая у цели —
     * втрое выше.
     *
     * Проверок две: длина вектора (у настоящего решения она равна дульной
     * скорости — именно это уравнение и решалось) и направление относительно
     * цели, см. ниже.
     */
    @JvmStatic
    fun isSolutionSane(solution: Vec3, muzzleVelocity: Double, toTarget: Vec3): Boolean {
        if (muzzleVelocity <= 0.0) return false
        val speed = solution.length()
        if (!speed.isFinite()) return false
        // Допуск ужат с половины до четверти: у быстрой цели скорость сама по
        // себе близка к дульной, и посторонний корень легко проходил прежний
        // порог, а вектор при этом получался направленным ВДОЛЬ ДВИЖЕНИЯ ЦЕЛИ,
        // а не в неё.
        if (abs(speed - muzzleVelocity) > muzzleVelocity * 0.25) return false

        // Главная проверка — геометрическая. Упреждение по самой своей природе
        // ограничено: sin(угла упреждения) = (скорость цели / скорость снаряда)
        // * sin(угла между ними), и для достижимой цели, то есть более
        // медленной, чем снаряд, этот угол строго меньше прямого. Решение,
        // требующее отвернуть от цели дальше, — не упреждение, а посторонний
        // корень. Именно он разворачивал башню в противоположную сторону от
        // цели, где она и застревала.
        return solution.angleTo(toTarget) < MAX_LEAD_ANGLE_DEGREES
    }

    /** Предельный физически осмысленный угол упреждения, град. */
    private const val MAX_LEAD_ANGLE_DEGREES = 75.0

    /**
     * Наведение прямо в цель, без упреждения, но с поправкой на падение снаряда
     * за время полёта по прямой. Запасной вариант, когда встречи не существует.
     * Для ЗУР это к тому же единственно верное поведение: она наводится сама,
     * командами с земли, и уводить пусковую в точку встречи незачем.
     */
    private fun pursuitVector(launchPos: Vec3, targetPos: Vec3, muzzleVelocity: Double, gravity: Double): Vec3 {
        val direct = targetPos.subtract(launchPos)
        val distance = direct.length()
        if (distance < 1.0E-4 || muzzleVelocity <= 0.0) return direct

        val flightTime = distance / muzzleVelocity
        return Vec3(
            direct.x / flightTime,
            (direct.y + 0.5 * gravity * flightTime * flightTime) / flightTime,
            direct.z / flightTime
        )
    }

    /**
     * 发射烟雾诱饵
     * 
     * @param vehicle 载具
     * @param vec3    发射方向
     */
    @JvmStatic
    fun releaseSmokeDecoy(vehicle: VehicleEntity, vec3: Vec3) {
        if (vehicle.decoyInputDown) {
            if (vehicle.decoyReady && vehicle.level() is ServerLevel) {
                for (i in 0..7) {
                    val smokeDecoyEntity = SmokeDecoyEntity(vehicle.level())
                    // PJM: вылет над крышей башни, с наклоном вверх — гранаты выпрыгивают
                    // из мортирок и летят по дуге, а не выкатываются из-под башни
                    smokeDecoyEntity.setPos(vehicle.x, vehicle.y + vehicle.bbHeight + 0.75, vehicle.z)
                    // PJM: завеса веером ±35° перед башней — дым встаёт там, куда повёрнута башня,
                    // а не полукругом вокруг корпуса; igniteTime — фолбэк, обычно шашка
                    // срабатывает при падении на землю (~10 блоков впереди)
                    smokeDecoyEntity.igniteTime = 30
                    smokeDecoyEntity.decoyShoot(
                        vehicle,
                        vec3.yRot((-35f + 10f * i) * Mth.DEG_TO_RAD).add(0.0, 0.3, 0.0),
                        1.5f, 8f
                    )
                    vehicle.level().addFreshEntity(smokeDecoyEntity)
                }

                vehicle.level()
                    .playSound(null, vehicle, ModSounds.DECOY_RELEASE.get(), vehicle.soundSource, 1f, 1f)
                vehicle.decoyReloadCoolDown = 500
                vehicle.decoyReady = false
            }
            vehicle.decoyInputDown = false
        }

        if (!vehicle.decoyReady && vehicle.decoyReloadCoolDown == 0 && vehicle.level() is ServerLevel) {
            vehicle.decoyReady = true
            vehicle.level().playSound(null, vehicle, ModSounds.DECOY_RELOAD.get(), vehicle.soundSource, 1f, 1f)
            vehicle.decoyReloadCoolDown = 500
        }
    }

    /**
     * 发射热诱弹
     * 
     * @param vehicle 载具
     */
    @JvmStatic
    fun releaseDecoy(vehicle: VehicleEntity) {
        if (vehicle.decoyInputDown) {
            if (vehicle.decoyReady && vehicle.level() is ServerLevel) {
                var i = 0
                while (i < 54) {
                    val finalI = i
                    queueServerWork(i) {
                        val transform = vehicle.getVehicleTransform(1f)
                        val worldPositionO = transformPosition(transform, 0.0, 0.0, 0.0)
                        val worldPosition = transformPosition(transform, 1.0, -0.2, 0.6)
                        val worldPosition2 = transformPosition(transform, -1.0, -0.2, 0.6)

                        val shootVecO = Vec3(worldPositionO.x, worldPositionO.y, worldPositionO.z)
                        val shootVec1 = Vec3(worldPosition.x, worldPosition.y, worldPosition.z)
                        val shootVec2 = Vec3(worldPosition2.x, worldPosition2.y, worldPosition2.z)

                        shootDecoy(vehicle, shootVecO.vectorTo(shootVec1).normalize(), finalI == 6)
                        shootDecoy(vehicle, shootVecO.vectorTo(shootVec2).normalize(), finalI == 6)
                    }
                    i += 6
                }

                vehicle.decoyReloadCoolDown = 400
                vehicle.decoyReady = false
            }
            vehicle.decoyInputDown = false
        }
        if (!vehicle.decoyReady && vehicle.decoyReloadCoolDown == 0 && vehicle.level() is ServerLevel) {
            vehicle.decoyReady = true
            vehicle.level().playSound(null, vehicle, ModSounds.DECOY_RELOAD.get(), vehicle.soundSource, 1f, 1f)
            vehicle.decoyReloadCoolDown = 400
        }
    }

    @JvmStatic
    fun shootDecoy(vehicle: VehicleEntity, shootVec: Vec3, first: Boolean) {
        val flareDecoyEntity = FlareDecoyEntity(vehicle.level())

        flareDecoyEntity.setPos(
            vehicle.x + vehicle.deltaMovement.x,
            vehicle.y + 0.5 + vehicle.deltaMovement.y,
            vehicle.z + vehicle.deltaMovement.z
        )
        flareDecoyEntity.decoyShoot(vehicle, shootVec, (vehicle.deltaMovement.length() * 0.3f + 0.7).toFloat(), 8f)

        vehicle.level().addFreshEntity(flareDecoyEntity)
        vehicle.level().playSound(
            null,
            vehicle,
            if (first) ModSounds.DECOY_RELEASE_FIRST.get() else ModSounds.DECOY_RELEASE.get(),
            vehicle.soundSource,
            2f,
            1f
        )
    }

    /**
     * 根据UUID，使乘客位武器自动瞄准
     *
     * @param vehicle 载具
     * @param uuid    目标的UUID字符串
     * @param pLiving 操控载具的实体
     */
    @JvmStatic
    fun passengerWeaponAutoAimFormUuid(vehicle: VehicleEntity, uuid: String?, pLiving: LivingEntity) {
        var target = findEntity(vehicle.level(), uuid)
        if (target != null) {
            if (target.vehicle != null) {
                target = target.vehicle
            }

            val targetPos = target!!.boundingBox.center
            var targetVel = target.deltaMovement

            if (target is LivingEntity) {
                val gravity = target.getAttributeValue(Attributes.GRAVITY)
                targetVel = targetVel.add(0.0, gravity, 0.0)
            }

            if (target is Player) {
                targetVel = targetVel.multiply(2.0, 1.0, 2.0)
            }

            val targetVec = calculateFiringSolution(
                vehicle.getShootPos(pLiving, 1f).subtract(
                    vehicle.getShootVec(pLiving, 1f).scale(
                        vehicle.getShootPos(pLiving, 1f).distanceTo(pLiving.position())
                    )
                ),
                targetPos,
                targetVel,
                vehicle.getProjectileVelocity(pLiving).toDouble(),
                vehicle.getProjectileGravity(pLiving).toDouble()
            )
            passengerWeaponAutoAimFormVector(vehicle, targetVec)
        }
    }

    /**
     * 根据方向向量，使乘客位武器自动瞄准
     *
     * @param vehicle  载具
     * @param shootVec 需要让武器站以这个角度发射的向量
     */
    @JvmStatic
    fun passengerWeaponAutoAimFormVector(vehicle: VehicleEntity, shootVec: Vec3) {
        val ySpeed = vehicle.passengerWeaponYSpeed
        val xSpeed = vehicle.passengerWeaponXSpeed
        val diffY = Mth.wrapDegrees(
            -getYRotFromVector(shootVec) + getYRotFromVector(
                vehicle.getPassengerWeaponStationVector(1f)
            )
        ).toFloat()
        val diffX = Mth.wrapDegrees(
            -getXRotFromVector(shootVec) + getXRotFromVector(
                vehicle.getPassengerWeaponStationVector(1f)
            )
        ).toFloat()

        vehicle.gunXRot = Mth.clamp(
            vehicle.gunXRot + Mth.clamp(diffX, -xSpeed, xSpeed),
            -vehicle.passengerWeaponMaxPitch,
            -vehicle.passengerWeaponMinPitch
        )
        vehicle.gunYRot = Mth.clamp(
            vehicle.gunYRot - Mth.clamp(diffY, -ySpeed, ySpeed),
            -vehicle.passengerWeaponMaxYaw,
            -vehicle.passengerWeaponMinYaw
        )

        vehicle.turretTurnSound(vehicle.gunXRot - vehicle.gunXRotO, vehicle.gunYRot - vehicle.gunYRotO, 0.95f)
    }

    /**
     * 根据操控者调整乘客武器站角度
     *
     * @param vehicle 载具
     */
    @JvmStatic
    fun adjustWeaponControllerAngle(vehicle: VehicleEntity) {
        val entity = vehicle.getNthEntity(vehicle.passengerWeaponStationControllerIndex)
        val pos = vehicle.passengerWeaponStationBarrelPosition
        if (entity != null && pos != null) {
            val aimPos = vehicle.boundingBox.center.add(entity.getViewVector(1f).scale(512.0))

            val transform = vehicle.getGunTransform(1f)
            val worldPosition = transformPosition(transform, pos.x, pos.y, pos.z)

            val aimVec = Vec3(worldPosition.x, worldPosition.y, worldPosition.z).vectorTo(aimPos)
            passengerWeaponAutoAimFormVector(vehicle, aimVec)
        }

        if (entity == null) {
            vehicle.gunYRot += vehicle.turretYRotLock
        }
    }
}
