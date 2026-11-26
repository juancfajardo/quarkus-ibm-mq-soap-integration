package dev.juancfajardo.integration.erp;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.jws.WebService;

import dev.juancfajardo.integration.contract.ErpOrderService;
import dev.juancfajardo.integration.contract.OrderAck;
import dev.juancfajardo.integration.contract.OrderRequest;

/**
 * Behaves like a fragile legacy ERP: it can be slow, it fails a configurable share of calls,
 * and sometimes it saves the order and then loses the reply. It is idempotent by orderId.
 */
@WebService(serviceName = "ErpOrderService",
        portName = "ErpOrderServicePort",
        targetNamespace = ErpOrderService.NAMESPACE,
        endpointInterface = "dev.juancfajardo.integration.contract.ErpOrderService")
public class ErpOrderServiceImpl implements ErpOrderService {

    @Override
    public OrderAck registerOrder(OrderRequest order) {
        ErpLedger.CALLS.incrementAndGet();
        sleep(ErpLedger.latencyMs);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (random.nextDouble() < ErpLedger.failureRate) {
            ErpLedger.FAULTS_BEFORE_SAVE.incrementAndGet();
            throw new IllegalStateException("ERP temporarily unavailable");
        }

        if (order.getCustomerCode() == null || order.getCustomerCode().startsWith("BLOCKED")) {
            ErpLedger.REJECTED.incrementAndGet();
            return OrderAck.rejected("Customer is blocked for new orders");
        }
        if (order.getTotalAmount() == null || order.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            ErpLedger.REJECTED.incrementAndGet();
            return OrderAck.rejected("Order total must be positive");
        }

        String newNumber = "ERP-" + ErpLedger.SEQUENCE.incrementAndGet();
        String existing = ErpLedger.ORDERS.putIfAbsent(order.getOrderId(), newNumber);
        if (existing != null) {
            ErpLedger.DUPLICATES_ABSORBED.incrementAndGet();
            return OrderAck.accepted(existing, true);
        }

        if (random.nextDouble() < ErpLedger.lostReplyRate) {
            // The order IS saved, but the caller never gets the answer: the classic duplicate trap.
            ErpLedger.FAULTS_AFTER_SAVE.incrementAndGet();
            throw new IllegalStateException("Timeout after the order was saved");
        }
        return OrderAck.accepted(newNumber, false);
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
