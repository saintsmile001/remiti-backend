# Rule: Security & Secrets
# Always On — applies to every code generation task

## Secrets — NEVER expose these
- Never hardcode API keys, secrets, or passwords anywhere in source code
- Never log access tokens, refresh tokens, or OAuth codes at any log level
- Never log the X-User-Id header value at INFO level (only DEBUG)
- Never commit .env files — always use .env.example with empty values
- Never return Auth0 Token Vault internal reference IDs to the frontend
- Never expose Paystack secret keys in any frontend code or API response
- Never expose OpenAI API keys in any frontend code or API response

## Auth0 Token Vault
- Always retrieve tokens at runtime via Auth0TokenVaultService — never cache tokens in memory longer than their expires_in value
- Never store OAuth access tokens or refresh tokens in PostgreSQL — only store the Token Vault reference ID (vaultRef)
- Always validate that the requesting userId owns the ConnectedTool before retrieving its token
- Always scope token requests to the minimum required OAuth scopes

## Payment Security
- Always verify Paystack webhook signatures using X-Paystack-Signature header before processing
- Never process a webhook event more than once — check invoiceId idempotency before updating status
- Always validate that invoice amounts are positive and non-zero before creating payment links
- Never expose raw Paystack transaction references in API responses to unauthenticated endpoints

## API Security
- Always validate userId ownership before returning invoice data (user can only see their own invoices)
- Always validate userId ownership before executing any approval or denial action
- Never return another user's data regardless of what userId is passed in the header
- Always sanitize email content before passing to OpenAI API (strip HTML, limit to 2000 chars)

## Step-Up Auth
- Always require explicit user approval for invoices above ₦150,000
- Never auto-execute payment flows for PENDING_APPROVAL invoices without checking approvedAt timestamp
- Always log USER_APPROVED and USER_DENIED actions with timestamp and userId

## Environment
- Always use @Value("${property.name}") to inject config — never use System.getenv() directly
- Always fail fast on startup if required env variables are missing (use @PostConstruct validation)
- Always use different Paystack keys for test vs production (sk_test_ vs sk_live_)
