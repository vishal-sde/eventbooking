package com.eventbooking.service;


public interface EmailProvider {


    void send(String to, String subject, String htmlBody);
}