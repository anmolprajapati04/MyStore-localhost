package com.ecommerce.order.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SmtpStartupVerifier implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(SmtpStartupVerifier.class);

    private final JavaMailSender mailSender;

    @Value("${app.mail.enabled:true}")
    private boolean mailEnabled;

    @Value("${spring.mail.username:}")
    private String username;

    public SmtpStartupVerifier(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void run(String... args) {
        if (!mailEnabled) {
            logger.warn("Order confirmation mail is disabled (app.mail.enabled=false)");
            return;
        }
        if (!StringUtils.hasText(username)) {
            logger.error("SMTP_USERNAME is empty. Set SMTP_USERNAME and SMTP_PASSWORD in .env. Order confirmation emails will not be sent.");
            return;
        }
        if (!(mailSender instanceof JavaMailSenderImpl impl)) {
            logger.info("JavaMailSender is configured: {}", mailSender.getClass().getName());
            return;
        }
        if (!StringUtils.hasText(impl.getPassword())) {
            logger.error("SMTP_PASSWORD is empty for {}. Create a Gmail App Password and put it in SMTP_PASSWORD.", username);
            return;
        }
        try {
            impl.testConnection();
            logger.info("SMTP connection verified as {}", username);
        } catch (Exception ex) {
            logger.error("SMTP login failed for {}. Use a Gmail App Password (16 characters), not the Gmail account password. Google account > Security > 2-Step Verification > App passwords.", username, ex);
        }
    }
}
