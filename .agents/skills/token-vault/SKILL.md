# SKILL: Auth0 Token Vault Specialist
# Location: .agents/skills/token-vault/SKILL.md
# Purpose: Expert guidance for implementing and debugging Auth0 Token Vault integration

## Role
You are an Auth0 Token Vault integration specialist.
When this skill is active, your job is to correctly implement, debug, and explain
the Token Vault layer — the core requirement of the Authorized to Act Hackathon.

---

## What Token Vault Does (Explain to Judges)

Auth0 Token Vault is an identity-aware credential store.
Instead of your app storing OAuth tokens in a database (a security risk),
Token Vault stores them encrypted and tied to the Auth0 user identity.

Your agent retrieves tokens at runtime:
1. User authenticates → consents to Gmail/Paystack/GCal scopes
2. Auth0 stores the OAuth tokens in Token Vault, linked to the user's Auth0 ID
3. Your Spring Boot agent calls Token Vault API with a management token to retrieve
   the user's access token for a specific tool
4. The access token is used for one API call, then discarded — never stored

This is the "secure delegated access" pattern that judges are evaluating.

---

## Management Token — Correct Implementation

```java
@Service
@Slf4j
public class Auth0TokenVaultService {

    private String cachedMgmtToken;
    private Instant tokenExpiresAt;

    private synchronized String getManagementToken() {
        // Return cached token if still valid (with 60s buffer)
        if (cachedMgmtToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) {
            return cachedMgmtToken;
        }

        // Fetch new management token
        RequestBody body = new FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("audience", audience)
            .build();

        Request request = new Request.Builder()
            .url("https://" + domain + "/oauth/token")
            .post(body)
            .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            String json = response.body().string();
            JsonNode node = objectMapper.readTree(json);
            cachedMgmtToken = node.get("access_token").asText();
            long expiresIn = node.get("expires_in").asLong(); // usually 86400 (24hrs)
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn);
            log.info("Auth0 management token refreshed, expires in {}s", expiresIn);
            return cachedMgmtToken;
        }
    }
}
```

---

## Token Vault API — Correct Endpoints

### Store a token (after OAuth callback):
```
POST https://{domain}/api/v2/users/{userId}/credentials
Authorization: Bearer {management_token}
Content-Type: application/json

{
  "credential_type": "access_token",
  "name": "gmail",
  "token": "{access_token}",
  "refresh_token": "{refresh_token}",
  "expires_in": 3600,
  "scopes": ["https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.send"]
}

Response: { "id": "cred_abc123", ... }  ← this is your vaultRef
```

### Retrieve a token:
```
GET https://{domain}/api/v2/users/{userId}/credentials
Authorization: Bearer {management_token}

Response: [
  { "id": "cred_abc123", "name": "gmail", "access_token": "ya29...", ... },
  ...
]
```

### Delete a token:
```
DELETE https://{domain}/api/v2/users/{userId}/credentials/{credentialId}
Authorization: Bearer {management_token}
```

---

## Common Errors and Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| 401 on management token | Wrong audience URL | audience must be `https://{domain}/api/v2/` (trailing slash matters) |
| 403 on credentials endpoint | Missing Management API scope | In Auth0 Dashboard → Applications → your app → APIs → authorize Management API with `read:users`, `update:users` scopes |
| 404 on user credentials | userId format wrong | Use the full Auth0 user ID: `auth0|abc123` not just `abc123` |
| Token not found after storing | Wrong credential_type | Must be exactly `"access_token"` (lowercase) |
| 401 on Gmail API | Token expired | Token Vault auto-refreshes if refresh_token was stored; check refresh_token is not null |

---

## Step-Up Auth — Correct Pattern for Judges

This is what impresses judges most. Here's the exact flow:

```
1. Agent detects invoice for ₦250,000 (above threshold)
2. Agent sets invoice.status = PENDING_APPROVAL
3. Agent saves AgentAction(type=APPROVAL_REQUESTED, requiresApproval=true)
4. Vue frontend polls /api/agent/status → sees pendingApprovals: 1
5. Dashboard shows approval modal with invoice details
6. User clicks "Approve" → POST /api/invoices/{id}/approve
7. Backend validates ownership, validates status = PENDING_APPROVAL
8. Backend saves AgentAction(type=USER_APPROVED, approvedAt=now())
9. Backend calls executePaymentFlow() → Paystack link sent
10. Audit log shows full trail: DETECTED → APPROVAL_REQUESTED → USER_APPROVED → PAYMENT_SENT
```

Tell judges: "For financial actions above ₦150,000, we use step-up authorization.
The agent cannot proceed without explicit user consent — this mirrors how
human financial controls work, applied to AI agents."

---

## When to Use This Skill

Ask for this skill when:
- Auth0 management token calls are returning 401 or 403
- Token retrieval from vault is failing
- You need to explain the Token Vault pattern in your blog post or demo
- You need to implement token refresh logic
- You're debugging OAuth callback issues
