package dev.juancfajardo.integration.contract;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlType;

/** ERP answer. {@code duplicate=true} means the ERP already had this order and returned the existing number. */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "OrderAck", namespace = ErpOrderService.NAMESPACE)
public class OrderAck {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";

    private String status;
    private String erpOrderNumber;
    private String reason;
    private boolean duplicate;

    public OrderAck() {
    }

    public static OrderAck accepted(String erpOrderNumber, boolean duplicate) {
        OrderAck ack = new OrderAck();
        ack.status = ACCEPTED;
        ack.erpOrderNumber = erpOrderNumber;
        ack.duplicate = duplicate;
        return ack;
    }

    public static OrderAck rejected(String reason) {
        OrderAck ack = new OrderAck();
        ack.status = REJECTED;
        ack.reason = reason;
        return ack;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErpOrderNumber() {
        return erpOrderNumber;
    }

    public void setErpOrderNumber(String erpOrderNumber) {
        this.erpOrderNumber = erpOrderNumber;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public void setDuplicate(boolean duplicate) {
        this.duplicate = duplicate;
    }
}
