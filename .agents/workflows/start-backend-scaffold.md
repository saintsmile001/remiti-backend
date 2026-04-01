# Workflow: /start-backend-scaffold
# Trigger: type `/start-backend-scaffold` in Antigravity
# Purpose: Bootstrap the full Spring Boot project structure (Steps 1 & 2)

## What this workflow does
Sets up the complete Spring Boot 3 project skeleton including pom.xml,
application.yml, folder structure, JPA entities, and repositories.
Run this FIRST before any other workflow.

---

## PROMPT — paste this into Antigravity

Create a Java 21 Spring Boot 3 project called "autoinvoice-agent".

Base package: com.autoinvoice

### pom.xml dependencies (add all of these):
- spring-boot-starter-web
- spring-boot-starter-data-jpa
- spring-boot-starter-security
- spring-boot-starter-validation
- spring-boot-starter-scheduling
- postgresql (runtime scope)
- lombok
- com.squareup.okhttp3:okhttp:4.12.0
- jackson-databind
- io.github.cdimascio:dotenv-java:3.0.0

### application.yml — create with these exact placeholders:
```yaml
server:
  port: 8080

spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    database-platform: org.hibernate.dialect.PostgreSQLDialect

auth0:
  domain: ${AUTH0_DOMAIN}
  client-id: ${AUTH0_CLIENT_ID}
  client-secret: ${AUTH0_CLIENT_SECRET}
  audience: ${AUTH0_AUDIENCE}

gmail:
  client-id: ${GMAIL_CLIENT_ID}
  client-secret: ${GMAIL_CLIENT_SECRET}

paystack:
  secret-key: ${PAYSTACK_SECRET_KEY}
  base-url: https://api.paystack.co

google:
  calendar:
    client-id: ${GCAL_CLIENT_ID}
    client-secret: ${GCAL_CLIENT_SECRET}

openai:
  api-key: ${OPENAI_API_KEY}
  model: gpt-4o

agent:
  approval-threshold-ngn: 150000
  scan-interval-ms: 900000
```

### Create .env.example with all variables listed above as empty strings.

### Create this exact folder structure under src/main/java/com/autoinvoice/:
- auth/
- agent/
- integrations/gmail/
- integrations/paystack/
- integrations/calendar/
- invoice/
- audit/
- approval/
- config/

### Create these enums:
InvoiceStatus.java in com.autoinvoice.invoice:
  DETECTED, PENDING_APPROVAL, PAYMENT_SENT, PAID, OVERDUE

ActionType.java in com.autoinvoice.audit:
  EMAIL_READ, INVOICE_EXTRACTED, PAYMENT_LINK_SENT, MEETING_SCHEDULED,
  APPROVAL_REQUESTED, USER_APPROVED, USER_DENIED, SCAN_TRIGGERED, PAYMENT_CONFIRMED

### Create 3 JPA entities:

ConnectedTool.java (table: connected_tools)
- id: UUID PK auto-generated
- userId: String not null
- toolName: String not null (gmail | paystack | gcal)
- vaultRef: String not null
- connectedAt: LocalDateTime default now
- isActive: Boolean default true
- Unique constraint on (userId, toolName)

Invoice.java (table: invoices)
- id: UUID PK auto-generated
- userId: String not null
- clientName: String not null
- clientEmail: String
- amount: BigDecimal precision 12 scale 2
- currency: String default NGN
- dueDate: LocalDate nullable
- sourceEmailId: String
- paystackPaymentUrl: String
- status: InvoiceStatus enum default DETECTED
- createdAt: LocalDateTime default now
- updatedAt: LocalDateTime auto-updated

AgentAction.java (table: agent_actions)
- id: UUID PK auto-generated
- invoiceId: UUID nullable
- userId: String not null
- actionType: ActionType enum
- requiresApproval: Boolean default false
- approvedAt: LocalDateTime nullable
- executedAt: LocalDateTime nullable
- resultJson: String (store as text)
- errorMessage: String nullable
- createdAt: LocalDateTime default now

### Create JPA repositories:
ConnectedToolRepository:
  - findByUserIdAndToolNameAndIsActiveTrue(String userId, String toolName): Optional<ConnectedTool>
  - findByUserIdAndIsActiveTrue(String userId): List<ConnectedTool>

InvoiceRepository:
  - findByUserIdOrderByCreatedAtDesc(String userId): List<Invoice>
  - findByUserIdAndStatus(String userId, InvoiceStatus status): List<Invoice>
  - findBySourceEmailId(String sourceEmailId): Optional<Invoice>

AgentActionRepository:
  - findByUserIdOrderByCreatedAtDesc(String userId): List<AgentAction>
  - findByInvoiceId(UUID invoiceId): List<AgentAction>

### Add to AutoinvoiceAgentApplication.java:
- @EnableScheduling annotation

### Create health check:
GET /api/health → returns { "status": "UP", "timestamp": now, "version": "1.0.0" }

---

## ✅ Done when:
- `mvn spring-boot:run` starts without errors
- `curl http://localhost:8080/api/health` returns JSON
- Tables are auto-created in PostgreSQL on first run
