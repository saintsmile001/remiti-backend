# Workflow: /build-agent-core
# Trigger: type `/build-agent-core` in Antigravity
# Purpose: Build the OpenAI extractor + Agent Orchestrator (Steps 6 & 9)
# Run AFTER: /build-integrations

## What this workflow does
This is the brain of the project. The InvoiceExtractorService uses OpenAI to
parse invoice details from emails. The AgentOrchestrator ties everything together
in a continuous loop.

---

## PROMPT PART A — OpenAI Invoice Extractor (paste first)

Create InvoiceExtractorService.java in com.autoinvoice.agent

First create InvoiceExtractionResult.java as a Java record:
  String clientName, String clientEmail, BigDecimal amount,
  String currency, LocalDate dueDate, String confidence

Implement InvoiceExtractorService:

### InvoiceExtractionResult extractInvoiceDetails(EmailSummary email)

Build this exact system prompt (as a private constant):
"""
You are an invoice data extractor for a business payment automation system.
Extract invoice details from the email provided.
Return ONLY a valid JSON object — no explanation, no markdown, no code blocks.
JSON fields:
{
  "clientName": string or null,
  "clientEmail": string or null,
  "amount": number or null,
  "currency": "NGN" or "USD" (default NGN if Nigerian context),
  "dueDate": "YYYY-MM-DD" or null,
  "confidence": "HIGH" or "MEDIUM" or "LOW"
}
Set confidence to LOW if you cannot find an amount.
Set confidence to MEDIUM if you found an amount but missing client name or due date.
Set confidence to HIGH if you found amount, client name, and due date.
"""

Build the user message:
"Subject: {email.subject()}
From: {email.sender()} <{email.senderEmail()}>
Date: {email.receivedAt()}
Body (first 2000 chars): {email.fullBody().substring(0, min(2000, length))}"

Call OpenAI API:
POST https://api.openai.com/v1/chat/completions
Headers:
  Authorization: Bearer {openai.api-key}
  Content-Type: application/json
Body:
{
  "model": "${openai.model}",
  "max_tokens": 500,
  "messages": [
    { "role": "system", "content": "{systemPrompt}" },
    { "role": "user", "content": "{userMessage}" }
  ]
}

Parse response:
- Get choices[0].message.content from response JSON
- Strip any accidental markdown fences (```json ... ```)
- Parse into InvoiceExtractionResult using ObjectMapper
- If confidence is LOW → return null
- If parsing fails → log warning and return null

Inject openai.api-key and openai.model via @Value.
Use @Slf4j.
Log the extracted result at INFO level (confidence + amount only, not full email body).

---

## PROMPT PART B — Agent Orchestrator (paste after Part A is confirmed)

Create AgentOrchestrator.java in com.autoinvoice.agent

Inject these via constructor:
- ConnectedToolRepository
- InvoiceRepository
- AgentActionRepository
- GmailService
- InvoiceExtractorService
- PaystackService
- GoogleCalendarService
- @Value("${agent.approval-threshold-ngn}") BigDecimal approvalThreshold

Add @Component and @Slf4j.

### @Scheduled(fixedDelayString = "${agent.scan-interval-ms}") void runAgentCycle()
- Log "Starting agent scan cycle" at INFO
- Find all distinct userIds with active Gmail connections via ConnectedToolRepository
- For each userId, call processUserInvoices(userId) inside try-catch
- Log any per-user exceptions at ERROR but continue to next user
- Log "Agent cycle complete. Processed {n} users" at INFO

### void processUserInvoices(String userId)
Step 1 — Scan inbox:
  - Call GmailService.scanInboxForInvoices(userId)
  - For each EmailSummary, save AgentAction(type=EMAIL_READ, userId, executedAt=now)

Step 2 — Extract invoices:
  - For each EmailSummary, call InvoiceExtractorService.extractInvoiceDetails(email)
  - If result is null, skip
  - Build Invoice entity from result + email.messageId(), set status=DETECTED, userId
  - Save Invoice via InvoiceRepository
  - Save AgentAction(type=INVOICE_EXTRACTED, invoiceId, userId, executedAt=now, resultJson=result as JSON)

Step 3 — Route by amount:
  - For each newly saved Invoice:
    - If invoice.amount >= approvalThreshold:
      - Set status = PENDING_APPROVAL, save
      - Save AgentAction(type=APPROVAL_REQUESTED, requiresApproval=true, invoiceId, userId)
      - Log "Invoice {id} queued for approval: ₦{amount}"
    - Else:
      - Call executePaymentFlow(userId, invoice)

### void executePaymentFlow(String userId, Invoice invoice)
1. Call PaystackService.createPaymentLink(invoice.clientEmail, invoice.amount, invoice.id, invoice.clientName)
2. Update invoice.paystackPaymentUrl and invoice.status = PAYMENT_SENT, save
3. Build payment email:
   Subject: "Invoice Payment Request — {clientName}"
   Body: "Hi {clientName},\n\nPlease find your payment link below.\n\nAmount: ₦{amount}\nPayment Link: {url}\n\nThank you."
4. Call GmailService.sendEmail(userId, invoice.clientEmail, subject, body)
5. Save AgentAction(type=PAYMENT_LINK_SENT, invoiceId, userId, executedAt=now)
6. Check if user has active gcal connection via ConnectedToolRepository
7. If yes: call GoogleCalendarService.scheduleFollowUp(...)
   Save AgentAction(type=MEETING_SCHEDULED, invoiceId, userId, executedAt=now)

### @Async void triggerManualScan(String userId)
- Save AgentAction(type=SCAN_TRIGGERED, userId, executedAt=now)
- Call processUserInvoices(userId)

### @Scheduled(cron = "0 0 8 * * *") void markOverdueInvoices()
- Find all PAYMENT_SENT invoices where dueDate < today via InvoiceRepository
- For each: set status=OVERDUE, save, log at WARN level

---

## ✅ Done when:
- App starts with scheduler running
- Manual scan returns immediately (async) when called
- No compilation errors in orchestrator or extractor
