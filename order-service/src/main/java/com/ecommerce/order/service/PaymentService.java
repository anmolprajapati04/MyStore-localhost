package com.ecommerce.order.service;

import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.config.OrderPricing;
import com.ecommerce.order.dto.*;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.Payment;
import com.ecommerce.order.model.PaymentStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.RazorpayClient;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {
    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProductClient productClient;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderEventService orderEventService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RazorpayClient razorpayClient;

    @Value("${app.razorpay.key-id}")
    private String keyId;

    @Value("${app.razorpay.key-secret}")
    private String keySecret;

    @Value("${app.razorpay.currency:INR}")
    private String currency;

    @Value("${app.razorpay.mock:false}")
    private boolean mockRazorpay;

    @Transactional
    public PaymentOrderResponse createPaymentOrder(PaymentOrderRequest request, String authenticatedUser) {
        logger.info("Creating payment order for user: {} with idempotencyKey: {}", authenticatedUser, request.getIdempotencyKey());
        
        OrderRequest orderRequest = request.getOrder();
        orderRequest.setUserId(authenticatedUser);
        
        BigDecimal total = calculateTotal(orderRequest.getOrderItems());
        long amountInPaise = total.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        String receipt = "mst_" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        
        String razorpayOrderId;
        if ("COD".equalsIgnoreCase(orderRequest.getPaymentMethod())) {
            razorpayOrderId = "cod_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            logger.info("COD order detected. Using local reference: {}", razorpayOrderId);
        } else {
            razorpayOrderId = createRazorpayOrder(amountInPaise, receipt);
        }

        try {
            Payment payment = new Payment();
            payment.setUserId(authenticatedUser);
            payment.setUserEmail(orderRequest.getCustomerEmail());
            payment.setRazorpayOrderId(razorpayOrderId);
            payment.setCurrency(currency);
            payment.setAmount(total);
            payment.setPaymentMethod(orderRequest.getPaymentMethod());
            payment.setPaymentStatus(PaymentStatus.PENDING);
            payment.setOrderPayload(objectMapper.writeValueAsString(orderRequest));
            
            Payment savedPayment = paymentRepository.save(payment);
            logger.info("Payment record created with ID: {} and Razorpay Order ID: {}", savedPayment.getId(), razorpayOrderId);
            
            return toPaymentOrderResponse(savedPayment);
        } catch (Exception ex) {
            logger.error("Failed to create payment record", ex);
            throw new IllegalStateException("Unable to create payment session", ex);
        }
    }

    @Transactional
    public PaymentVerificationResponse verifyPayment(PaymentVerificationRequest request, String authenticatedUser) {
        logger.info("Verifying payment for Razorpay Order ID: {}", request.getRazorpayOrderId());
        
        Payment payment = paymentRepository.findByRazorpayOrderId(request.getRazorpayOrderId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment order"));

        if (PaymentStatus.SUCCESS.equals(payment.getPaymentStatus()) && payment.getOrderId() != null) {
            logger.info("Payment already verified for Razorpay Order ID: {}", request.getRazorpayOrderId());
            return toVerificationResponse(payment, orderRepository.findById(payment.getOrderId()).orElse(null), "Payment already verified");
        }

        if (!isValidSignature(request)) {
            logger.warn("Invalid payment signature for Razorpay Order ID: {}", request.getRazorpayOrderId());
            payment.setPaymentStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Invalid payment signature");
            paymentRepository.save(payment);
            return toVerificationResponse(payment, null, "Payment verification failed");
        }

        try {
            OrderRequest orderRequest = objectMapper.readValue(payment.getOrderPayload(), OrderRequest.class);
            
            // Validate stock before confirming order
            validateStock(orderRequest.getOrderItems());
            
            // Create the actual order
            Order order = orderService.createOrder(orderRequest);
            
            // Decrement stock
            decrementStock(orderRequest.getOrderItems());

            payment.setPaymentId(request.getRazorpayPaymentId());
            payment.setPaymentStatus(PaymentStatus.SUCCESS);
            payment.setOrderId(order.getId());
            paymentRepository.save(payment);

            logger.info("Payment verified successfully and order #{} created for Razorpay Order ID: {}", order.getId(), request.getRazorpayOrderId());

            orderEventService.publishPaymentSuccess(order, payment);
            return toVerificationResponse(payment, order, "Payment verified and order confirmed");
        } catch (Exception ex) {
            logger.error("Error during payment verification", ex);
            payment.setPaymentStatus(PaymentStatus.FAILED);
            payment.setFailureReason(ex.getMessage());
            paymentRepository.save(payment);
            throw new IllegalStateException("Payment verified but order confirmation failed", ex);
        }
    }

    @Transactional
    public void markPaymentFailed(String razorpayOrderId, String reason, String authenticatedUser) {
        logger.info("Marking payment as failed for Razorpay Order ID: {}. Reason: {}", razorpayOrderId, reason);
        Payment payment = paymentRepository.findByRazorpayOrderId(razorpayOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment order"));
        
        payment.setPaymentStatus(PaymentStatus.FAILED);
        payment.setFailureReason(reason);
        paymentRepository.save(payment);
    }

    public List<Payment> getPaymentHistory(String userId) {
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private BigDecimal calculateTotal(List<OrderItemRequest> items) {
        BigDecimal itemsTotal = items.stream()
                .map(item -> BigDecimal.valueOf(productClient.getProduct(item.getProductId()).getPrice())
                        .multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return itemsTotal.add(OrderPricing.DELIVERY_FEE);
    }

    private String createRazorpayOrder(long amountInPaise, String receipt) {
        if (mockRazorpay) {
            return "order_mock_" + UUID.randomUUID().toString().replace("-", "");
        }
        try {
            JSONObject options = new JSONObject();
            options.put("amount", amountInPaise);
            options.put("currency", currency);
            options.put("receipt", receipt);
            options.put("payment_capture", 1);
            com.razorpay.Order order = razorpayClient.orders.create(options);
            return order.get("id");
        } catch (Exception ex) {
            logger.error("Razorpay order creation failed", ex);
            throw new IllegalStateException("Unable to create Razorpay order", ex);
        }
    }

    private boolean isValidSignature(PaymentVerificationRequest request) {
        if (mockRazorpay || (request.getRazorpayOrderId() != null && request.getRazorpayOrderId().startsWith("cod_"))) {
            return true;
        }
        try {
            String payload = request.getRazorpayOrderId() + "|" + request.getRazorpayPaymentId();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String generatedSignature = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return constantTimeEquals(generatedSignature, request.getRazorpaySignature());
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null || left.length() != right.length()) return false;
        int result = 0;
        for (int i = 0; i < left.length(); i++) {
            result |= left.charAt(i) ^ right.charAt(i);
        }
        return result == 0;
    }

    private PaymentOrderResponse toPaymentOrderResponse(Payment payment) {
        PaymentOrderResponse response = new PaymentOrderResponse();
        response.setKeyId(keyId);
        response.setRazorpayOrderId(payment.getRazorpayOrderId());
        response.setAmount(payment.getAmount());
        response.setAmountInPaise(payment.getAmount().multiply(BigDecimal.valueOf(100)).longValue());
        response.setCurrency(payment.getCurrency());
        response.setStatus(payment.getPaymentStatus());
        return response;
    }

    private PaymentVerificationResponse toVerificationResponse(Payment payment, Order order, String message) {
        PaymentVerificationResponse response = new PaymentVerificationResponse();
        response.setPaymentStatus(payment.getPaymentStatus());
        response.setOrder(order);
        response.setMessage(message);
        return response;
    }

    private void validateStock(List<OrderItemRequest> items) {
        for (OrderItemRequest item : items) {
            if (productClient.getProduct(item.getProductId()).getStockQuantity() < item.getQuantity()) {
                throw new IllegalStateException("Insufficient stock for product " + item.getProductId());
            }
        }
    }

    private void decrementStock(List<OrderItemRequest> items) {
        for (OrderItemRequest item : items) {
            productClient.decrementStock(item.getProductId(), item.getQuantity());
        }
    }
}
