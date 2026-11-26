package dev.juancfajardo.integration.contract;

import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;

/**
 * SOAP contract of the legacy ERP. Shared by the ERP mock (server) and the order gateway (client).
 *
 * <p>Business rejections are returned in {@link OrderAck#getStatus()}; technical problems
 * (ERP down, timeouts) surface as SOAP faults, which the client treats as retryable.
 */
@WebService(name = "ErpOrderService", targetNamespace = ErpOrderService.NAMESPACE)
public interface ErpOrderService {

    String NAMESPACE = "http://erp.example.com/orders";

    @WebMethod
    @WebResult(name = "ack")
    OrderAck registerOrder(@WebParam(name = "order") OrderRequest order);
}
