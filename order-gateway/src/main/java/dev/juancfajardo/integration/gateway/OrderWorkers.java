package dev.juancfajardo.integration.gateway;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.TextMessage;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.juancfajardo.integration.contract.ErpOrderService;
import dev.juancfajardo.integration.contract.OrderAck;
import dev.juancfajardo.integration.contract.OrderRequest;
import dev.juancfajardo.integration.gateway.OrderStore.OrderRecord;
import dev.juancfajardo.integration.gateway.OrderStore.Status;
import io.quarkiverse.cxf.annotation.CXFClient;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

/**
 * Consumers of the orders queue. Each thread owns a transacted JMSContext:
 * <ul>
 * <li>ERP accepted the order: commit (message consumed).</li>
 * <li>ERP rejected it (business rule): move it to the dead-letter queue and commit, in the same transaction.</li>
 * <li>ERP keeps failing after the retries: rollback, so IBM MQ redelivers it later; after too many deliveries it
 * goes to the dead-letter queue.</li>
 * </ul>
 * Retrying is safe because the ERP call is idempotent by orderId.
 */
@ApplicationScoped
public class OrderWorkers {

    private static final Logger LOG = Logger.getLogger(OrderWorkers.class);

    @Inject
    MqConnections mq;
    @Inject
    OrderStore store;
    @Inject
    ObjectMapper mapper;
    @Inject
    @CXFClient("erp")
    ErpOrderService erp;

    @ConfigProperty(name = "workers.count", defaultValue = "4")
    int workerCount;
    @ConfigProperty(name = "retry.max-attempts", defaultValue = "5")
    int maxAttempts;
    @ConfigProperty(name = "retry.base-delay-ms", defaultValue = "50")
    long baseDelayMs;
    @ConfigProperty(name = "retry.max-delay-ms", defaultValue = "1000")
    long maxDelayMs;
    @ConfigProperty(name = "retry.max-deliveries", defaultValue = "3")
    int maxDeliveries;

    private volatile boolean running;
    private ExecutorService executor;
    private RetryPolicy policy;

    void start(@Observes StartupEvent event) {
        policy = new RetryPolicy(maxAttempts, baseDelayMs, maxDelayMs, maxDeliveries);
        running = true;
        executor = Executors.newFixedThreadPool(workerCount);
        for (int i = 0; i < workerCount; i++) {
            executor.submit(this::consumeLoop);
        }
        LOG.infof("Started %d order consumers, %s", workerCount, policy);
    }

    void stop(@Observes ShutdownEvent event) {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void consumeLoop() {
        while (running) {
            try (JMSContext ctx = mq.createTransactedContext()) {
                JMSConsumer consumer = ctx.createConsumer(mq.ordersQueue(ctx));
                JMSProducer producer = ctx.createProducer();
                Queue dlq = mq.deadLetterQueue(ctx);
                while (running) {
                    Message message = consumer.receive(1_000);
                    if (message != null) {
                        handle(ctx, producer, dlq, message);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    LOG.warnf("Consumer connection problem, reconnecting in 2 s: %s", e.toString());
                    sleep(2_000);
                }
            }
        }
    }

    private void handle(JMSContext ctx, JMSProducer producer, Queue dlq, Message message) throws Exception {
        String json = message.getBody(String.class);
        OrderMessage order = mapper.readValue(json, OrderMessage.class);
        OrderRecord record = store.getOrCreate(order.orderId(), order.acceptedAtMs());
        int deliveryCount = message.propertyExists("JMSXDeliveryCount")
                ? message.getIntProperty("JMSXDeliveryCount")
                : 1;
        record.deliveries.incrementAndGet();

        if (policy.exceededDeliveries(deliveryCount)) {
            moveToDeadLetter(ctx, producer, dlq, json, order.orderId(), "Too many delivery attempts");
            record.status = Status.FAILED_TO_DLQ;
            record.reason = "Too many delivery attempts";
            record.completedAtMs = System.currentTimeMillis();
            ctx.commit();
            return;
        }

        OrderRequest request = new OrderRequest(order.orderId(), order.customerCode(), order.totalAmount(),
                order.lineCount());
        OrderAck ack = callErpWithRetries(request, record);
        if (ack == null) {
            record.status = Status.RETRYING;
            ctx.rollback(); // back to the queue; IBM MQ redelivers it
            return;
        }

        if (OrderAck.REJECTED.equals(ack.getStatus())) {
            moveToDeadLetter(ctx, producer, dlq, json, order.orderId(), ack.getReason());
            record.status = Status.REJECTED_TO_DLQ;
            record.reason = ack.getReason();
        } else {
            record.status = Status.SENT;
            record.erpOrderNumber = ack.getErpOrderNumber();
            record.erpReportedDuplicate = ack.isDuplicate();
        }
        record.completedAtMs = System.currentTimeMillis();
        ctx.commit();
    }

    /** Returns the ERP answer, or null if every attempt failed with a technical error. */
    private OrderAck callErpWithRetries(OrderRequest request, OrderRecord record) {
        int failed = 0;
        while (true) {
            try {
                record.erpCalls.incrementAndGet();
                return erp.registerOrder(request);
            } catch (RuntimeException e) { // SOAP fault, timeout, connection refused
                failed++;
                if (!policy.hasAttemptsLeft(failed)) {
                    LOG.debugf("Order %s: %d failed ERP calls, giving it back to the queue", request.getOrderId(),
                            failed);
                    return null;
                }
                sleep(policy.delayAfter(failed));
            }
        }
    }

    private void moveToDeadLetter(JMSContext ctx, JMSProducer producer, Queue dlq, String json, String orderId,
            String reason) throws Exception {
        TextMessage copy = ctx.createTextMessage(json);
        copy.setStringProperty("orderId", orderId);
        copy.setStringProperty("dlqReason", reason);
        producer.send(dlq, copy);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
