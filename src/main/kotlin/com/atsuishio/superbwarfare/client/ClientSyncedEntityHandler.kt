package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.config.server.MiscConfig
import com.atsuishio.superbwarfare.network.message.receive.EntitySyncMessage.SyncedEntity
import com.atsuishio.superbwarfare.network.message.receive.PlayerInfoSyncMessage.SyncedPlayerInfo
import com.atsuishio.superbwarfare.tools.mc
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object ClientSyncedEntityHandler {
    @JvmField
    val SYNCED_ENTITIES = ConcurrentHashMap<SyncedKey, ClientSyncedEntity>()

    @JvmField
    val SYNCED_PLAYERS = ConcurrentHashMap<SyncedPlayerKey, ClientSyncedPlayer>()

    data class SyncedKey(val dim: ResourceLocation, val id: Int, val friendly: Boolean)

    data class ClientSyncedEntity(val entity: Entity, val timeStamp: Long)

    data class SyncedPlayerKey(val dim: ResourceLocation, val uuid: UUID)

    data class ClientSyncedPlayer(
        val timeStamp: Long,
        val uuid: UUID,
        val pos: Vec3,
        val name: String,
        val onVehicle: Boolean,
        val isDriver: Boolean
    )

    fun sync(dim: ResourceLocation, list: List<SyncedEntity>, friendly: Boolean) {
        val level = mc.level ?: return
        val time = System.currentTimeMillis()
        for (syncedEntity in list) {
            val key = SyncedKey(dim, syncedEntity.id, friendly)
            val existedEntity = SYNCED_ENTITIES[key]
            var entity: Entity
            if (existedEntity != null) {
                entity = existedEntity.entity
            } else {
                val type = BuiltInRegistries.ENTITY_TYPE.get(syncedEntity.type)
                entity = type.create(level) ?: continue
                val tag = syncedEntity.tag as? CompoundTag ?: continue
                entity.load(tag)
                entity.id = syncedEntity.id
            }

            val pos = syncedEntity.pos
            // xo/yo/zo — позиция ПРОШЛОГО тика, из неё рендер интерполирует
            // текущий кадр. Раньше сюда клали новую позицию, то есть призрак
            // телепортировался: между пакетами (sync_entity_interval, по
            // умолчанию 10 тиков) он стоял на месте, а потом прыгал. Для
            // быстрой цели это ровно та самая рамка захвата, которая замирает
            // ПОЗАДИ цели. Теперь оставляем реальную предыдущую позицию, и
            // поправка от сервера доезжает плавно за один тик.
            if (existedEntity != null) {
                entity.xo = entity.x
                entity.yo = entity.y
                entity.zo = entity.z
            } else {
                entity.xo = pos.x
                entity.yo = pos.y
                entity.zo = pos.z
            }
            entity.setPos(syncedEntity.pos)
            entity.deltaMovement = syncedEntity.motion
            SYNCED_ENTITIES[key] = ClientSyncedEntity(entity, time)
        }
    }

    /**
     * Экстраполяция призраков между пакетами синхронизации. Сами они не тикают
     * (в мир не добавлены), поэтому без этого их позиция обновляется только
     * раз в sync_entity_interval — цель едет, а отметка на радаре и рамка
     * захвата стоят. Ведём их по последней известной скорости; следующий пакет
     * работает как коррекция счисления.
     */
    fun advanceGhosts() {
        for (synced in SYNCED_ENTITIES.values) {
            val entity = synced.entity
            entity.xo = entity.x
            entity.yo = entity.y
            entity.zo = entity.z

            val motion = entity.deltaMovement
            if (motion.lengthSqr() < 1.0E-6) continue
            entity.setPos(entity.x + motion.x, entity.y + motion.y, entity.z + motion.z)
        }
    }

    fun syncPlayerInfo(dim: ResourceLocation, list: List<SyncedPlayerInfo>) {
        if (mc.level == null) return
        val time = System.currentTimeMillis()
        for (info in list) {
            val key = SyncedPlayerKey(dim, info.uuid)
            SYNCED_PLAYERS[key] =
                ClientSyncedPlayer(time, info.uuid, info.pos, info.name, info.onVehicle, info.isDriver)
        }
    }

    fun clean() {
        val tick = System.currentTimeMillis()
        SYNCED_ENTITIES.values.removeIf { tick - it.timeStamp > MiscConfig.CLIENT_SYNC_EXPIRE_TIME.get() }
        SYNCED_PLAYERS.values.removeIf { tick - it.timeStamp > MiscConfig.CLIENT_SYNC_EXPIRE_TIME.get() }
    }

    @JvmStatic
    fun getSyncedFriendlyEntities(level: Level): List<Entity> {
        return SYNCED_ENTITIES.filterKeys { it.dim == level.dimension().location() && it.friendly }
            .map { it.value.entity }
    }

    @JvmStatic
    fun getSyncedHostileEntities(level: Level): List<Entity> {
        return SYNCED_ENTITIES.filterKeys { it.dim == level.dimension().location() && !it.friendly }
            .map { it.value.entity }
    }

    @JvmStatic
    fun getSyncedEntities(level: Level): List<Entity> {
        return SYNCED_ENTITIES.filterKeys { it.dim == level.dimension().location() }.map { it.value.entity }
    }

    @JvmStatic
    fun getSyncedPlayerInfo(level: Level): List<ClientSyncedPlayer> {
        return SYNCED_PLAYERS.filterKeys { it.dim == level.dimension().location() }.map { it.value }
    }
}