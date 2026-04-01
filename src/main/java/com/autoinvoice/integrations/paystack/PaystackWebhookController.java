package com.autoinvoice.integrations.paystack;

import com.autoinvoice.audit.ActionType;
import com.autoinvoice.audit.AgentAction;
import com.autoinvoice.audit.AgentActionRepository;
import com.autoinvoice.invoice.Invoice;
import com.autoinvoice.invoice.InvoiceRepository;
import com.autoinvoice.invoice.InvoiceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@Slf4j
public class PaystackWebhookController {

    private final ObjectMapper objectMapper;
    private final InvoiceRepository invoiceRepository;
    private final AgentActionRepository agentActionRepository;
    private final String secretKey;

    public PaystackWebhookController(ObjectMapper objectMapper,
                                     InvoiceRepository invoiceRepository,
                                     AgentActionRepository agentActionRepository,
                                     @Value("${paystack.secret-key}") String secretKey) {
        this.objectMapper = objectMapper;
        this.invoiceRepository = invoiceRepository;
        this.agentActionRepository = agentActionRepository;
        this.secretKey = secretKey;
    }

    @PostMapping("/api/webhooks/paystack")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Paystack-Signature", required = false) String signature) {

        log.info("Received Paystack webhook event");

        if (signature == null || signature.isBlank()) {
            log.warn("Missing X-Paystack-Signature header");
            return ResponseEntity.status(401).build();
        }

        // TODO: In production, lookup the user's secret key from the database using metadata.invoiceId
        // or a custom header to verify the signature per-user if needed.
        // For now, we use the global secretKey for platform-wide webhook handling.
        if (!isValidSignature(rawBody, signature)) {
            log.warn("Invalid Paystack webhook signature");
            return ResponseEntity.status(401).build();
        }

        try {
            JsonNode event = objectMapper.readTree(rawBody);
            String eventType = event.path("event").asText();

            if (!"charge.success".equals(eventType)) {
                return ResponseEntity.ok().build();
            }

            JsonNode data = event.path("data");
            String reference = data.path("reference").asText(null);
            String status = data.path("status").asText(null);
            long amountKobo = data.path("amount").asLong(0);
            String currency = data.path("currency").asText(null);

            String invoiceIdStr = data.path("metadata").path("invoiceId").asText();
            if (invoiceIdStr == null || invoiceIdStr.isBlank()) {
                log.warn("Metadata invoiceId is missing");
                return ResponseEntity.ok().build();
            }

            UUID invoiceId = UUID.fromString(invoiceIdStr);

            Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
            if (invoice == null) {
                log.warn("Invoice not found for webhook: {}", invoiceId);
                return ResponseEntity.ok().build();
            }

            if (invoice.getStatus() == InvoiceStatus.PAID) {
                log.info("Invoice already marked as PAID: {}", invoiceId);
                return ResponseEntity.ok().build();
            }

            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setUpdatedAt(LocalDateTime.now());
            invoiceRepository.save(invoice);

            AgentAction action = new AgentAction();
            action.setInvoiceId(invoiceId);
            action.setUserId(invoice.getUserId());
            action.setActionType(ActionType.PAYMENT_CONFIRMED);
            action.setExecutedAt(LocalDateTime.now());
            action.setResultJson(String.format(
                    "{\"reference\":\"%s\",\"status\":\"%s\",\"amountKobo\":%d,\"currency\":\"%s\"}",
                    safe(reference), safe(status), amountKobo, safe(currency)
            ));
            agentActionRepository.save(action);

            log.info("Payment confirmed for invoice {} — reference={}", invoiceId, reference);

        } catch (Exception e) {
            log.error("Error processing Paystack webhook", e);
        }

        return ResponseEntity.ok().build();
    }

    private boolean isValidSignature(String body, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String computed = bytesToHex(hash);
            return computed.equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.error("Signature verification failed", e);
            return false;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}