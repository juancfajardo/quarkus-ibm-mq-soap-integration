package dev.juancfajardo.integration.erp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory "ERP database" plus the fault-injection settings. Static on purpose: the SOAP endpoint
 * and the REST admin resource share it without extra wiring.
 */
public final class ErpLedger {

    /** orderId -> ERP order number. putIfAbsent makes registration idempotent. */
    static final Map<String, String> ORDERS = new ConcurrentHashMap<>();

    static final AtomicLong CALLS = new AtomicLong();
    static final AtomicLong FAULTS_BEFORE_SAVE = new AtomicLong();
    static final AtomicLong FAULTS_AFTER_SAVE = new AtomicLong();
    static final AtomicLong DUPLICATES_ABSORBED = new AtomicLong();
    static final AtomicLong REJECTED = new AtomicLong();
    static final AtomicLong SEQUENCE = new AtomicLong(100_000);

    /** Probability that the ERP fails before saving the order (ERP down, lock timeout...). */
    static volatile double failureRate = 0.0;
    /** Probability that the ERP saves the order but the reply is lost (timeout after commit). */
    static volatile double lostReplyRate = 0.0;
    /** Artificial processing time of each call, in milliseconds. */
    static volatile long latencyMs = 0;

    private ErpLedger() {
    }

    static void reset() {
        ORDERS.clear();
        CALLS.set(0);
        FAULTS_BEFORE_SAVE.set(0);
        FAULTS_AFTER_SAVE.set(0);
        DUPLICATES_ABSORBED.set(0);
        REJECTED.set(0);
    }

    static Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calls", CALLS.get());
        m.put("uniqueOrders", ORDERS.size());
        m.put("faultsBeforeSave", FAULTS_BEFORE_SAVE.get());
        m.put("faultsAfterSave", FAULTS_AFTER_SAVE.get());
        m.put("duplicatesAbsorbed", DUPLICATES_ABSORBED.get());
        m.put("rejected", REJECTED.get());
        m.put("failureRate", failureRate);
        m.put("lostReplyRate", lostReplyRate);
        m.put("latencyMs", latencyMs);
        return m;
    }
}
