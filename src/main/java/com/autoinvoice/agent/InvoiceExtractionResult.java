package com.autoinvoice.agent;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvoiceExtractionResult(
        String clientName,
        String clientEmail,
        BigDecimal amount,
        String currency,
        LocalDate dueDate,
        String confidence
) {

    public InvoiceExtractionResult {
        clientName = normalizeBlank(clientName);
        clientEmail = normalizeBlank(clientEmail);
        currency = normalizeCurrency(currency);
        confidence = normalizeConfidence(confidence);
    }

    public boolean isHighConfidence() {
        return "HIGH".equals(confidence);
    }

    public boolean isMediumConfidence() {
        return "MEDIUM".equals(confidence);
    }

    public boolean isLowConfidence() {
        return "LOW".equals(confidence);
    }

    public boolean hasValidAmount() {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean hasClientEmail() {
        return clientEmail != null && !clientEmail.isBlank();
    }

    public boolean hasClientName() {
        return clientName != null && !clientName.isBlank();
    }

    public boolean hasDueDate() {
        return dueDate != null;
    }

    public boolean isUsable() {
        return !isLowConfidence() && hasValidAmount();
    }

    private static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    private static String normalizeCurrency(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NGN";
        }

        String upper = raw.trim().toUpperCase();

        return switch (upper) {
            case "NGN", "NAIRA", "NIGERIAN NAIRA" -> "NGN";
            case "USD", "DOLLAR", "US DOLLAR", "US DOLLARS" -> "USD";
            default -> "NGN";
        };
    }

    private static String normalizeConfidence(String raw) {
        if (raw == null || raw.isBlank()) {
            return "LOW";
        }

        String upper = raw.trim().toUpperCase();

        return switch (upper) {
            case "HIGH", "MEDIUM", "LOW" -> upper;
            default -> "LOW";
        };
    }
}