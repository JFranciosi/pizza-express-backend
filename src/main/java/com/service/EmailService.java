package com.service;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class EmailService {

    private final Mailer mailer;
    private final String from;

    @Inject
    public EmailService(Mailer mailer,
            @ConfigProperty(name = "quarkus.mailer.from", defaultValue = "noreply@pizzaexpress.com") String from) {
        this.mailer = mailer;
        this.from = from;
    }

    public void sendPasswordResetEmail(String toEmail, String resetLink) {
        String subject = "Pizza Express - Password Reset";
        String body = "<h1>Password Reset Request</h1>" +
                "<p>We received a request to reset your password.</p>" +
                "<p>Click the link below to set a new password:</p>" +
                "<a href=\"" + resetLink + "\">Reset Password</a>" +
                "<p>If you did not request this, please ignore this email.</p>";

        mailer.send(Mail.withHtml(toEmail, subject, body).setFrom(from));
    }
}
