package com.service;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@RunOnVirtualThread
public class EmailService {

    private final Mailer mailer;
    private final String from;

    private final io.quarkus.qute.Template passwordResetEmail;

    @Inject
    public EmailService(Mailer mailer,
            @ConfigProperty(name = "quarkus.mailer.from", defaultValue = "noreply@pizzaexpress.com") String from,
            @Location("PasswordResetEmail.html") Template passwordResetEmail) {
        this.mailer = mailer;
        this.from = from;
        this.passwordResetEmail = passwordResetEmail;
    }

    public void sendPasswordResetEmail(String toEmail, String resetLink) {
        String subject = "Pizza Express - Password Reset";
        String body = passwordResetEmail.data("resetLink", resetLink).render();

        mailer.send(Mail.withHtml(toEmail, subject, body).setFrom(from));
    }
}
