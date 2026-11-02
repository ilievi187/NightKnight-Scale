package org.nightknight.scale.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Native4kPerformanceValidationPolicyTest {
    @Test
    fun validatesOnlyAfterConsecutiveStableActive4kWindows() {
        val policy = Native4kPerformanceValidationPolicy(stableWindowsRequired = 3)
        repeat(2) {
            assertFalse(policy.observe(true, true, 30f, 32f))
        }
        assertTrue(policy.observe(true, true, 30f, 32f))
        assertTrue(policy.isValidated)
    }

    @Test
    fun missing4kEvidenceInactiveSourceOrUnknownRateCannotValidate() {
        val policy = Native4kPerformanceValidationPolicy(stableWindowsRequired = 1)
        assertFalse(policy.observe(false, true, 30f, 32f))
        assertFalse(policy.observe(true, false, 30f, 32f))
        assertFalse(policy.observe(true, true, null, 32f))
        assertFalse(policy.observe(true, true, 30f, null))
        assertFalse(policy.isValidated)
    }

    @Test
    fun overloadBreaksTheConsecutiveStableWindowRun() {
        val policy = Native4kPerformanceValidationPolicy(stableWindowsRequired = 3)
        assertFalse(policy.observe(true, true, 30f, 32f))
        assertFalse(policy.observe(true, true, 30f, 40f))
        assertFalse(policy.observe(true, true, 30f, 32f))
        assertFalse(policy.isValidated)
        assertFalse(policy.observe(true, true, 30f, 32f))
        assertTrue(policy.observe(true, true, 30f, 32f))
    }

    @Test
    fun thirtyFpsProfileDoesNotAcceptAnAverageIntervalMoreThanThreePercentOverBudget() {
        val policy = Native4kPerformanceValidationPolicy(stableWindowsRequired = 1)
        assertFalse(policy.observe(true, true, 30f, 35f))
        assertFalse(policy.isValidated)
    }
}
