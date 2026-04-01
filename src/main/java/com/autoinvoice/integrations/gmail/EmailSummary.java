package com.autoinvoice.integrations.gmail;

import java.time.LocalDateTime;

public record EmailSummary(
    String messageId,
    String subject,
    String sender,
    String senderEmail,
    String bodySnippet,
    String fullBody,
    LocalDateTime receivedAt
) {}
