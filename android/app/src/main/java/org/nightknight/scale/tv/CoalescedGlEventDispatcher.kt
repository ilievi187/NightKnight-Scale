package org.nightknight.scale.tv

/** Coalesces requests to one queued GL event while retaining the newest requested action. */
internal class CoalescedGlEventDispatcher(
    private val enqueue: ((() -> Unit) -> Unit),
) {
    private val lock = Any()
    private var pending = false
    private var latestAction: (() -> Unit)? = null

    fun request(action: () -> Unit) {
        val shouldEnqueue = synchronized(lock) {
            latestAction = action
            if (pending) {
                false
            } else {
                pending = true
                true
            }
        }
        if (!shouldEnqueue) return

        try {
            enqueue {
                val nextAction = synchronized(lock) {
                    latestAction.also {
                        latestAction = null
                        pending = false
                    }
                }
                nextAction?.invoke()
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                pending = false
                latestAction = null
            }
            throw failure
        }
    }
}
