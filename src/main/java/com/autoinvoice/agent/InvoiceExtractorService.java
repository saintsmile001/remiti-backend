package com.autoinvoice.agent;

import com.autoinvoice.integrations.gmail.EmailSummary;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class InvoiceExtractorService {

    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private final ObjectMapper objectMapper;
    private final OkHttpClient okHttpClient;

    @Value("${openai.api-key}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String openAiModel;

    private static final String SYSTEM_PROMPT = """
            You are an invoice data extractor for a business payment automation system in Nigeria.
            Extract invoice data from the email.

            Return ONLY one JSON object with this exact shape:
            {
              "clientName": "string or null",
              "clientEmail": "string or null",
              "amount": number or null,
              "currency": "NGN" or "USD",
              "dueDate": "YYYY-MM-DD" or null,
              "confidence": "HIGH" or "MEDIUM" or "LOW"
            }

            Rules:
            - Never return markdown.
            - Never return code fences.
            - Never return explanations.
            - If no currency is explicit and the context is Nigerian, use NGN.
            - Amount must be numeric only.
            - If multiple amounts appear, use the final total amount.
            - Confidence HIGH: amount + clientName + dueDate found
            - Confidence MEDIUM: amount + one of clientName or dueDate found
            - Confidence LOW: no clear invoice amount
            """;

    public InvoiceExtractionResult extractInvoiceDetails(EmailSummary email) {
        String userMessage = String.format(
                "Subject: %s%nFrom: %s <%s>%nDate: %s%nBody:%n%s",
                safe(email.subject()),
                safe(email.sender()),
                safe(email.senderEmail()),
                email.receivedAt(),
                truncate(email.fullBody(), 4000)
        );

        try {
            String requestJson = buildRequestJson(userMessage);

            Request request = new Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + openAiApiKey.trim().replaceAll("\\r\\n|\\r|\\n", ""))
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestJson, JSON))
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.warn("OpenAI request failed: status={}, body={}", response.code(), responseBody);
                    return null;
                }

                JsonNode rootNode = objectMapper.readTree(responseBody);
                JsonNode choices = rootNode.path("choices");

                if (!choices.isArray() || choices.isEmpty()) {
                    log.warn("OpenAI returned no choices");
                    return null;
                }

                String content = choices.get(0).path("message").path("content").asText(null);
                if (content == null || content.isBlank()) {
                    log.warn("OpenAI returned empty content");
                    return null;
                }

                InvoiceExtractionResult result = parseOpenAIResponse(content);

                if (result != null) {
                    log.info(
                            "Extracted invoice: confidence={}, amount={}, currency={}, clientEmail={}",
                            result.confidence(),
                            result.amount(),
                            result.currency(),
                            result.clientEmail()
                    );
                }

                return result;
            }

        } catch (Exception e) {
            log.warn("Failed to extract invoice details: {}", e.getMessage(), e);
            return null;
        }
    }

    private String buildRequestJson(String userMessage) throws IOException {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", openAiModel);
        requestBody.put("temperature", 0);
        requestBody.put("max_tokens", 500);
        requestBody.put("response_format", Map.of("type", "json_object"));
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userMessage)
        ));

        return objectMapper.writeValueAsString(requestBody);
    }

    private InvoiceExtractionResult parseOpenAIResponse(String rawResponse) {
        try {
            String cleaned = cleanModelOutput(rawResponse);
            String jsonObject = extractFirstJsonObject(cleaned);

            if (jsonObject == null) {
                log.warn("No JSON object found in model response");
                return null;
            }

            ObjectMapper mapper = objectMapper.copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            JsonNode node = mapper.readTree(jsonObject);

            InvoiceExtractionResult result = new InvoiceExtractionResult(
                    textOrNull(node, "clientName"),
                    textOrNull(node, "clientEmail"),
                    parseAmountNode(node.get("amount")),
                    textOrNull(node, "currency"),
                    parseDate(textOrNull(node, "dueDate")),
                    textOrNull(node, "confidence")
            );

            if (!result.isUsable()) {
                log.info(
                        "Skipping email because extraction is not usable. confidence={}, amount={}",
                        result.confidence(),
                        result.amount()
                );
                return null;
            }

            return result;

        } catch (Exception e) {
            log.warn("Failed to parse OpenAI extraction response: {}", e.getMessage());
            return null;
        }
    }

    private String cleanModelOutput(String rawResponse) {
        return rawResponse
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();
    }

    private String extractFirstJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, i + 1);
                    }
                }
            }
        }

        return null;
    }

    private BigDecimal parseAmountNode(JsonNode amountNode) {
        if (amountNode == null || amountNode.isNull()) {
            return null;
        }

        try {
            if (amountNode.isNumber()) {
                BigDecimal amount = amountNode.decimalValue();
                return amount.compareTo(BigDecimal.ZERO) > 0 ? amount : null;
            }

            String raw = amountNode.asText();
            if (raw == null || raw.isBlank()) {
                return null;
            }

            String normalized = raw.replaceAll("[^0-9.\\-]", "");
            if (normalized.isBlank()) {
                return null;
            }

            BigDecimal amount = new BigDecimal(normalized);
            return amount.compareTo(BigDecimal.ZERO) > 0 ? amount : null;
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseDate(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }

        String value = field.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(max, value.length()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}