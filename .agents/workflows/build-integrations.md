# Workflow: /build-integrations
# Trigger: type `/build-integrations` in Antigravity
# Purpose: Build Gmail, Paystack, and Google Calendar integrations (Steps 5, 7, 8)
# Run AFTER: /build-token-vault

## What this workflow does
Builds all 3 external service integrations. Each one retrieves its OAuth token
from Auth0 Token Vault at runtime — tokens are never hardcoded or stored locally.

---

## PROMPT PART A — Gmail (paste first, confirm, then paste Part B)

Create GmailService.java in com.autoinvoice.integrations.gmail

First create EmailSummary.java as a Java record in the same package:
  String messageId, String subject, String sender,
  String senderEmail, String bodySnippet, String fullBody, LocalDateTime receivedAt

Then implement GmailService:

### List<EmailSummary> scanInboxForInvoices(String userId)
1. Call Auth0TokenVaultService.getVaultToken(userId, "gmail") to get access token
2. Call Gmail API list endpoint:
   GET https://gmail.googleapis.com/gmail/v1/users/me/messages
   Header: Authorization: Bearer {token}
   Params: q="invoice OR payment OR \"amount due\" OR \"attached invoice\"", maxResults=20
3. For each message ID returned:
   GET https://gmail.googleapis.com/gmail/v1/users/me/messages/{id}
   Params: format=full
   Parse: subject from headers, sender name, sender email, body (decode base64 payload)
4. Filter out already-processed emails:
   Call InvoiceRepository.findBySourceEmailId(messageId) — skip if present
5. Return list of EmailSummary objects

### boolean sendEmail(String userId, String toEmail, String subject, String htmlBody)
1. Get Gmail token from Token Vault
2. Build RFC 2822 email:
   From: me
   To: {toEmail}
   Subject: {subject}
   Content-Type: text/html
   Body: {htmlBody}
3. Base64URL encode the raw email
4. POST https://gmail.googleapis.com/gmail/v1/users/me/messages/send
   Body: { raw: base64EncodedEmail }
5. Return true on 200, false on error

Helper: private String decodeEmailBody(MessagePart payload) — handles base64 decoding of Gmail message parts.
Use @Slf4j. Inject Auth0TokenVaultService and InvoiceRepository.

---

## PROMPT PART B — Paystack (paste after Part A is confirmed)

Create PaystackService.java in com.autoinvoice.integrations.paystack

### String createPaymentLink(String customerEmail, BigDecimal amountNgn, UUID invoiceId, String clientName)
1. Convert to kobo: amountNgn.multiply(BigDecimal.valueOf(100)).intValue()
2. POST https://api.paystack.co/transaction/initialize
   Header: Authorization: Bearer {paystack.secret-key}
   Body:
   {
     "email": customerEmail,
     "amount": amountInKobo,
     "currency": "NGN",
     "metadata": { "invoiceId": invoiceId, "clientName": clientName },
     "callback_url": "http://localhost:8080/api/webhooks/paystack"
   }
3. Return response.data.authorization_url

### boolean verifyTransaction(String reference)
1. GET https://api.paystack.co/transaction/verify/{reference}
   Header: Authorization: Bearer {paystack.secret-key}
2. Return true if response.data.status == "success"

Then create PaystackWebhookController.java in the same package:

### POST /api/webhooks/paystack
1. Get raw request body as String
2. Compute HMAC-SHA512 of body using paystack.secret-key
3. Compare with X-Paystack-Signature header — return 401 if mismatch
4. Parse event JSON — only handle event = "charge.success"
5. Extract invoiceId from data.metadata.invoiceId
6. Find invoice in InvoiceRepository — skip if not found or already PAID
7. Update invoice status to PAID, set updatedAt = now()
8. Save AgentAction: type=PAYMENT_CONFIRMED, invoiceId, executedAt=now()
9. Return 200 OK always (Paystack retries on non-200)

Inject paystack.secret-key via @Value("${paystack.secret-key}").
Add @Slf4j.

---

## PROMPT PART C — Google Calendar (paste after Part B is confirmed)

Create GoogleCalendarService.java in com.autoinvoice.integrations.calendar

### String scheduleFollowUp(String userId, String clientName, String clientEmail, UUID invoiceId, BigDecimal amount)
1. Call Auth0TokenVaultService.getVaultToken(userId, "gcal")
2. Calculate event time: next business day (skip weekends) + 3 days, at 10:00 AM WAT
3. Format as RFC3339: "2026-03-25T10:00:00+01:00"
4. POST https://www.googleapis.com/calendar/v3/calendars/primary/events
   Header: Authorization: Bearer {gcal token}
   Body:
   {
     "summary": "Follow-up: Unpaid Invoice — {clientName}",
     "description": "Invoice ID: {invoiceId}\nAmount: ₦{amount}\nAction: Follow up on outstanding payment",
     "start": { "dateTime": startRfc3339, "timeZone": "Africa/Lagos" },
     "end": { "dateTime": endRfc3339 (30 mins later), "timeZone": "Africa/Lagos" },
     "attendees": [{ "email": clientEmail }]
   }
5. Return the created event ID from response.id

### Private helper: LocalDateTime calculateFollowUpDate()
- Start from today, add days one at a time
- Skip Saturday (6) and Sunday (7)
- Stop after 3 business days

Use ZonedDateTime with ZoneId.of("Africa/Lagos") for all date handling.
Add @Slf4j.
Log event ID after successful creation.

---

## ✅ Done when:
- All 3 service classes compile without errors
- PaystackWebhookController is mapped at POST /api/webhooks/paystack
- No hardcoded secrets anywhere
