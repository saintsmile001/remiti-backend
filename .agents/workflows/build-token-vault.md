# Workflow: /build-token-vault
# Trigger: type `/build-token-vault` in Antigravity
# Purpose: Implement Auth0 Token Vault service + OAuth callback controller (Steps 3 & 4)
# Run AFTER: /start-backend-scaffold

## What this workflow does
Builds the Auth0 Token Vault integration — the most critical part of the project.
This is what the hackathon judges are specifically evaluating.

---

## PROMPT — paste this into Antigravity

Create Auth0TokenVaultService.java in com.autoinvoice.auth

This service manages all OAuth tokens through Auth0's Token Vault API.
Tokens are NEVER stored in PostgreSQL — only vault reference IDs are stored.

### Management Token (cached):
Method: String getManagementToken()
- POST https://{auth0.domain}/oauth/token
- Body: { grant_type: "client_credentials", client_id, client_secret, audience }
- Cache the token in a private field with its expiry time
- Refresh automatically when within 60 seconds of expiry
- Return the access_token string

### Retrieve token from vault:
Method: String getVaultToken(String userId, String toolName)
- GET https://{auth0.domain}/api/v2/users/{userId}/credentials
- Header: Authorization: Bearer {management token}
- Filter the credentials array by connection/toolName
- Return the access_token for the matching credential
- Throw TokenNotFoundException("Tool not connected: " + toolName) if not found

### Store token in vault:
Method: String storeVaultToken(String userId, String toolName, String accessToken, String refreshToken, Long expiresIn)
- POST https://{auth0.domain}/api/v2/users/{userId}/credentials
- Header: Authorization: Bearer {management token}
- Body: { credential_type: "access_token", name: toolName, token: accessToken, refresh_token: refreshToken, expires_in: expiresIn }
- Return the credential ID (use as vaultRef)

### Remove token from vault:
Method: void removeVaultToken(String userId, String vaultRef)
- DELETE https://{auth0.domain}/api/v2/users/{userId}/credentials/{vaultRef}
- Header: Authorization: Bearer {management token}

Use OkHttpClient (injected as @Bean from AppConfig).
Use @Value to inject auth0.domain, auth0.client-id, auth0.client-secret, auth0.audience.
Create custom exceptions: TokenNotFoundException, VaultException.
Add @Slf4j — log all vault operations at INFO, never log token values.

---

Then create OAuthController.java in com.autoinvoice.auth

### GET /api/auth/connect/{toolName}
- toolName: gmail | paystack | gcal
- Build OAuth URL for the tool:
  Gmail/GCal: https://accounts.google.com/o/oauth2/v2/auth
    params: client_id, redirect_uri (http://localhost:8080/api/auth/callback),
    response_type=code, access_type=offline, prompt=consent,
    scope (gmail: "https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.send")
    scope (gcal: "https://www.googleapis.com/auth/calendar.events")
    state: base64(userId + ":" + toolName)
- Return 302 redirect to OAuth URL

### GET /api/auth/callback
- Params: code, state
- Decode state to get userId and toolName
- Exchange code for tokens:
  POST https://oauth2.googleapis.com/token
  Body: code, client_id, client_secret, redirect_uri, grant_type=authorization_code
- Call Auth0TokenVaultService.storeVaultToken(...)
- Save ConnectedTool to database with the returned vaultRef
- Redirect to http://localhost:5173/connect-tools?status=success&tool={toolName}

### DELETE /api/auth/disconnect/{toolName}
- Header: X-User-Id
- Find ConnectedTool by userId + toolName
- Call Auth0TokenVaultService.removeVaultToken(userId, vaultRef)
- Set isActive = false on ConnectedTool, save
- Return 200 { message: "Disconnected successfully" }

### GET /api/auth/tools/status
- Header: X-User-Id
- Query ConnectedToolRepository for all active tools for userId
- Return: [{ toolName: "gmail", connected: true }, { toolName: "paystack", connected: false }, { toolName: "gcal", connected: false }]
- Always return all 3 tools even if not connected

---

## ✅ Done when:
- GET /api/auth/tools/status returns all 3 tools with connected: false
- No compilation errors
- Auth0TokenVaultService starts up without errors (management token fetch is lazy)
