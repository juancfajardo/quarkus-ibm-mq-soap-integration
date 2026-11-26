package dev.juancfajardo.integration.gateway;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.juancfajardo.integration.contract.ErpOrderService;
import dev.juancfajardo.integration.contract.OrderAck;
import dev.juancfajardo.integration.contract.OrderRequest;
import dev.juancfajardo.integration.gateway.OrderStore.OrderRecord;
import dev.juancfajardo.integration.gateway.OrderStore.Status;
import io.quarkiverse.cxf.annotation.CXFClient;
import io.smallrye.common.annotation.Blocking;

@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class OrderResource {

    public record NewOrder(String customerCode, BigDecimal totalAmount, int lineCount) {
    }

    @Inject
    MqConnections mq;
    @Inject
    OrderStore store;
    @Inject
    ObjectMapper mapper;
    @Inject
    @CXFClient("erp")
    ErpOrderService erp;

    /** Recommended path: accept, put on IBM MQ, answer 202. The ERP call happens in the background. */
    @POST
    public Response submit(NewOrder order) throws Exception {
        if (order == null || order.customerCode() == null || order.customerCode().isBlank()) {
            return Response.status(400).entity(Map.of("error", "customerCode is required")).build();
        }
        String orderId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        store.create(orderId, now, Status.QUEUED);
        try {
            String json = mapper.writeValueAsString(
                    new OrderMessage(orderId, order.customerCode(), order.totalAmount(), order.lineCount(), now));
            mq.sendOrder(orderId, json);
        } catch (Exception e) {
            store.remove(orderId);
            return Response.status(503).entity(Map.of("error", "Order queue unavailable")).build();
        }
        return Response.accepted(Map.of("orderId", orderId, "status", Status.QUEUED.name())).build();
    }

    /** Baseline for comparison: calls the ERP directly, once, while the client waits. */
    @POST
    @Path("/sync")
    public Response submitSync(NewOrder order) {
        String orderId = UUID.randomUUID().toString();
        OrderRecord record = store.create(orderId, System.currentTimeMillis(), Status.QUEUED);
        try {
            record.erpCalls.incrementAndGet();
            OrderAck ack = erp.registerOrder(
                    new OrderRequest(orderId, order.customerCode(), order.totalAmount(), order.lineCount()));
            record.completedAtMs = System.currentTimeMillis();
            if (OrderAck.REJECTED.equals(ack.getStatus())) {
                record.status = Status.SYNC_REJECTED;
                return Response.status(422).entity(Map.of("orderId", orderId, "reason", ack.getReason())).build();
            }
            record.status = Status.SYNC_SENT;
            return Response.ok(Map.of("orderId", orderId, "erpOrderNumber", ack.getErpOrderNumber())).build();
        } catch (RuntimeException e) {
            record.status = Status.SYNC_LOST;
            record.completedAtMs = System.currentTimeMillis();
            return Response.status(502).entity(Map.of("orderId", orderId, "error", "ERP call failed")).build();
        }
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        OrderRecord r = store.get(id);
        if (r == null) {
            return Response.status(404).build();
        }
        return Response.ok(Map.of(
                "orderId", r.orderId,
                "status", r.status.name(),
                "erpOrderNumber", r.erpOrderNumber == null ? "" : r.erpOrderNumber,
                "erpCalls", r.erpCalls.get(),
                "deliveries", r.deliveries.get())).build();
    }

    @GET
    @Path("/stats")
    public Map<String, Object> stats() {
        return store.stats();
    }

    @POST
    @Path("/reset")
    public Map<String, Object> reset() {
        store.clear();
        return store.stats();
    }
}
