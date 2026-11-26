package dev.juancfajardo.integration.erp;

import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Admin API used by the load test: change the fault injection and read the ERP counters. */
@Path("/erp")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ErpAdminResource {

    public record FaultConfig(Double failureRate, Double lostReplyRate, Long latencyMs) {
    }

    @GET
    @Path("/stats")
    public Map<String, Object> stats() {
        return ErpLedger.snapshot();
    }

    @POST
    @Path("/config")
    public Map<String, Object> configure(FaultConfig config) {
        if (config.failureRate() != null) {
            ErpLedger.failureRate = config.failureRate();
        }
        if (config.lostReplyRate() != null) {
            ErpLedger.lostReplyRate = config.lostReplyRate();
        }
        if (config.latencyMs() != null) {
            ErpLedger.latencyMs = config.latencyMs();
        }
        return ErpLedger.snapshot();
    }

    @POST
    @Path("/reset")
    public Map<String, Object> reset() {
        ErpLedger.reset();
        return ErpLedger.snapshot();
    }
}
