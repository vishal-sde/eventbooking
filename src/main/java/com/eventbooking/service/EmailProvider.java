package com.eventbooking.service;

/**
 * Abstraction over "however we actually send an email". Kept separate from
 * EmailService so the transport (Brevo's HTTPS API today) can be swapped
 * without touching any of the email-composition logic.
 */
public interface EmailProvider {

    /**
     * Sends a single HTML email. Implementations should throw on failure —
     * EmailService already wraps calls in try/catch and logs, so this method
     * doesn't need to swallow errors itself.
     */
    void send(String to, String subject, String htmlBody);
}