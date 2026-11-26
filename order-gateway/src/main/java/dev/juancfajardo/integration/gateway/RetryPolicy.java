package dev.juancfajardo.integration.gateway;

/**
 * Exponential backoff for calls to the ERP: base, 2x base, 4x base... capped at {@code maxDelayMs}.
 * After {@code maxAttempts} failed calls the message goes back to the queue (transaction rollback),
 * and after {@code maxDeliveries} deliveries it is moved to the dead-letter queue.
 */
public record RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs, int maxDeliveries) {

    public RetryPolicy {
        if (maxAttempts < 1 || maxDeliveries < 1) {
            throw new IllegalArgumentException("maxAttempts and maxDeliveries must be at least 1");
        }
        if (baseDelayMs < 0 || maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("invalid delays");
        }
    }

    /** Delay before the next call, after failed attempt number {@code attempt} (1-based). */
    public long delayAfter(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt is 1-based");
        }
        long delay = baseDelayMs << Math.min(attempt - 1, 20);
        return Math.min(delay, maxDelayMs);
    }

    public boolean hasAttemptsLeft(int failedAttempts) {
        return failedAttempts < maxAttempts;
    }

    public boolean exceededDeliveries(int deliveryCount) {
        return deliveryCount > maxDeliveries;
    }
}
