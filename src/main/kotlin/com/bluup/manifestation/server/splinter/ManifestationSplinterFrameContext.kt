package com.bluup.manifestation.server.splinter

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

object ManifestationSplinterFrameContext {
    data class Context(
        val splinterId: UUID,
        val ownerId: UUID,
        val ownerPlayer: ServerPlayer,
        val level: ServerLevel,
        val state: SplinterStateStore,
        val debugTelemetry: Boolean,
        val safeInlineCap: Int
    )

    private val current: ThreadLocal<Context?> = ThreadLocal.withInitial { null }

    fun <T> withContext(context: Context, block: () -> T): T {
        val previous = current.get()
        current.set(context)
        return try {
            block()
        } finally {
            current.set(previous)
        }
    }

    fun requireCurrent(): Context {
        return current.get() ?: error("manifestation_externalized_foreach_frame_without_splinter_context")
    }
}
