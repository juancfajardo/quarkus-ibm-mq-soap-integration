package dev.juancfajardo.integration.gateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;

/** In-memory order tracking for the demo (a real system would use a table). */
@ApplicationScoped
public class OrderStore {

    public enum Status {
        QUEUED, RETRYING, SENT, REJECTED_TO_DLQ, FAILED_TO_DLQ, SYNC_SENT, SYNC_REJECTED, SYNC_LOST
    }

    public static final class OrderRecord {
        public final String orderId;
        public final long acceptedAtMs;
        public volatile Status status;
        public volatile long completedAtMs;
        public volatile String erpOrderNumber;
        public volatile String reason;
        public volatile boolean erpReportedDuplicate;
        public final AtomicLong erpCalls = new AtomicLong();
        public final AtomicLong deliveries = new AtomicLong();

        OrderRecord(String orderId, long acceptedAtMs, Status status) {
            this.orderId = orderId;
            this.acceptedAtMs = acceptedAtMs;
            this.status = status;
        }
    }

    private final Map<String, OrderRecord> orders = new ConcurrentHashMap<>();

    public OrderRecord create(String orderId, long acceptedAtMs, Status status) {
        OrderRecord record = new OrderRecord(orderId, acceptedAtMs, status);
        orders.put(orderId, record);
        return record;
    }

    public OrderRecord get(String orderId) {
        return orders.get(orderId);
    }

    public OrderRecord getOrCreate(String orderId, long acceptedAtMs) {
        return orders.computeIfAbsent(orderId, id -> new OrderRecord(id, acceptedAtMs, Status.QUEUED));
    }

    public void remove(String orderId) {
        orders.remove(orderId);
    }

    public void clear() {
        orders.clear();
    }

    public Map<String, Object> stats() {
        Collection<OrderRecord> all = orders.values();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Status s : Status.values()) {
            byStatus.put(s.name(), 0L);
        }
        long erpCalls = 0;
        long redeliveries = 0;
        long duplicatesReported = 0;
        long firstAccepted = Long.MAX_VALUE;
        long lastCompleted = 0;
        List<Long> endToEnd = new ArrayList<>();
        for (OrderRecord r : all) {
            byStatus.merge(r.status.name(), 1L, Long::sum);
            erpCalls += r.erpCalls.get();
            redeliveries += Math.max(0, r.deliveries.get() - 1);
            if (r.erpReportedDuplicate) {
                duplicatesReported++;
            }
            if (r.status == Status.SENT) {
                endToEnd.add(r.completedAtMs - r.acceptedAtMs);
                firstAccepted = Math.min(firstAccepted, r.acceptedAtMs);
                lastCompleted = Math.max(lastCompleted, r.completedAtMs);
            }
        }
        endToEnd.sort(Long::compare);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", all.size());
        m.put("byStatus", byStatus);
        m.put("erpCalls", erpCalls);
        m.put("redeliveries", redeliveries);
        m.put("erpReportedDuplicates", duplicatesReported);
        m.put("endToEndMsP50", percentile(endToEnd, 50));
        m.put("endToEndMsP95", percentile(endToEnd, 95));
        m.put("endToEndMsMax", endToEnd.isEmpty() ? 0 : endToEnd.get(endToEnd.size() - 1));
        m.put("asyncWindowMs", endToEnd.isEmpty() ? 0 : lastCompleted - firstAccepted);
        return m;
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
