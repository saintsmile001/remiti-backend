package com.autoinvoice.audit;

public enum ActionType {
    EMAIL_READ, 
    INVOICE_EXTRACTED, 
    PAYMENT_LINK_SENT, 
    MEETING_SCHEDULED,
    APPROVAL_REQUESTED, 
    USER_APPROVED, 
    USER_DENIED, 
    SCAN_TRIGGERED, 
    PAYMENT_CONFIRMED
}
