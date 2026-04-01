# Workflow: /finalize-and-deploy
# Trigger: type `/finalize-and-deploy` in Antigravity
# Purpose: Final wiring, README, and deploy to Railway (Step 15)
# Run AFTER: /build-api-layer

## What this workflow does
Adds database indexes, writes the README, and prepares the project for
deployment to Railway so the Paystack webhook URL is a real public URL
(not localhost). This is required for the hackathon submission.

---

## PROMPT — paste this into Antigravity

Do the following final tasks:

### 1. Add database indexes
Create src/main/resources/schema-indexes.sql:
```sql
-- Run once after tables are created
CREATE INDEX IF NOT EXISTS idx_invoices_user_id ON invoices(user_id);
CREATE INDEX IF NOT EXISTS idx_invoices_status ON invoices(status);
CREATE INDEX IF NOT EXISTS idx_invoices_source_email ON invoices(source_email_id);
CREATE INDEX IF NOT EXISTS idx_agent_actions_user_id ON agent_actions(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_actions_invoice_id ON agent_actions(invoice_id);
CREATE INDEX IF NOT EXISTS idx_connected_tools_user_tool ON connected_tools(user_id, tool_name);
```

### 2. Add Dockerfile
Create Dockerfile in project root:
```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 3. Add railway.toml
Create railway.toml in project root:
```toml
[build]
builder = "dockerfile"

[deploy]
startCommand = "java -jar app.jar"
healthcheckPath = "/api/health"
healthcheckTimeout = 30
restartPolicyType = "on_failure"
```

### 4. Update application.yml for production
Add a prod profile section at the bottom of application.yml:
```yaml
---
spring:
  config:
    activate:
      on-profile: prod
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
```

### 5. Create README.md
Write a README.md with these exact sections:

# AutoInvoice Agent
> AI-powered invoice management with secure OAuth delegation via Auth0 Token Vault

## What it does
AutoInvoice Agent monitors your Gmail inbox for invoices, extracts details using
OpenAI, and handles the full payment follow-up lifecycle using Paystack —
all with OAuth tokens securely managed by Auth0 Token Vault.

## How Auth0 Token Vault is used
- User connects Gmail/Paystack/Google Calendar via OAuth through Auth0
- Auth0 Token Vault stores and manages all OAuth tokens — they never touch our database
- Agent retrieves tokens at runtime via the Token Vault API for each action
- High-value actions (>₦150,000) require explicit user step-up approval before execution

## Tech Stack
| Layer | Technology |
|-------|-----------|
| Backend | Java 21 + Spring Boot 3 |
| Frontend | Vue 3 + Tailwind CSS |
| AI | OpenAI API (gpt-4o) |
| Auth | Auth0 Token Vault |
| Database | PostgreSQL 16 |
| Payments | Paystack API |

## Prerequisites
- Java 21
- PostgreSQL 16
- Auth0 account (free tier works)
- Paystack account (test keys work)
- OpenAI API key

## Setup
```bash
git clone https://github.com/yourusername/autoinvoice-agent
cd autoinvoice-agent
cp .env.example .env
# Fill in all values in .env
mvn spring-boot:run
```

## API Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /api/health | Health check |
| GET | /api/auth/connect/{tool} | Start OAuth flow |
| GET | /api/auth/callback | OAuth callback |
| DELETE | /api/auth/disconnect/{tool} | Disconnect tool |
| GET | /api/auth/tools/status | Check connected tools |
| GET | /api/invoices | List invoices |
| GET | /api/invoices/{id} | Get invoice detail |
| POST | /api/invoices/{id}/approve | Approve payment (step-up auth) |
| POST | /api/invoices/{id}/deny | Deny payment action |
| GET | /api/audit-log | Full agent action log |
| GET | /api/audit-log/summary | Action count summary |
| POST | /api/agent/scan | Trigger manual scan |
| GET | /api/agent/status | Agent status + stats |
| POST | /api/webhooks/paystack | Paystack payment webhook |

## All requests require header: X-User-Id: {auth0-user-id}

---

## ✅ Final checklist before starting Vue frontend:

Run all of these in Postman — all must return valid JSON:

1. GET  http://localhost:8080/api/health
   Expected: { "status": "UP" }

2. GET  http://localhost:8080/api/auth/tools/status
   Header: X-User-Id: test-user-001
   Expected: [{ "toolName": "gmail", "connected": false }, ...]

3. GET  http://localhost:8080/api/invoices
   Header: X-User-Id: test-user-001
   Expected: { "data": [], "total": 0 }

4. GET  http://localhost:8080/api/audit-log
   Header: X-User-Id: test-user-001
   Expected: { "data": [], "total": 0 }

5. GET  http://localhost:8080/api/agent/status
   Header: X-User-Id: test-user-001
   Expected: { "pendingApprovals": 0, "totalInvoices": 0, ... }

6. POST http://localhost:8080/api/agent/scan
   Header: X-User-Id: test-user-001
   Expected: 202 { "message": "Scan started" }

**All 6 green = backend is done. Start Vue frontend.**
