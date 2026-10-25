package com.ecommerce.order.event;

import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.ShippingAddress;

import java.math.BigDecimal;
import java.util.List;

public class OrderConfirmationMailEvent {
    private final Long orderId;
    private final String customerName;
    private final String customerEmail;
    private final BigDecimal totalAmount;
    private final String paymentMethod;
    private final ShippingAddress address;
    private final List<OrderItem> items;

    public OrderConfirmationMailEvent(Long orderId,
                                      String customerName,
                                      String customerEmail,
                                      BigDecimal totalAmount,
                                      String paymentMethod,
                                      ShippingAddress address,
                                      List<OrderItem> items) {
        this.orderId = orderId;
        this.customerName = customerName;
        this.customerEmail = customerEmail;
        this.totalAmount = totalAmount;
        this.paymentMethod = paymentMethod;
        this.address = address;
        this.items = items;
    }

    public Long getOrderId() { return orderId; }
    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getPaymentMethod() { return paymentMethod; }
    public ShippingAddress getAddress() { return address; }
    public List<OrderItem> getItems() { return items; }
}
