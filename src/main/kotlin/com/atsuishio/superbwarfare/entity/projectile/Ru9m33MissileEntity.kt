package com.atsuishio.superbwarfare.entity.projectile

import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level

/**
 * PJM: ЗУР 9М33М3 комплекса 9К33 «Оса-АКМ».
 *
 * Отличается от 9М336 только маневренностью: ракета 1970-х с радиокомандным наведением
 * разворачивается заметно вяло, поэтому по манёвренной цели её проще сорвать с траектории.
 */
open class Ru9m33MissileEntity(type: EntityType<out Ru9m33MissileEntity>, level: Level) :
    Ru9m336MissileEntity(type, level) {

    // вдвое медленнее выходит на упреждение и вдвое ниже потолок разворота
    override fun turnRate(): Float = ((tickCount - 1) * 0.25f).coerceIn(0f, 7f)
}
