package dev.juancfajardo.integration.gateway;

import java.math.BigDecimal;

/** Payload of the message on the IBM MQ queue (JSON text message). */
public record OrderMessage(String orderId, String customerCode, BigDecimal totalAmount, int lineCount,
        long acceptedAtMs) {
}
