package dev.juancfajardo.integration.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(5, 100, 1_000, 3);

    @Test
    void delaysGrowExponentiallyUntilTheCap() {
        assertEquals(100, policy.delayAfter(1));
        assertEquals(200, policy.delayAfter(2));
        assertEquals(400, policy.delayAfter(3));
        assertEquals(800, policy.delayAfter(4));
        assertEquals(1_000, policy.delayAfter(5));
        assertEquals(1_000, policy.delayAfter(60));
    }

    @Test
    void stopsRetryingAfterMaxAttempts() {
        assertTrue(policy.hasAttemptsLeft(4));
        assertFalse(policy.hasAttemptsLeft(5));
    }

    @Test
    void sendsToDeadLetterQueueAfterMaxDeliveries() {
        assertFalse(policy.exceededDeliveries(3));
        assertTrue(policy.exceededDeliveries(4));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 100, 1_000, 3));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(3, 500, 100, 3));
        assertThrows(IllegalArgumentException.class, () -> policy.delayAfter(0));
    }
}
