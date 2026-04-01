# SKILL: OpenAI Invoice Extractor
# Location: .agents/skills/invoice-extractor/SKILL.md
# Purpose: Specialist skill for building and improving the AI invoice extraction layer

## Role
You are an expert in prompt engineering for structured data extraction using OpenAI.
When this skill is active, your job is to build, test, and optimize the
InvoiceExtractorService.java and its OpenAI API prompts for maximum accuracy.

## What this skill covers
- Writing OpenAI API system prompts for invoice parsing
- Handling edge cases: multi-currency emails, partial invoice data, non-English emails
- Improving extraction accuracy with few-shot examples
- Validating and parsing OpenAI JSON responses safely

---

## The Core Extraction Prompt (Canonical Version)

Use this exact system prompt in InvoiceExtractorService.java:

```
You are an invoice data extractor for a business payment automation system in Nigeria.
Your job is to extract structured invoice data from emails.

Return ONLY a valid JSON object — no explanation, no markdown, no code blocks.

JSON format (strict):
{
  "clientName": "string or null",
  "clientEmail": "string or null",
  "amount": number or null,
  "currency": "NGN" or "USD" (use NGN if context is Nigerian or no currency specified),
  "dueDate": "YYYY-MM-DD" or null,
  "confidence": "HIGH" or "MEDIUM" or "LOW"
}

Confidence rules:
- HIGH: found amount + clientName + dueDate
- MEDIUM: found amount + at least one of clientName or dueDate
- LOW: could not find a clear invoice amount

Amount rules:
- Extract numeric value only (e.g. 150000 not "₦150,000")
- If amount is in kobo, convert to Naira (divide by 100)
- If multiple amounts, use the total/final amount
```

---

## Few-Shot Examples to Add When Accuracy Is Low

If extraction confidence is consistently LOW or MEDIUM, add these examples
to the system prompt (append after the rules above):

```
Example 1:
Email: "Subject: Invoice #1042 from TechBuild Ltd
Body: Dear client, please find invoice #1042 for ₦85,000 attached. Payment due by March 30, 2026."
Output: {"clientName": "TechBuild Ltd", "clientEmail": null, "amount": 85000, "currency": "NGN", "dueDate": "2026-03-30", "confidence": "HIGH"}

Example 2:
Email: "Subject: Payment Required
Body: Hi, your outstanding balance is $500 USD. Please settle by end of week. Regards, Acme Corp (billing@acme.com)"
Output: {"clientName": "Acme Corp", "clientEmail": "billing@acme.com", "amount": 500, "currency": "USD", "dueDate": null, "confidence": "MEDIUM"}

Example 3:
Email: "Subject: Meeting tomorrow
Body: Just wanted to confirm our 2pm meeting. Let me know if the time works."
Output: {"clientName": null, "clientEmail": null, "amount": null, "currency": "NGN", "dueDate": null, "confidence": "LOW"}
```

---

## Safe JSON Parsing Pattern

Always use this pattern in InvoiceExtractorService.java to prevent crashes:

```java
private InvoiceExtractionResult parseOpenAIResponse(String rawResponse) {
    try {
        // Strip accidental markdown fences
        String cleaned = rawResponse
            .replaceAll("```json\\s*", "")
            .replaceAll("```\\s*", "")
            .trim();

        // Validate it starts with { (basic sanity check)
        if (!cleaned.startsWith("{")) {
            log.warn("OpenAI response is not a JSON object: {}", cleaned.substring(0, Math.min(100, cleaned.length())));
            return null;
        }

        InvoiceExtractionResult result = objectMapper.readValue(cleaned, InvoiceExtractionResult.class);

        // Reject low confidence results
        if ("LOW".equals(result.confidence())) {
            log.info("Skipping email — OpenAI confidence is LOW");
            return null;
        }

        return result;

    } catch (JsonProcessingException e) {
        log.warn("Failed to parse OpenAI extraction response: {}", e.getMessage());
        return null;
    }
}
```

---

## Edge Cases to Handle

| Situation | How to Handle |
|-----------|---------------|
| Email in Yoruba/Igbo/Pidgin | OpenAI handles this natively — no changes needed |
| Amount as "five hundred thousand" (words) | Add to prompt: "Convert written amounts to numbers" |
| Multiple invoices in one email | Extract the largest/most recent amount |
| Forwarded email with quoted text | Truncate at first "--- Forwarded message ---" |
| PDF invoice attached (no body text) | Set confidence=LOW, log "PDF attachment detected — manual review needed" |
| HTML email with lots of tags | Strip HTML tags before sending to OpenAI using Jsoup |

---

## When to Use This Skill

Ask for this skill when:
- Invoice extraction accuracy is below 70%
- OpenAI is returning malformed JSON
- You need to add support for a new email format or language
- You want to add few-shot examples to the prompt
- You need to handle PDF attachments in a future iteration
