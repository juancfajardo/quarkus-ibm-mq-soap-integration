package dev.juancfajardo.integration.contract;

import java.math.BigDecimal;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlType;

/** Order sent to the ERP. {@code orderId} is the idempotency key. */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "OrderRequest", namespace = ErpOrderService.NAMESPACE)
public class OrderRequest {

    private String orderId;
    private String customerCode;
    private BigDecimal totalAmount;
    private int lineCount;

    public OrderRequest() {
    }

    public OrderRequest(String orderId, String customerCode, BigDecimal totalAmount, int lineCount) {
        this.orderId = orderId;
        this.customerCode = customerCode;
        this.totalAmount = totalAmount;
        this.lineCount = lineCount;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getCustomerCode() {
        return customerCode;
    }

    public void setCustomerCode(String customerCode) {
        this.customerCode = customerCode;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public int getLineCount() {
        return lineCount;
    }

    public void setLineCount(int lineCount) {
        this.lineCount = lineCount;
    }
}
