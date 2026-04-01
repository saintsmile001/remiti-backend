package com.autoinvoice.integrations.paystack;

import com.autoinvoice.auth.ConnectedTool;
import com.autoinvoice.auth.ConnectedToolRepository;
import com.autoinvoice.security.EncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class PaystackService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final ConnectedToolRepository connectedToolRepository;
    private final EncryptionService encryptionService;
    private final String appBaseUrl;

    public PaystackService(OkHttpClient okHttpClient,
                           ObjectMapper objectMapper,
                           ConnectedToolRepository connectedToolRepository,
                           EncryptionService encryptionService,
                           @Value("${app.frontend-url:http://localhost:5173}") String appBaseUrl) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
        this.connectedToolRepository = connectedToolRepository;
        this.encryptionService = encryptionService;
        this.appBaseUrl = appBaseUrl;
    }

    public boolean validateSecretKey(String apiKey) {
        Request request = new Request.Builder()
                .url("https://api.paystack.co/bank")
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "empty";
                log.warn("Paystack key validation failed: {} - {}", response.code(), body);
                return false;
            }
            return true;
        } catch (IOException e) {
            log.warn("Failed to validate Paystack key due to network error", e);
            return false;
        }
    }

    public String createPaymentLink(String userId,
                                    String customerEmail,
                                    BigDecimal amountNgn,
                                    UUID invoiceId,
                                    String clientName) {
        ConnectedTool tool = connectedToolRepository
                .findByUserIdAndToolNameAndIsActiveTrue(userId, "paystack")
                .orElseThrow(() -> new IllegalStateException("Paystack not connected for user"));

        if (tool.getEncryptedApiKey() == null || tool.getEncryptedApiKey().isBlank()) {
            throw new IllegalStateException("No encrypted Paystack API key found");
        }

        String userSecretKey = encryptionService.decrypt(tool.getEncryptedApiKey());

        long amountKobo = amountNgn
                .multiply(BigDecimal.valueOf(100))
                .longValueExact();

        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("invoiceId", invoiceId.toString());
            metadata.put("clientName", clientName);
            metadata.put("source", "autoinvoice-agent");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("email", customerEmail);
            payload.put("amount", amountKobo);
            payload.put("currency", "NGN");
            payload.put("metadata", metadata);
            payload.put("callback_url", appBaseUrl + "/payment/callback");

            String requestBody = objectMapper.writeValueAsString(payload);

            Request request = new Request.Builder()
                    .url("https://api.paystack.co/transaction/initialize")
                    .header("Authorization", "Bearer " + userSecretKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    throw new RuntimeException("Failed to create payment link: " + response.code() + " " + responseBody);
                }

                JsonNode json = objectMapper.readTree(responseBody);
                String authorizationUrl = json.path("data").path("authorization_url").asText(null);

                if (authorizationUrl == null || authorizationUrl.isBlank()) {
                    throw new RuntimeException("Paystack did not return authorization_url");
                }

                return authorizationUrl;
            }
        } catch (IOException e) {
            throw new RuntimeException("Error creating payment link", e);
        }
    }
}