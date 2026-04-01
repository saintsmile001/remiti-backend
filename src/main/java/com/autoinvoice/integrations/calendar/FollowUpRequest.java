package com.autoinvoice.integrations.calendar;

import java.math.BigDecimal;
import java.util.UUID;

public record FollowUpRequest(
        String clientName,
        String clientEmail,
        UUID invoiceId,
        BigDecimal amount
) {}
