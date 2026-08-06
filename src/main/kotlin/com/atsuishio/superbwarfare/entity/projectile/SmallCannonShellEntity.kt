package com.atsuishio.superbwarfare.entity.projectile

import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModDamageTypes.causeProjectileHitDamage
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.init.ModTags
import com.atsuishio.superbwarfare.network.message.receive.ClientIndicatorMessage
import com.atsuishio.superbwarfare.tools.CustomExplosion
import com.atsuishio.superbwarfare.tools.forceHurt
import com.atsuishio.superbwarfare.tools.sendPacketTo
import com.atsuishio.superbwarfare.world.phys.ExtendedEntityRayTraceResult
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.Item
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.entity.PartEntity

open class SmallCannonShellEntity(type: EntityType<out SmallCannonShellEntity>, level: Level) :
    FastThrowableProjectile(type, level) {
    private var aa = false

    // Set only by GunItem.shootBullet(), only when the shooter was seated
    // in a PantsirEntity — every other vehicle sharing this same shell type
    // (LAV-AD, Bradley, Mi-28, etc.) leaves this false.
    var firedFromPantsir = false

    init {
        this.noCulling = true
        this.damageValue = 40f
        this.explosionDamageValue = 80f
        this.explosionRadiusValue = 5f
    }

    override fun getDefaultItem(): Item {
        return ModItems.SMALL_SHELL_AP.get()
    }

    // Bonus direct-hit damage specifically for the Pantsir's own 2A38M
    // against actual aircraft/helicopters — its whole purpose is shooting
    // those down, so its shells should hurt them more than the flat base
    // damage alone implies. Boosts damageValue right before the base
    // class's onHitEntity() applies it.
    override fun onHitEntity(result: EntityHitResult) {
        if (firedFromPantsir && result is ExtendedEntityRayTraceResult) {
            var target = result.entity
            if (target is PartEntity<*>) target = target.parent
            if (target is VehicleEntity && (target.vehicleType == VehicleType.AIRPLANE || target.vehicleType == VehicleType.HELICOPTER)) {
                damageValue *= PANTSIR_AIRBORNE_DAMAGE_MULTIPLIER
            }
        }
        super.onHitEntity(result)
    }

    override fun afterHitEntity(result: EntityHitResult) {
        if (this.level() is ServerLevel) {
            if (this.tickCount > 0) {
                this.causeExplode(result.getLocation(), true)
            }
        }
        this.discard()
    }

    override fun afterHitBlock(result: BlockHitResult) {
        val resultPos = result.blockPos
        if (this.level() is ServerLevel) {
            val hardness = this.level().getBlockState(resultPos).block.defaultDestroyTime()
            if (hardness != -1f) {
                if (ExplosionConfig.EXPLOSION_DESTROY.get() && ExplosionConfig.EXTRA_EXPLOSION_EFFECT.get()) {
                    val destroy = Math.random() < (1.0 - (hardness / 50.0)).coerceIn(0.1, 1.0)
                    if (destroy) {
                        this.level().destroyBlock(resultPos, true)
                    }
                }
            }
            this.causeExplode(result.getLocation(), false)
        }
        this.discard()
    }

    private fun causeExplode(vec3: Vec3, hitEntity: Boolean) {
        CustomExplosion.Builder(this)
            .attacker(this.owner)
            .damage(explosionDamageValue)
            .radius(explosionRadiusValue)
            .position(vec3)
            .beast(this.isBeast())
            .destroyBlock { if (hitEntity) Explosion.BlockInteraction.KEEP else (if (ExplosionConfig.EXPLOSION_DESTROY.get()) Explosion.BlockInteraction.DESTROY else Explosion.BlockInteraction.KEEP) }
            .beast(this.isBeast())
            .explode()
    }

    override fun tick() {
        super.tick()
        if (aa) {
            crushProjectile(deltaMovement)
        }
        if (owner != null && distanceToSqr(owner!!) > MAX_RANGE_FROM_SHOOTER_SQR) {
            if (level() is ServerLevel) {
                causeExplode(position())
            }
            this.discard()
        }
    }

    open fun crushProjectile(velocity: Vec3) {
        if (this.level() !is ServerLevel) return

        val frontBox = boundingBox.inflate(PROXIMITY_RADIUS).expandTowards(velocity)
        val shooter = this.owner

        // Ищем среди ВСЕХ сущностей, а не только среди наследников Projectile.
        // Прежний поиск по Projectile отсекал ровно то, ради чего зенитный
        // снаряд и нужен: Shahed136 и стратегическая ракета pjmbasemod
        // наследуют обычный Entity, и очередь проходила сквозь них, не видя
        // их вообще.
        val target = level().getEntities(EntityTypeTest.forClass(Entity::class.java), frontBox) { it !== this }
            .asSequence()
            .filter { isProximityTarget(it) }
            .filter { it !== shooter && (shooter == null || it !== shooter.vehicle) }
            .filter { !isOwnOrdnance(it, shooter) }
            .minByOrNull { it.position().distanceTo(this.position()) }
            ?: return

        causeExplode(target.position(), false)

        // Тупой боеприпас без здоровья просто снимаем, всё остальное —
        // дроны, ракеты сторонних модов, сбиваемые снаряды — честно бьём
        // уроном, чтобы отработали их собственные смерть и взрыв, а живучая
        // цель не падала с одного случайного снаряда.
        if (target is Projectile && target !is DestroyableProjectile) {
            target.discard()
        } else {
            target.forceHurt(causeProjectileHitDamage(this.level().registryAccess(), this, shooter), damageValue)
        }

        if (shooter is ServerPlayer) {
            shooter.level().playSound(
                null, shooter.blockPosition(), ModSounds.INDICATION.get(), SoundSource.VOICE, 1f, 1f
            )
            sendPacketTo(shooter, ClientIndicatorMessage(0, 5))
        }

        this.discard()
    }

    /**
     * По кому зенитный снаряд срабатывает бесконтактно. Технику сюда
     * НАМЕРЕННО не включаем: у неё крупный габарит, снаряд попадает по ней
     * прямым попаданием, а оно даёт полный урон — бесконтактный подрыв рядом
     * заменил бы его слабым осколочным и обернулся бы понижением пушки.
     */
    private fun isProximityTarget(entity: Entity): Boolean {
        if (entity is SmallCannonShellEntity) return false
        if (entity is VehicleEntity) return false
        if (entity.bbWidth < 0.3 && entity.bbHeight < 0.3) return false

        return entity is Projectile ||
                entity.type.`is`(ModTags.EntityTypes.RADAR_CONTACT) ||
                entity.type.`is`(ModTags.EntityTypes.SMALL_DRONE)
    }

    /** Свой же боеприпас: собственную сходящую ракету сбивать не надо. */
    private fun isOwnOrdnance(entity: Entity, shooter: Entity?): Boolean {
        if (shooter == null) return false
        val entityOwner = (entity as? Projectile)?.owner ?: return false
        return entityOwner === shooter || (shooter.vehicle != null && entityOwner.vehicle === shooter.vehicle)
    }

    fun antiAir(antiAir: Boolean) {
        this.aa = antiAir
    }

    override fun isFastMoving(): Boolean {
        return false
    }

    companion object {
        // Tune to taste — applies only when firedFromPantsir is true.
        private const val PANTSIR_AIRBORNE_DAMAGE_MULTIPLIER = 1.5f

        // Радиус бесконтактного срабатывания. Прежние 0.5 блока по цели
        // размером с дрон на скорости 26 блоков/тик означали, что попасть
        // можно было только буквально в неё саму. Двух блоков хватает, чтобы
        // очередь работала как зенитная: чем она плотнее, тем выше шанс, что
        // хоть один снаряд пройдёт в радиусе.
        private const val PROXIMITY_RADIUS = 2.0

        // Жёсткая отсечка по удалению от стрелка. Раньше 1024 блока — она
        // срабатывала РАНЬШЕ времени жизни снаряда и молча ограничивала
        // дальность, сколько бы ProjectileLife ни стоял в данных.
        private const val MAX_RANGE_FROM_SHOOTER_SQR = 1500.0 * 1500.0
    }
}
