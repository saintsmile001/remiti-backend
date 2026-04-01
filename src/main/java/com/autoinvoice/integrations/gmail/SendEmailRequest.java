package com.autoinvoice.integrations.gmail;

public record SendEmailRequest(String toEmail, String subject, String htmlBody) {
}