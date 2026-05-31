package my.edu.ukm.ftsm.ecommerce.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends OTP emails. When {@code app.mail.enabled=false} (default for local dev)
 * the OTP is logged instead of sent, so the flow works without SMTP credentials.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender; // may be null if not configured
    private final boolean enabled;
    private final String from;

    public EmailService(@org.springframework.beans.factory.annotation.Autowired(required = false) JavaMailSender mailSender,
                        @Value("${app.mail.enabled:false}") boolean enabled,
                        @Value("${app.mail.from:no-reply@ftsm.ukm.edu.my}") String from) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
    }

    public void sendOtp(String to, String code) {
        String subject = "Your FTSM Marketplace verification code";
        String text = "Your verification code is: " + code + "\nIt expires in 5 minutes.";

        if (!enabled || mailSender == null) {
            log.info("[OTP] (mail disabled) code for {} = {}", to, code);
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(text);
        mailSender.send(msg);
        log.info("[OTP] sent verification email to {}", to);
    }
}
