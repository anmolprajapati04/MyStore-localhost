package com.ecommerce.order.service;

import com.ecommerce.order.config.OrderPricing;
import com.ecommerce.order.event.OrderConfirmationMailEvent;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.ShippingAddress;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

@Service
public class EmailService {
    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);

    private final JavaMailSender mailSender;
    private final Executor mailExecutor;

    @Value("${app.mail.enabled:true}")
    private boolean mailEnabled;

    @Value("${app.mail.from:${spring.mail.username:}}")
    private String fromAddress;

    @Value("${spring.mail.username:}")
    private String smtpUsername;

    @Value("${app.support.email:${spring.mail.username:support@mystore.local}}")
    private String supportEmail;

    public EmailService(JavaMailSender mailSender, @Qualifier("mailExecutor") Executor mailExecutor) {
        this.mailSender = mailSender;
        this.mailExecutor = mailExecutor;
    }

    public OrderConfirmationMailEvent toMailEvent(Order order) {
        List<OrderItem> items = order.getOrderItems() == null ? List.of() : new ArrayList<>(order.getOrderItems());
        return new OrderConfirmationMailEvent(
                order.getId(),
                order.getCustomerName(),
                order.getCustomerEmail(),
                order.getTotalAmount(),
                order.getPaymentMethod(),
                copyAddress(order.getShippingAddress()),
                copyItems(items)
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderCommitted(OrderConfirmationMailEvent event) {
        mailExecutor.execute(() -> sendWithRetry(
                event.getOrderId(),
                event.getCustomerName(),
                event.getCustomerEmail(),
                event.getTotalAmount(),
                event.getPaymentMethod(),
                event.getAddress(),
                event.getItems()
        ));
    }

    private void sendWithRetry(Long orderId,
                               String customerName,
                               String customerEmail,
                               BigDecimal totalAmount,
                               String paymentMethod,
                               ShippingAddress address,
                               List<OrderItem> items) {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                sendOrderConfirmationNow(orderId, customerName, customerEmail, totalAmount, paymentMethod, address, items);
                return;
            } catch (Exception ex) {
                last = ex;
                logger.warn("Order #{} confirmation attempt {} failed: {}", orderId, attempt, ex.getMessage());
                try {
                    Thread.sleep(750L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        logger.error("Failed to send order confirmation to {} for order #{}", customerEmail, orderId, last);
    }

    private void sendOrderConfirmationNow(Long orderId,
                                          String customerName,
                                          String customerEmail,
                                          BigDecimal totalAmount,
                                          String paymentMethod,
                                          ShippingAddress address,
                                          List<OrderItem> items) throws Exception {
        if (!mailEnabled) {
            logger.warn("Mail disabled — confirmation for order #{} was not sent", orderId);
            return;
        }
        if (!isDeliverable(customerEmail)) {
            logger.warn("Order #{} confirmation skipped. Invalid customer email: {}", orderId, customerEmail);
            return;
        }
        if (isPlaceholderEmail(customerEmail)) {
            logger.warn("Order #{} confirmation skipped. Customer email looks like a placeholder: {}", orderId, customerEmail);
            return;
        }

        String from = StringUtils.hasText(fromAddress) ? fromAddress.trim() : trimToEmpty(smtpUsername);
        if (!isDeliverable(from)) {
            throw new IllegalStateException("MAIL_FROM / SMTP_USERNAME is missing or invalid");
        }

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(new InternetAddress(from, "MyStore", "UTF-8"));
        helper.setTo(customerEmail.trim());
        helper.setReplyTo(isDeliverable(supportEmail) ? supportEmail.trim() : from);
        helper.setSubject("MyStore order confirmation #" + orderId);
        helper.setText(buildOrderEmail(orderId, customerName, totalAmount, paymentMethod, address, items), true);
        mailSender.send(message);
        logger.info("Order confirmation email sent to {} for order #{}", customerEmail, orderId);
    }

    private boolean isDeliverable(String email) {
        return StringUtils.hasText(email) && EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    private boolean isPlaceholderEmail(String email) {
        String value = email.trim().toLowerCase(Locale.ROOT);
        return value.endsWith("@mystore.local")
                || value.endsWith("@example.com")
                || value.endsWith("@mystore.com");
    }

    private List<OrderItem> copyItems(List<OrderItem> source) {
        List<OrderItem> copies = new ArrayList<>();
        for (OrderItem item : source) {
            OrderItem copy = new OrderItem();
            copy.setProductName(item.getProductName());
            copy.setQuantity(item.getQuantity());
            copy.setSubtotal(item.getSubtotal());
            copy.setProductPrice(item.getProductPrice());
            copies.add(copy);
        }
        return copies;
    }

    private ShippingAddress copyAddress(ShippingAddress source) {
        if (source == null) {
            return new ShippingAddress();
        }
        ShippingAddress copy = new ShippingAddress();
        copy.setStreet(source.getStreet());
        copy.setCity(source.getCity());
        copy.setState(source.getState());
        copy.setZipCode(source.getZipCode());
        copy.setCountry(source.getCountry());
        return copy;
    }

    private String buildOrderEmail(Long orderId,
                                   String customerName,
                                   BigDecimal totalAmount,
                                   String paymentMethod,
                                   ShippingAddress shippingAddress,
                                   List<OrderItem> items) {
        java.text.NumberFormat formatter = java.text.NumberFormat.getCurrencyInstance(new Locale("en", "IN"));

        StringBuilder itemsHtml = new StringBuilder();
        BigDecimal itemsTotal = BigDecimal.ZERO;
        for (var item : items) {
            BigDecimal line = item.getSubtotal() == null ? BigDecimal.ZERO : item.getSubtotal();
            itemsTotal = itemsTotal.add(line);
            itemsHtml.append("<tr>")
                    .append("<td style='padding: 12px 0; border-bottom: 1px solid #edf2f7;'>")
                    .append("<div style='font-weight: 600; color: #1a202c;'>").append(escape(item.getProductName())).append("</div>")
                    .append("<div style='font-size: 12px; color: #718096;'>Qty: ").append(item.getQuantity()).append("</div>")
                    .append("</td>")
                    .append("<td style='padding: 12px 0; border-bottom: 1px solid #edf2f7; text-align: right; font-weight: 700; color: #1a202c;'>")
                    .append(formatter.format(line))
                    .append("</td>")
                    .append("</tr>");
        }

        String deliveryDate = LocalDate.now().plusDays(5).format(DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy"));
        String address = escape(safe(shippingAddress.getStreet())) + ", " +
                escape(safe(shippingAddress.getCity())) + ", " +
                escape(safe(shippingAddress.getState())) + " - " +
                escape(safe(shippingAddress.getZipCode()));
        String method = paymentMethod == null ? "Online" : paymentMethod.replace("_", " ");
        BigDecimal delivery = OrderPricing.DELIVERY_FEE;
        BigDecimal paid = totalAmount == null ? itemsTotal.add(delivery) : totalAmount;

        return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'></head>" +
                "<body style='margin: 0; padding: 0; background-color: #f7fafc; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif;'>" +
                "<div style='max-width: 600px; margin: 20px auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.05);'>" +
                "  <div style='background-color: #4f46e5; padding: 30px; text-align: center;'>" +
                "    <h1 style='color: #ffffff; margin: 0; font-size: 28px; font-weight: 800; letter-spacing: -0.5px;'>MyStore</h1>" +
                "    <p style='color: #c7d2fe; margin: 5px 0 0; font-size: 16px;'>Order Confirmation</p>" +
                "  </div>" +
                "  <div style='padding: 40px 30px;'>" +
                "    <h2 style='margin: 0 0 10px; color: #1a202c; font-size: 22px;'>Order Confirmed!</h2>" +
                "    <p style='color: #4a5568; line-height: 1.6; margin-bottom: 30px;'>Hello " + escape(safe(customerName)) + ", thank you for shopping with us. Your order has been successfully placed and is being prepared for shipment.</p>" +
                "    <div style='background-color: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 20px; margin-bottom: 30px;'>" +
                "      <div style='margin-bottom: 8px;'>" +
                "        <span style='color: #718096; font-size: 14px;'>Order ID:</span>" +
                "        <span style='color: #1a202c; font-weight: 700; font-size: 14px;'> #" + orderId + "</span>" +
                "      </div>" +
                "      <div>" +
                "        <span style='color: #718096; font-size: 14px;'>Estimated Delivery:</span>" +
                "        <span style='color: #10b981; font-weight: 700; font-size: 14px;'> " + deliveryDate + "</span>" +
                "      </div>" +
                "    </div>" +
                "    <table style='width: 100%; border-collapse: collapse; margin-bottom: 25px;'>" +
                "      <thead><tr><th style='text-align: left; padding-bottom: 10px; border-bottom: 2px solid #edf2f7; color: #718096; font-size: 12px; text-transform: uppercase;'>Item</th>" +
                "      <th style='text-align: right; padding-bottom: 10px; border-bottom: 2px solid #edf2f7; color: #718096; font-size: 12px; text-transform: uppercase;'>Amount</th></tr></thead>" +
                "      <tbody>" + itemsHtml +
                "      <tr><td style='padding: 12px 0; color: #718096;'>Delivery</td><td style='padding: 12px 0; text-align: right; font-weight: 700;'>" + formatter.format(delivery) + "</td></tr>" +
                "      </tbody>" +
                "    </table>" +
                "    <div style='text-align: right; margin-bottom: 30px;'>" +
                "      <span style='color: #718096; font-size: 16px; margin-right: 15px;'>Order total:</span>" +
                "      <span style='color: #4f46e5; font-size: 24px; font-weight: 800;'>" + formatter.format(paid) + "</span>" +
                "    </div>" +
                "    <div style='border-top: 1px solid #edf2f7; padding-top: 25px;'>" +
                "      <div style='margin-bottom: 16px;'><h4 style='margin: 0 0 8px; color: #718096; font-size: 12px; text-transform: uppercase;'>Delivery Address</h4><p style='margin: 0; color: #4a5568; font-size: 14px; line-height: 1.5;'>" + address + "</p></div>" +
                "      <div><h4 style='margin: 0 0 8px; color: #718096; font-size: 12px; text-transform: uppercase;'>Payment Method</h4><p style='margin: 0; color: #4a5568; font-size: 14px;'>" + escape(method) + "</p></div>" +
                "    </div>" +
                "  </div>" +
                "  <div style='background-color: #f7fafc; padding: 25px; text-align: center; border-top: 1px solid #edf2f7;'>" +
                "    <p style='margin: 0; color: #a0aec0; font-size: 12px;'>Questions? Contact our support team at <a href='mailto:" + supportEmail + "' style='color: #4f46e5; text-decoration: none;'>" + supportEmail + "</a></p>" +
                "    <p style='margin: 8px 0 0; color: #a0aec0; font-size: 12px;'>&copy; 2026 MyStore. All rights reserved.</p>" +
                "  </div>" +
                "</div></body></html>";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
