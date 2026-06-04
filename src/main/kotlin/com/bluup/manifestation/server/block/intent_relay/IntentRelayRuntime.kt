package com.bluup.manifestation.server.block

/**
 * Simple recursion guard for relay forwarding calls on the server thread.
 */
object IntentRelayRuntime {
    private const val MAX_DEPTH = 8
    private val depth = ThreadLocal.withInitial { 0 }

    fun <T> runForwarding(action: () -> T): T? {
        val current = depth.get()
        if (current >= MAX_DEPTH) {
            return null
        }

        depth.set(current + 1)
        return try {
            action()
        } finally {
            depth.set(current)
        }
    }
}
