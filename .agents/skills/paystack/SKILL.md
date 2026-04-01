# SKILL: Paystack Integration Specialist
# Location: .agents/skills/paystack/SKILL.md
# Purpose: Expert guidance for Paystack payment links, webhooks, and Nigerian market context

## Role
You are a Paystack integration specialist focused on the Nigerian payments market.
When this skill is active, your job is to correctly implement Paystack payment
link generation, webhook verification, and transaction management.

---

## Key Rules (Always Apply)

- Always multiply Naira amounts by 100 to get kobo before sending to Paystack API
- Always use integer for amount (not decimal) — Paystack rejects decimals
- Always use `sk_test_` keys during development, never `sk_live_` until submission
- Always verify webhook signatures before processing — this is a security requirement
- Always check idempotency — never process the same invoiceId twice

---

## Create Payment Link — Correct Implementation

```java
public String createPaymentLink(String customerEmail, BigDecimal amountNgn,
                                 UUID invoiceId, String clientName) {
    // Convert to kobo (must be integer)
    int amountKobo = amountNgn.multiply(BigDecimal.valueOf(100)).intValue();

    String requestBody = objectMapper.writeValueAsString(Map.of(
        "email", customerEmail,
        "amount", amountKobo,
        "currency", "NGN",
        "metadata", Map.of(
            "invoiceId", invoiceId.toString(),
            "clientName", clientName,
            "source", "autoinvoice-agent"
        ),
        "callback_url", baseUrl + "/api/webhooks/paystack"
    ));

    Request request = new Request.Builder()
        .url("https://api.paystack.co/transaction/initialize")
        .addHeader("Authorization", "Bearer " + secretKey)
        .addHeader("Content-Type", "application/json")
        .post(RequestBody.create(requestBody, MediaType.get("application/json")))
        .build();

    try (Response response = okHttpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
            throw new PaystackException("Failed to create payment link: " + response.code());
        }
        JsonNode json = objectMapper.readTree(response.body().string());
        return json.path("data").path("authorization_url").asText();
    }
}
```

---

## Webhook Signature Verification — Correct Implementation

```java
@PostMapping("/api/webhooks/paystack")
public ResponseEntity<Void> handleWebhook(
        @RequestBody String rawBody,
        @RequestHeader("X-Paystack-Signature") String signature) {

    // Verify HMAC-SHA512 signature
    if (!isValidSignature(rawBody, signature)) {
        log.warn("Invalid Paystack webhook signature — rejecting");
        return ResponseEntity.status(401).build();
    }

    // Parse event
    JsonNode event = objectMapper.readTree(rawBody);
    String eventType = event.path("event").asText();

    if (!"charge.success".equals(eventType)) {
        return ResponseEntity.ok().build(); // Acknowledge but ignore
    }

    // Extract invoiceId from metadata
    String invoiceIdStr = event.path("data").path("metadata").path("invoiceId").asText();
    UUID invoiceId = UUID.fromString(invoiceIdStr);

    // Idempotency check — don't process twice
    Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
    if (invoice == null || invoice.getStatus() == InvoiceStatus.PAID) {
        return ResponseEntity.ok().build(); // Already processed
    }

    // Mark as paid
    invoice.setStatus(InvoiceStatus.PAID);
    invoice.setUpdatedAt(LocalDateTime.now());
    invoiceRepository.save(invoice);

    // Log action
    AgentAction action = new AgentAction();
    action.setInvoiceId(invoiceId);
    action.setUserId(invoice.getUserId());
    action.setActionType(ActionType.PAYMENT_CONFIRMED);
    action.setExecutedAt(LocalDateTime.now());
    agentActionRepository.save(action);

    log.info("Payment confirmed for invoice {} — ₦{}", invoiceId, invoice.getAmount());
    return ResponseEntity.ok().build(); // Always return 200 to Paystack
}

private boolean isValidSignature(String body, String signature) {
    try {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secretKey.getBytes(), "HmacSHA512"));
        byte[] hash = mac.doFinal(body.getBytes());
        String computed = bytesToHex(hash);
        return computed.equals(signature);
    } catch (Exception e) {
        log.error("Signature verification failed", e);
        return false;
    }
}

private String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) sb.append(String.format("%02x", b));
    return sb.toString();
}
```

---

## Testing Webhooks Locally

During development, Paystack cannot reach localhost.
Use this approach:

1. Install ngrok: `brew install ngrok` or download from ngrok.com
2. Run: `ngrok http 8080`
3. Copy the HTTPS URL e.g. `https://abc123.ngrok.io`
4. Update your .env: `APP_BASE_URL=https://abc123.ngrok.io`
5. Update application.yml to use `${APP_BASE_URL}` in the callback_url
6. In Paystack Dashboard → Settings → API Keys & Webhooks → set webhook URL to `https://abc123.ngrok.io/api/webhooks/paystack`

To simulate a payment locally:
- Use Paystack test card: 4084 0840 8408 4081, any future expiry, any CVV
- Or use Paystack test USSD: *737*1*100#

---

## Common Paystack Errors

| Error | Meaning | Fix |
|-------|---------|-----|
| `amount must be at least 100` | Amount in kobo is less than 100 (₦1) | Validate minimum ₦1 before calling API |
| `Invalid key` | Wrong secret key format | Must start with `sk_test_` or `sk_live_` |
| `Customer email is required` | clientEmail is null | Add validation — set fallback email if null |
| Webhook not received | Wrong webhook URL | Verify URL in Paystack Dashboard matches your ngrok URL |
| Duplicate charge | Webhook received twice | Idempotency check (status == PAID already) handles this |

---

## Test Cards for Paystack (Nigeria)

| Card Number | Scenario |
|-------------|---------|
| 4084 0840 8408 4081 | Successful payment |
| 4084 0840 8408 4090 | Declined payment |
| 5531 8866 5214 2950 | Mastercard success |

PIN: 0000 | OTP: 123456 | Expiry: any future date | CVV: any 3 digits

---

## When to Use This Skill

Ask for this skill when:
- Payment link generation is returning errors
- Webhook signature verification is failing
- You need to test with Paystack sandbox
- You're setting up ngrok for local webhook testing
- The invoice is not being marked as PAID after a test transaction
