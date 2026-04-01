package com.autoinvoice.approval;

import com.autoinvoice.agent.AgentOrchestrator;
import com.autoinvoice.audit.ActionType;
import com.autoinvoice.audit.AgentAction;
import com.autoinvoice.audit.AgentActionRepository;
import com.autoinvoice.auth.AuthSessionService;
import com.autoinvoice.invoice.Invoice;
import com.autoinvoice.invoice.InvoiceRepository;
import com.autoinvoice.invoice.InvoiceStatus;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
@Slf4j
public class ApprovalController {

    private final InvoiceRepository invoiceRepository;
    private final AgentActionRepository agentActionRepository;
    private final AgentOrchestrator agentOrchestrator;
    private final AuthSessionService authSessionService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getInvoices(
            HttpServletRequest request,
            @RequestParam(required = false) InvoiceStatus status) {

        String userId = authSessionService.requireUserId(request);

        List<Invoice> invoices = (status != null)
                ? invoiceRepository.findByUserIdAndStatus(userId, status)
                : invoiceRepository.findByUserIdOrderByCreatedAtDesc(userId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", invoices);
        response.put("total", invoices.size());
        response.put("timestamp", LocalDateTime.now().toString());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{invoiceId}")
    public ResponseEntity<?> getInvoice(
            HttpServletRequest request,
            @PathVariable UUID invoiceId) {

        String userId = authSessionService.requireUserId(request);

        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new EntityNotFoundException("Invoice not found: " + invoiceId));

        if (!userId.equals(invoice.getUserId())) {
            return forbidden("You do not own this invoice");
        }

        List<AgentAction> actions = agentActionRepository.findByInvoiceId(invoiceId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("invoice", invoice);
        response.put("actions", actions);
        response.put("timestamp", LocalDateTime.now().toString());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{invoiceId}/approve")
    public ResponseEntity<?> approveInvoice(
            HttpServletRequest request,
            @PathVariable UUID invoiceId) {

        String userId = authSessionService.requireUserId(request);
        String userAuth0AccessToken = authSessionService.requireUserAccessToken(request);

        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new EntityNotFoundException("Invoice not found: " + invoiceId));

        if (!userId.equals(invoice.getUserId())) {
            return forbidden("You do not own this invoice");
        }

        if (invoice.getStatus() != InvoiceStatus.PENDING_APPROVAL) {
            return badRequest("Invoice is not pending approval. Current status: " + invoice.getStatus());
        }

        LocalDateTime now = LocalDateTime.now();

        AgentAction approvalAction = new AgentAction();
        approvalAction.setInvoiceId(invoiceId);
        approvalAction.setUserId(userId);
        approvalAction.setActionType(ActionType.USER_APPROVED);
        approvalAction.setRequiresApproval(true);
        approvalAction.setApprovedAt(now);
        approvalAction.setExecutedAt(now);
        approvalAction.setCreatedAt(now);
        agentActionRepository.save(approvalAction);

        log.info("User {} approved invoice {} amount={} currency={}",
                userId, invoiceId, invoice.getAmount(), invoice.getCurrency());

        agentOrchestrator.executePaymentFlow(userId, userAuth0AccessToken, invoice);

        Invoice updated = invoiceRepository.findById(invoiceId).orElse(invoice);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Invoice approved. Payment link sent.");
        response.put("invoice", updated);
        response.put("approvedAt", now.toString());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{invoiceId}/deny")
    public ResponseEntity<?> denyInvoice(
            HttpServletRequest request,
            @PathVariable UUID invoiceId) {

        String userId = authSessionService.requireUserId(request);

        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new EntityNotFoundException("Invoice not found: " + invoiceId));

        if (!userId.equals(invoice.getUserId())) {
            return forbidden("You do not own this invoice");
        }

        if (invoice.getStatus() != InvoiceStatus.PENDING_APPROVAL) {
            return badRequest("Invoice is not pending approval");
        }

        LocalDateTime now = LocalDateTime.now();

        invoice.setStatus(InvoiceStatus.DETECTED);
        invoice.setUpdatedAt(now);
        invoiceRepository.save(invoice);

        AgentAction denyAction = new AgentAction();
        denyAction.setInvoiceId(invoiceId);
        denyAction.setUserId(userId);
        denyAction.setActionType(ActionType.USER_DENIED);
        denyAction.setRequiresApproval(true);
        denyAction.setExecutedAt(now);
        denyAction.setCreatedAt(now);
        agentActionRepository.save(denyAction);

        log.info("User {} denied invoice {} amount={} currency={}",
                userId, invoiceId, invoice.getAmount(), invoice.getCurrency());

        return ResponseEntity.ok(Map.of(
                "message", "Invoice denied. No payment sent.",
                "invoiceId", invoiceId.toString(),
                "newStatus", "DETECTED",
                "timestamp", now.toString()
        ));
    }

    // ===== Helper methods =====

    private ResponseEntity<Map<String, Object>> forbidden(String message) {
        return ResponseEntity.status(403)
                .body(Map.of(
                        "error", "Access denied",
                        "message", message
                ));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest()
                .body(Map.of(
                        "error", "Invalid operation",
                        "message", message
                ));
    }
}