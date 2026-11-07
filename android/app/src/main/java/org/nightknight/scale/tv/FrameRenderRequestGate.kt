package org.nightknight.scale.tv

import java.util.concurrent.atomic.AtomicBoolean

/** Coalesces decoded-frame notifications while one dirty render is pending. */
internal class FrameRenderRequestGate {
    private val pending = AtomicBoolean(false)

    fun markFramePending(): Boolean = pending.compareAndSet(false, true)

    fun onRenderStarted() {
        pending.set(false)
    }

    fun reset() {
        pending.set(false)
    }
}
