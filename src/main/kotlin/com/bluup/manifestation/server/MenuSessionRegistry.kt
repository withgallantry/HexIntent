package com.bluup.manifestation.server
import net.minecraft.nbt.CompoundTag
import at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus
import com.bluup.manifestation.Manifestation
import com.bluup.manifestation.common.ManifestationNetworking
import com.bluup.manifestation.common.menu.MenuPayload
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import java.util.UUID

object MenuSessionRegistry {
    private const val SESSION_TTL_MS = 45_000L

    data class CircleContext(val dimensionId: String, val impetusPos: BlockPos)


    data class DispatchContext(
        val hand: InteractionHand,
        val source: MenuPayload.DispatchSource,
        val stack: List<CompoundTag>,
        val ravenmind: CompoundTag?
    )

    data class ResolveResult(
        val dispatch: DispatchContext?,
        val rejectMessage: Component?
    )

    private data class Session(
        val token: UUID,
        val hand: InteractionHand,
        val source: MenuPayload.DispatchSource,
        val expiresAtMs: Long,
        val circleContext: CircleContext?,
        val stack: List<CompoundTag>,
        val ravenmind: CompoundTag?
    )

    private val byPlayer: MutableMap<UUID, Session> = mutableMapOf()

    @JvmStatic
    fun clearForPlayer(playerId: UUID) {
        byPlayer.remove(playerId)
    }

    @JvmStatic
    fun attachSession(
        player: ServerPlayer,
        payload: MenuPayload,
        circleContext: CircleContext?,
        stack: List<CompoundTag>,
        ravenmind: CompoundTag?
    ): MenuPayload {
        val now = System.currentTimeMillis()
        pruneExpired(now)

        val token = UUID.randomUUID()
        val session = Session(
            token = token,
            hand = payload.hand(),
            source = payload.dispatchSource(),
            expiresAtMs = now + SESSION_TTL_MS,
            circleContext = circleContext,
            stack = stack.map { it.copy() },
            ravenmind = ravenmind
        )
        byPlayer[player.uuid] = session
        return payload.withSessionToken(token)
    }

    @JvmStatic
    fun resolveAndConsume(
        player: ServerPlayer,
        token: UUID,
        clientHand: InteractionHand,
        clientSource: MenuPayload.DispatchSource
    ): ResolveResult {
        val now = System.currentTimeMillis()
        val session = byPlayer.remove(player.uuid)
            ?: return ResolveResult(null, Component.translatable("message.manifestation.menu_expired"))

        if (session.expiresAtMs < now) {
            return ResolveResult(null, Component.translatable("message.manifestation.menu_expired"))
        }

        if (session.token != token) {
            return ResolveResult(null, Component.translatable("message.manifestation.menu_expired"))
        }

        if (session.source == MenuPayload.DispatchSource.CIRCLE && !isCircleContextValid(player, session.circleContext)) {
            return ResolveResult(null, Component.translatable("message.manifestation.circle_disrupted"))
        }

        if (session.hand != clientHand || session.source != clientSource) {
            Manifestation.LOGGER.warn(
                "MenuSessionRegistry: client dispatch metadata mismatch for {} (client hand/source {} {}, server {} {})",
                player.name.string,
                clientHand,
                clientSource,
                session.hand,
                session.source
            )
        }

        return ResolveResult(
            DispatchContext(
                session.hand,
                session.source,
                session.stack.map { it.copy() },
                session.ravenmind?.copy()
            ),
            null
        )
    }

    @JvmStatic
    fun invalidateCircleAt(level: ServerLevel, pos: BlockPos) {
        val dimId = level.dimension().location().toString()
        val message = Component.translatable("message.manifestation.circle_disrupted")

        val toRemove = mutableListOf<UUID>()
        for ((playerId, session) in byPlayer) {
            val circle = session.circleContext ?: continue
            if (circle.dimensionId != dimId || circle.impetusPos != pos) {
                continue
            }

            val player = level.server.playerList.getPlayer(playerId)
            if (player != null) {
                sendMenuInvalidated(player, session.token, message)
            }
            toRemove.add(playerId)
        }

        for (playerId in toRemove) {
            byPlayer.remove(playerId)
        }
    }

    private fun isCircleContextValid(player: ServerPlayer, circleContext: CircleContext?): Boolean {
        if (circleContext == null) {
            return false
        }

        val dimLoc = net.minecraft.resources.ResourceLocation.tryParse(circleContext.dimensionId) ?: return false

        val level = player.server.getLevel(
            net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                dimLoc
            )
        ) ?: return false

        if (!level.hasChunkAt(circleContext.impetusPos)) {
            return false
        }

        val blockEntity = level.getBlockEntity(circleContext.impetusPos)
        return blockEntity is BlockEntityAbstractImpetus
    }

    private fun sendMenuInvalidated(player: ServerPlayer, token: UUID, message: Component) {
        val buf = PacketByteBufs.create()
        buf.writeUUID(token)
        buf.writeComponent(message)
        ServerPlayNetworking.send(player, ManifestationNetworking.MENU_INVALIDATE_S2C, buf)
    }

    private fun pruneExpired(nowMs: Long) {
        val it = byPlayer.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.expiresAtMs < nowMs) {
                it.remove()
            }
        }
    }
}
