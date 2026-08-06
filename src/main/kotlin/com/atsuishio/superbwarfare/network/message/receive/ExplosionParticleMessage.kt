package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.tools.ParticleTool
import com.atsuishio.superbwarfare.tools.localPlayer
import com.atsuishio.superbwarfare.tools.sendPacket
import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

@Serializable
data class ExplosionParticleMessage(
    val type: ParticleTool.ParticleType,
    val x: Double,
    val y: Double,
    val z: Double
) : ClientPacketPayload() {

    override fun PayloadContext.handler() {
        val player = localPlayer ?: return
        ParticleTool.spawnExplosionParticlesClient(type, player.level(), Vec3(x, y, z))
    }

    companion object {

        // Прежние 1024 блока обрезали как раз то, ради чего всё и делалось:
        // Панцирь бьёт на 1680, и подрыв на предельной дальности не долетал до
        // стрелявшего вообще — ракета просто пропадала без единой частицы.
        private const val PARTICLE_VIEW_DISTANCE = 2048.0

        /**
         * Sends the explosion particle message to all players within
         * [PARTICLE_VIEW_DISTANCE] blocks.
         */
        @JvmStatic
        fun sendToNearbyPlayers(
            level: ServerLevel,
            type: ParticleTool.ParticleType,
            pos: Vec3
        ) {
            // Перебираем список игроков уровня напрямую вместо запроса по
            // AABB: тот проходит все секции внутри коробки, а коробка тут
            // размером в две тысячи блоков. Игроков же на сервере единицы.
            val radiusSq = PARTICLE_VIEW_DISTANCE * PARTICLE_VIEW_DISTANCE
            for (player in level.players()) {
                if (player.distanceToSqr(pos) > radiusSq) continue
                player.sendPacket(ExplosionParticleMessage(type, pos.x, pos.y, pos.z))
            }
        }
    }
}
