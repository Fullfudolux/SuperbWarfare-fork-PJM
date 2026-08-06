package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.tools.ParticleTool
import com.atsuishio.superbwarfare.tools.localPlayer
import com.atsuishio.superbwarfare.tools.sendPacket
import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * Падение обломков сбитой воздушной цели. Отдельный пакет от взрыва: взрыв
 * происходит там, где сработал взрыватель, а обломки сыплются оттуда, где была
 * сама цель, и только если она действительно уничтожена.
 */
@Serializable
data class WreckageParticleMessage(
    val x: Double,
    val y: Double,
    val z: Double
) : ClientPacketPayload() {

    override fun PayloadContext.handler() {
        val player = localPlayer ?: return
        ParticleTool.spawnWreckageFallParticlesClient(player.level(), Vec3(x, y, z))
    }

    companion object {
        // Та же дальность, что и у взрывов: сбитие на предельной дистанции
        // должно быть видно тому, кто стрелял.
        private const val PARTICLE_VIEW_DISTANCE = 2048.0

        @JvmStatic
        fun sendToNearbyPlayers(level: ServerLevel, pos: Vec3) {
            val radiusSq = PARTICLE_VIEW_DISTANCE * PARTICLE_VIEW_DISTANCE
            for (player in level.players()) {
                if (player.distanceToSqr(pos) > radiusSq) continue
                player.sendPacket(WreckageParticleMessage(pos.x, pos.y, pos.z))
            }
        }
    }
}
