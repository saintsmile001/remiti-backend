package com.autoinvoice.audit;

import com.autoinvoice.auth.AuthSessionService;
import com.autoinvoice.invoice.Invoice;
import com.autoinvoice.invoice.InvoiceRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/audit-log")
@RequiredArgsConstructor
@Slf4j
public class AuditLogController {

    private final AgentActionRepository agentActionRepository;
    private final InvoiceRepository invoiceRepository;
    private final AuthSessionService authSessionService;

    @GetMapping
    public ResponseEntity<?> getAuditLog(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) UUID invoiceId) {

        String userId = authSessionService.requireUserId(request);

       
        int safeLimit = Math.min(limit, 100);

        List<AgentAction> actions;

        if (invoiceId != null) {

            
            Optional<Invoice> invoiceOpt = invoiceRepository.findById(invoiceId);

            if (invoiceOpt.isEmpty() || !userId.equals(invoiceOpt.get().getUserId())) {
                return ResponseEntity.status(403)
                        .body(Map.of(
                                "error", "Access denied",
                                "message", "You do not own this invoice"
                        ));
            }

            actions = agentActionRepository.findByInvoiceId(invoiceId);

        } else {
            actions = agentActionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        }

        List<AgentAction> limited = actions.stream()
                .limit(safeLimit)
                .toList();

       
        Set<UUID> invoiceIds = limited.stream()
                .map(AgentAction::getInvoiceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, Invoice> invoiceMap = invoiceRepository.findAllById(invoiceIds)
                .stream()
                .collect(Collectors.toMap(Invoice::getId, i -> i));

        List<Map<String, Object>> enriched = limited.stream()
                .map(action -> {

                    Map<String, Object> entry = new LinkedHashMap<>();

                    entry.put("id", action.getId());
                    entry.put("actionType", action.getActionType());
                    entry.put("invoiceId", action.getInvoiceId());
                    entry.put("requiresApproval", action.getRequiresApproval());
                    entry.put("approvedAt", toString(action.getApprovedAt()));
                    entry.put("executedAt", toString(action.getExecutedAt()));
                    entry.put("errorMessage", action.getErrorMessage());
                    entry.put("createdAt", toString(action.getCreatedAt()));

                    if (action.getInvoiceId() != null) {
                        Invoice inv = invoiceMap.get(action.getInvoiceId());
                        if (inv != null) {
                            entry.put("clientName", inv.getClientName());
                            entry.put("amount", inv.getAmount());
                            entry.put("currency", inv.getCurrency());
                        }
                    }

                    return entry;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "data", enriched,
                "total", enriched.size(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @GetMapping("/summary")
    public ResponseEntity<?> getSummary(HttpServletRequest request) {

        String userId = authSessionService.requireUserId(request);

        List<AgentAction> all = agentActionRepository
                .findByUserIdOrderByCreatedAtDesc(userId);

        Map<String, Long> counts = all.stream()
                .collect(Collectors.groupingBy(
                        a -> a.getActionType().name(),
                        Collectors.counting()
                ));

        return ResponseEntity.ok(Map.of(
                "summary", counts,
                "totalActions", (long) all.size(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    

    private String toString(LocalDateTime time) {
        return time != null ? time.toString() : null;
    }
}