package com.ecommerce.order.service;

import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.config.OrderPricing;
import com.ecommerce.order.dto.OrderRequest;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.ShippingAddress;
import com.ecommerce.order.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@Transactional
public class OrderService {
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductClient productClient;

    @Autowired
    private OrderEventService orderEventService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    public Order createOrder(OrderRequest orderRequest) {
        Order order = new Order();
        order.setUserId(orderRequest.getUserId());
        order.setCustomerName(orderRequest.getCustomerName());
        order.setCustomerEmail(orderRequest.getCustomerEmail());
        order.setPaymentMethod(orderRequest.getPaymentMethod());

        // Shipping address
        ShippingAddress shippingAddress = new ShippingAddress();
        shippingAddress.setStreet(orderRequest.getShippingAddress().getStreet());
        shippingAddress.setCity(orderRequest.getShippingAddress().getCity());
        shippingAddress.setState(orderRequest.getShippingAddress().getState());
        shippingAddress.setZipCode(orderRequest.getShippingAddress().getZipCode());
        shippingAddress.setCountry(orderRequest.getShippingAddress().getCountry());
        order.setShippingAddress(shippingAddress);

        BigDecimal totalAmount = BigDecimal.ZERO;

        // Order items
        for (var itemRequest : orderRequest.getOrderItems()) {
            var productResponse = productClient.getProduct(itemRequest.getProductId());

            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(productResponse.getId());
            orderItem.setProductName(productResponse.getName());
            orderItem.setProductPrice(BigDecimal.valueOf(productResponse.getPrice()));
            orderItem.setQuantity(itemRequest.getQuantity());

            BigDecimal subtotal = BigDecimal.valueOf(productResponse.getPrice())
                    .multiply(BigDecimal.valueOf(itemRequest.getQuantity()));
            orderItem.setSubtotal(subtotal);

            order.getOrderItems().add(orderItem);
            totalAmount = totalAmount.add(subtotal);
        }

        order.setTotalAmount(totalAmount.add(OrderPricing.DELIVERY_FEE));
        Order savedOrder = orderRepository.save(order);
        savedOrder.getOrderItems().size();
        orderEventService.publishOrderCreated(savedOrder);
        eventPublisher.publishEvent(emailService.toMailEvent(savedOrder));
        return savedOrder;
    }
}
