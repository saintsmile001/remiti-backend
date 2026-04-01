# Rule: API & Integration Conventions
# Always On — applies to all external API integrations

## HTTP Client (OkHttpClient)
- Always use a single shared OkHttpClient instance per service (inject as @Bean)
- Always set connection timeout: 10 seconds, read timeout: 30 seconds
- Always close response bodies in a finally block or use try-with-resources
- Always check response.isSuccessful() before parsing the body
- Always parse error responses and include them in thrown exceptions

## External API Calls
- Always wrap external API calls in try-catch and map to custom exceptions
- Always log the API endpoint being called at DEBUG level (never log request bodies that contain tokens)
- Always add retry logic for 429 (rate limit) and 503 (service unavailable) responses — max 3 retries with exponential backoff
- Never make blocking external API calls inside @Scheduled methods without a timeout guard

## Gmail API
- Always use the Gmail message ID as sourceEmailId to prevent duplicate invoice processing
- Always check InvoiceRepository.findBySourceEmailId() before creating a new invoice from an email
- Always request only the minimum Gmail scopes needed (readonly for scanning, send for replies)
- Always handle pagination — Gmail API returns max 100 results per page

## Paystack API
- Always send amounts in kobo (Naira × 100) as an integer
- Always include invoiceId in the metadata field of every transaction
- Always use the test secret key (sk_test_) during development
- Always verify transactions via the verify endpoint before marking invoices as PAID

## Google Calendar API
- Always use the Africa/Lagos timezone for all events (WAT, UTC+1)
- Always format dates as RFC3339 (e.g. 2026-03-25T10:00:00+01:00)
- Always check if the user has an active gcal ConnectedTool before attempting calendar operations

## OpenAI API
- Always use model: gpt-4o
- Always set max_tokens: 500 for invoice extraction (response is just JSON)
- Always truncate email body to 2000 characters before sending to OpenAI
- Always expect and handle cases where OpenAI returns null fields in the JSON
- Always validate the JSON response before mapping to InvoiceExtractionResult

## Response Format
All API responses follow this structure:

Success:
{
  "data": { ... },
  "timestamp": "2026-03-25T10:00:00Z"
}

Error:
{
  "error": "Short description",
  "message": "Detailed message",
  "status": 400,
  "timestamp": "2026-03-25T10:00:00Z",
  "path": "/api/invoices/123"
}

List responses:
{
  "data": [ ... ],
  "total": 25,
  "timestamp": "2026-03-25T10:00:00Z"
}
