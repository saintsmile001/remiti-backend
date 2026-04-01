package com.autoinvoice.agent;

import com.autoinvoice.auth.GoogleTokenService;
import com.autoinvoice.auth.AuthSessionService;
import com.autoinvoice.auth.ConnectedToolRepository;
import com.autoinvoice.invoice.InvoiceRepository;
import com.autoinvoice.invoice.InvoiceStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentOrchestrator agentOrchestrator;
    private final InvoiceRepository invoiceRepository;
    private final ConnectedToolRepository connectedToolRepository;
    private final AuthSessionService authSessionService;
    private final GoogleTokenService googleTokenService;

    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> triggerScan(
            HttpServletRequest request) {

        String userId = authSessionService.requireUserId(request);

        // Get Google token by decrypting stored refresh token and calling Google directly
        String googleAccessToken =
            googleTokenService.getFreshGoogleAccessToken(userId);

        // Pass to orchestrator
        agentOrchestrator.triggerManualScan(userId, googleAccessToken);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Scan started");
        response.put("userId", userId);
        response.put("timestamp", LocalDateTime.now().toString());

        return ResponseEntity.accepted().body(response);
    }
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(HttpServletRequest request) {
        String userId = authSessionService.requireUserId(request);

        long pending = invoiceRepository
                .findByUserIdAndStatus(userId, InvoiceStatus.PENDING_APPROVAL).size();

        long total = invoiceRepository
                .findByUserIdOrderByCreatedAtDesc(userId).size();

        long paid = invoiceRepository
                .findByUserIdAndStatus(userId, InvoiceStatus.PAID).size();

        long overdue = invoiceRepository
                .findByUserIdAndStatus(userId, InvoiceStatus.OVERDUE).size();

        List<Map<String, Object>> tools = connectedToolRepository
                .findByUserIdAndIsActiveTrue(userId)
                .stream()
                .map(tool -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("toolName", tool.getToolName());
                    t.put("connectedAt", tool.getConnectedAt() != null ? tool.getConnectedAt().toString() : null);
                    t.put("active", tool.getIsActive());
                    return t;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pendingApprovals", pending);
        response.put("totalInvoices", total);
        response.put("paidInvoices", paid);
        response.put("overdueInvoices", overdue);
        response.put("connectedTools", tools);
        response.put("timestamp", LocalDateTime.now().toString());

        return ResponseEntity.ok(response);
    }
    
    
    @GetMapping("/agent/heartbeat")
    public ResponseEntity<Map<String, Object>> getAgentHeartbeat(HttpServletRequest request) {
        String userId = authSessionService.requireUserId(request);
        
        // Quick check: Is this user's AI agent currently running?
        boolean isScanning = agentOrchestrator.isUserBeingScanned(userId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", userId);
        response.put("isScanning", isScanning);
        response.put("status", isScanning ? "BUSY" : "IDLE");
        response.put("timestamp", LocalDateTime.now().toString());

        return ResponseEntity.ok(response);
    }
}