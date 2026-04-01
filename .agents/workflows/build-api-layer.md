# Workflow: /build-api-layer
# Trigger: type `/build-api-layer` in Antigravity
# Purpose: Build all REST controllers + security config (Steps 10, 11, 12, 13, 14)
# Run AFTER: /build-agent-core

## What this workflow does
Builds the complete API surface — invoice approval, audit log, agent controls,
CORS config, and global exception handling. After this workflow, all endpoints
should be testable in Postman.

---

## PROMPT — paste this into Antigravity

Create the following 4 controllers and 2 config classes.

---

### 1. ApprovalController.java in com.autoinvoice.approval

GET /api/invoices
- Header: X-User-Id
- Optional query param: ?status=PENDING_APPROVAL
- Returns list of invoices for this user, newest first
- Each invoice response includes: id, clientName, clientEmail, amount, currency, status, dueDate, paystackPaymentUrl, createdAt

GET /api/invoices/{invoiceId}
- Header: X-User-Id
- Returns single invoice + its AgentAction history
- Validate userId owns this invoice → 403 if not
- Response: { invoice: {...}, actions: [...] }

POST /api/invoices/{invoiceId}/approve
- Header: X-User-Id
- Validate invoice exists → 404 if not
- Validate invoice.userId == requesting userId → 403 if not
- Validate invoice.status == PENDING_APPROVAL → 400 if not
- Call AgentOrchestrator.executePaymentFlow(userId, invoice)
- Save AgentAction(type=USER_APPROVED, approvedAt=now(), invoiceId, userId)
- Return updated invoice

POST /api/invoices/{invoiceId}/deny
- Header: X-User-Id
- Validate invoice exists and owned by user
- Validate status == PENDING_APPROVAL
- Set invoice.status = DETECTED (reset)
- Save AgentAction(type=USER_DENIED, invoiceId, userId)
- Return 200 { message: "Invoice action denied", invoiceId: "..." }

---

### 2. AuditLogController.java in com.autoinvoice.audit

GET /api/audit-log
- Header: X-User-Id
- Optional params: ?limit=50&invoiceId={uuid}
- Returns AuditLogEntry list: combines AgentAction with clientName from related Invoice
- AuditLogEntry record: id, actionType, invoiceId, clientName, amount, requiresApproval, approvedAt, executedAt, errorMessage, createdAt

GET /api/audit-log/summary
- Header: X-User-Id
- Returns count per ActionType for this user
- Response: { "EMAIL_READ": 12, "INVOICE_EXTRACTED": 8, "PAYMENT_LINK_SENT": 5, ... }

---

### 3. AgentController.java in com.autoinvoice.agent

POST /api/agent/scan
- Header: X-User-Id
- Calls AgentOrchestrator.triggerManualScan(userId) — this is @Async so returns immediately
- Returns 202 Accepted: { message: "Scan started", timestamp: now }

GET /api/agent/status
- Header: X-User-Id
- Returns:
  {
    pendingApprovals: count of PENDING_APPROVAL invoices for this user,
    totalInvoices: count of all invoices,
    paidInvoices: count of PAID invoices,
    overdueInvoices: count of OVERDUE invoices,
    connectedTools: [list from ConnectedToolRepository]
  }

---

### 4. SecurityConfig.java in com.autoinvoice.config

- Disable CSRF
- Permit all requests (stateless — auth via X-User-Id header for now)
- Configure CORS:
  - Allowed origins: http://localhost:5173, http://localhost:3000
  - Allowed methods: GET, POST, PUT, DELETE, OPTIONS
  - Allowed headers: *, X-User-Id
  - Allow credentials: true
  - Max age: 3600

---

### 5. GlobalExceptionHandler.java in com.autoinvoice.config

Use @RestControllerAdvice. Handle:

TokenNotFoundException → 401
  { error: "Tool not connected", message: exception.message, status: 401, timestamp, path }

VaultException → 502
  { error: "Auth0 Token Vault error", message: exception.message, status: 502, timestamp, path }

EntityNotFoundException → 404
  { error: "Not found", message: exception.message, status: 404, timestamp, path }

AccessDeniedException → 403
  { error: "Access denied", message: exception.message, status: 403, timestamp, path }

IllegalStateException → 400
  { error: "Invalid operation", message: exception.message, status: 400, timestamp, path }

Exception (catch-all) → 500
  { error: "Internal server error", message: exception.message, status: 500, timestamp, path }

Inject HttpServletRequest to get the request path for error responses.

---

### 6. RequestLoggingInterceptor.java in com.autoinvoice.config

Implement HandlerInterceptor:
- preHandle: record start time in request attribute
- afterCompletion: log method + path + status code + duration ms
- Format: "→ GET /api/invoices [test-user-001] 200 OK (45ms)"
- Register in WebMvcConfigurer for /api/**

---

## ✅ Done when all these Postman calls succeed:

GET  /api/health                        → 200 { status: "UP" }
GET  /api/auth/tools/status             → 200 [{ toolName: "gmail", connected: false }, ...]
GET  /api/invoices                      → 200 { data: [], total: 0 }
GET  /api/audit-log                     → 200 { data: [], total: 0 }
GET  /api/agent/status                  → 200 { pendingApprovals: 0, totalInvoices: 0, ... }
POST /api/agent/scan                    → 202 { message: "Scan started" }

All requests use header: X-User-Id: test-user-001
