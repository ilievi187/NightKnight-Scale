package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class CoalescedGlEventDispatcherTest {
    @Test
    fun pendingRequestsCollapseAndReadTheLatestRequestedState() {
        val pending = mutableListOf<() -> Unit>()
        val dispatcher = CoalescedGlEventDispatcher { event -> pending += event }
        var latestRequestedMode = "off"
        val appliedModes = mutableListOf<String>()

        latestRequestedMode = "quality"
        dispatcher.request { appliedModes += latestRequestedMode }
        latestRequestedMode = "performance"
        dispatcher.request { appliedModes += latestRequestedMode }
        latestRequestedMode = "balanced"
        dispatcher.request { appliedModes += latestRequestedMode }

        assertEquals(1, pending.size)
        pending.removeAt(0).invoke()
        assertEquals(listOf("balanced"), appliedModes)
    }

    @Test
    fun requestsMadeDuringAnEventScheduleOneFollowup() {
        val pending = mutableListOf<() -> Unit>()
        val dispatcher = CoalescedGlEventDispatcher { event -> pending += event }
        val applied = mutableListOf<Int>()

        dispatcher.request {
            applied += 1
            dispatcher.request { applied += 2 }
            dispatcher.request { applied += 3 }
        }

        assertEquals(1, pending.size)
        pending.removeAt(0).invoke()
        assertEquals(listOf(1), applied)
        assertEquals(1, pending.size)
        pending.removeAt(0).invoke()
        assertEquals(listOf(1, 3), applied)
    }
}
