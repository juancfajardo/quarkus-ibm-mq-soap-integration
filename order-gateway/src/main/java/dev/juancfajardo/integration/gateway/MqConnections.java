package dev.juancfajardo.integration.gateway;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.Queue;
import jakarta.jms.TextMessage;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.ibm.msg.client.jakarta.jms.JmsConnectionFactory;
import com.ibm.msg.client.jakarta.jms.JmsFactoryFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;

/**
 * IBM MQ client connection (JMS 3.0, Jakarta). Producers come from a small pool of contexts,
 * because a JMSContext must not be shared between threads.
 */
@ApplicationScoped
public class MqConnections {

    @ConfigProperty(name = "mq.host")
    String host;
    @ConfigProperty(name = "mq.port")
    int port;
    @ConfigProperty(name = "mq.channel")
    String channel;
    @ConfigProperty(name = "mq.queue-manager")
    String queueManager;
    @ConfigProperty(name = "mq.user")
    String user;
    @ConfigProperty(name = "mq.password")
    String password;
    @ConfigProperty(name = "mq.orders-queue")
    String ordersQueue;
    @ConfigProperty(name = "mq.dead-letter-queue")
    String deadLetterQueue;
    @ConfigProperty(name = "mq.producer-pool-size", defaultValue = "8")
    int producerPoolSize;

    private JmsConnectionFactory factory;
    private BlockingQueue<JMSContext> producers;

    @PostConstruct
    void init() {
        try {
            configureFactory();
        } catch (JMSException e) {
            throw new IllegalStateException("Cannot configure the IBM MQ connection factory", e);
        }
        producers = new ArrayBlockingQueue<>(producerPoolSize);
    }

    private void configureFactory() throws JMSException {
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.JAKARTA_WMQ_PROVIDER);
        factory = ff.createConnectionFactory();
        factory.setStringProperty(WMQConstants.WMQ_HOST_NAME, host);
        factory.setIntProperty(WMQConstants.WMQ_PORT, port);
        factory.setStringProperty(WMQConstants.WMQ_CHANNEL, channel);
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, queueManager);
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "order-gateway");
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory.setStringProperty(WMQConstants.USERID, user);
        factory.setStringProperty(WMQConstants.PASSWORD, password);
    }

    /** Transacted context for a consumer thread: commit = message consumed, rollback = back to the queue. */
    public JMSContext createTransactedContext() {
        return factory.createContext(JMSContext.SESSION_TRANSACTED);
    }

    public Queue ordersQueue(JMSContext ctx) {
        return ctx.createQueue("queue:///" + ordersQueue);
    }

    public Queue deadLetterQueue(JMSContext ctx) {
        return ctx.createQueue("queue:///" + deadLetterQueue);
    }

    /** Puts a persistent JSON message on the orders queue. */
    public void sendOrder(String orderId, String json) throws InterruptedException, JMSException {
        JMSContext ctx = producers.poll();
        if (ctx == null) {
            ctx = factory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
        }
        boolean healthy = true;
        try {
            TextMessage message = ctx.createTextMessage(json);
            message.setStringProperty("orderId", orderId);
            ctx.createProducer().send(ordersQueue(ctx), message);
        } catch (RuntimeException | JMSException e) {
            healthy = false;
            throw e;
        } finally {
            if (!healthy || !producers.offer(ctx, 10, TimeUnit.MILLISECONDS)) {
                ctx.close();
            }
        }
    }

    @PreDestroy
    void close() {
        JMSContext ctx;
        while ((ctx = producers.poll()) != null) {
            ctx.close();
        }
    }
}
