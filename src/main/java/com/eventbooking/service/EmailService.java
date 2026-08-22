package com.eventbooking.service;

import com.eventbooking.entity.Booking;
import com.eventbooking.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;


@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · h:mm a");

    private final EmailProvider emailProvider;

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Async
    public void sendPasswordChangedNotice(String toEmail) {
        String subject = "Your Evently password was changed";
        String body = wrap(
                "Password changed",
                "The password for " + escape(toEmail) + " was just reset. If this wasn't you, "
                        + "contact support immediately — your account may be at risk.",
                ""
        );
        send(toEmail, subject, body);
    }

    @Async
    public void sendPasswordResetEmail(String toEmail, String otp) {
        String subject = "Reset your Evently password";
        String details = """
                <div style="text-align:center;margin:22px 0">
                  <span style="display:inline-block;font:800 32px 'DM Sans',Arial,sans-serif;letter-spacing:.35em;padding:14px 22px;background:#f6f4ee;border-radius:12px">%s</span>
                </div>
                """.formatted(otp);
        String body = wrap(
                "Reset your password",
                "Use the code below to reset the password for " + escape(toEmail)
                        + ". It expires in 15 minutes. If you didn't request this, you can ignore this email.",
                details
        );
        send(toEmail, subject, body);
    }

    @Async
    public void sendOtpEmail(String toEmail, String otp) {
        String subject = "Verify your Evently account";
        String details = """
                <div style="text-align:center;margin:22px 0">
                  <span style="display:inline-block;font:800 32px 'DM Sans',Arial,sans-serif;letter-spacing:.35em;padding:14px 22px;background:#f6f4ee;border-radius:12px">%s</span>
                </div>
                """.formatted(otp);
        String body = wrap(
                "Verify your email",
                "Use the code below to verify " + escape(toEmail) + ". It expires in 10 minutes.",
                details
        );
        send(toEmail, subject, body);
    }

    @Async
    public void sendRegistrationConfirmation(User user) {
        String subject = "Welcome to Evently, " + user.getName() + "!";
        String body = wrap(
                "Welcome aboard, " + escape(user.getName()) + " 🎉",
                "Your Evently account is ready. Start browsing events and book your first experience whenever you're ready.",
                null
        );
        send(user.getEmail(), subject, body);
    }

    @Async
    public void sendBookingConfirmation(Booking booking) {
        User user = booking.getUser();
        var event = booking.getEvent();
        String subject = "Booking confirmed — " + event.getName();
        String details = """
                <table style="width:100%%;border-collapse:collapse;margin:18px 0;font-size:14px">
                    <tr><td style="padding:8px 0;color:#66706c">Booking ref</td><td style="padding:8px 0;text-align:right;font-weight:700">%s</td></tr>
                    <tr><td style="padding:8px 0;color:#66706c">Event</td><td style="padding:8px 0;text-align:right;font-weight:700">%s</td></tr>
                    <tr><td style="padding:8px 0;color:#66706c">Venue</td><td style="padding:8px 0;text-align:right">%s</td></tr>
                    <tr><td style="padding:8px 0;color:#66706c">Date &amp; time</td><td style="padding:8px 0;text-align:right">%s</td></tr>
                    <tr><td style="padding:8px 0;color:#66706c">Seats</td><td style="padding:8px 0;text-align:right">%d</td></tr>
                    <tr><td style="padding:8px 0;color:#66706c">Amount paid</td><td style="padding:8px 0;text-align:right;font-weight:700">₹%s</td></tr>
                </table>
                """.formatted(
                escape(booking.getBookingRef()),
                escape(event.getName()),
                escape(event.getVenue() + (event.getCity() != null ? ", " + event.getCity() : "")),
                event.getEventDate().format(DATE_FORMAT),
                booking.getSeatsBooked(),
                String.format("%.2f", booking.getTotalAmount())
        );
        String body = wrap(
                "You're going! 🎟️",
                "Your booking for <strong>" + escape(event.getName()) + "</strong> is confirmed. Show your booking reference at entry, or pull up your QR ticket from the Evently app.",
                details
        );
        send(user.getEmail(), subject, body);
    }

    private void send(String to, String subject, String htmlBody) {
        if (!mailEnabled) {
            log.info("[email disabled] Would send \"{}\" to {}", subject, to);
            return;
        }
        try {
            emailProvider.send(to, subject, htmlBody);
            log.info("Sent email \"{}\" to {}", subject, to);
        } catch (Exception ex) {
            log.error("Failed to send email \"{}\" to {}: {}", subject, to, ex.getMessage());
        }
    }

    private String wrap(String heading, String intro, String extraHtml) {
        return """
                <div style="font-family:'DM Sans',Arial,sans-serif;background:#f6f4ee;padding:32px">
                  <div style="max-width:520px;margin:0 auto;background:#fff;border-radius:16px;padding:32px;color:#17201d">
                    <p style="color:#66706c;font-weight:700;letter-spacing:.04em;text-transform:uppercase;font-size:12px;margin:0 0 8px">Evently</p>
                    <h1 style="font-size:22px;margin:0 0 14px">%s</h1>
                    <p style="font-size:14px;line-height:1.7;margin:0 0 4px">%s</p>
                    %s
                    <p style="color:#66706c;font-size:12px;margin-top:28px">See you there!<br>— The Evently team</p>
                  </div>
                </div>
                """.formatted(heading, intro, extraHtml == null ? "" : extraHtml);
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}