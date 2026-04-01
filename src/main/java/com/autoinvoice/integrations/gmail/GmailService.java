package com.autoinvoice.integrations.gmail;

import com.autoinvoice.invoice.InvoiceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@Slf4j
public class GmailService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String GMAIL_BASE = "https://gmail.googleapis.com/gmail/v1/users/me";

    private final InvoiceRepository invoiceRepository;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public GmailService(InvoiceRepository invoiceRepository,
                        OkHttpClient okHttpClient,
                        ObjectMapper objectMapper) {
        this.invoiceRepository = invoiceRepository;
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    // ===========================
    // SCAN INBOX
    // ===========================
    public List<EmailSummary> scanInboxForInvoicesWithGoogleToken(String googleAccessToken) {
        log.info("Scanning Gmail inbox for invoice-related emails");

        List<EmailSummary> summaries = new ArrayList<>();

        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(GMAIL_BASE + "/messages"))
                .newBuilder()
                .addQueryParameter("q", "invoice OR payment OR \"amount due\" OR \"attached invoice\"")
                .addQueryParameter("maxResults", "20")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + googleAccessToken)
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {

            if (!response.isSuccessful()) {
                log.error("Gmail list failed: {}", readBodySafely(response));
                return summaries;
            }

            JsonNode body = objectMapper.readTree(readBodySafely(response));
            JsonNode messages = body.get("messages");

            if (messages == null || !messages.isArray()) {
                log.info("No matching Gmail messages found");
                return summaries;
            }

            for (JsonNode node : messages) {
                String messageId = node.path("id").asText(null);
                if (messageId == null || messageId.isBlank()) continue;

                // skip already processed emails
                if (invoiceRepository.findBySourceEmailId(messageId).isPresent()) {
                    continue;
                }

                EmailSummary summary = fetchFullMessage(googleAccessToken, messageId);
                if (summary != null) {
                    summaries.add(summary);
                }
            }

        } catch (Exception e) {
            log.error("Error scanning Gmail inbox", e);
        }

        return summaries;
    }

    // ===========================
    // FETCH FULL MESSAGE
    // ===========================
    private EmailSummary fetchFullMessage(String googleAccessToken, String messageId) {

        Request request = new Request.Builder()
                .url(GMAIL_BASE + "/messages/" + messageId + "?format=full")
                .header("Authorization", "Bearer " + googleAccessToken)
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {

            if (!response.isSuccessful()) {
                log.warn("Failed to fetch message id={}: {}", messageId, response.code());
                return null;
            }

            JsonNode body = objectMapper.readTree(readBodySafely(response));

            String snippet = body.path("snippet").asText("");
            long internalDate = body.path("internalDate").asLong(Instant.now().toEpochMilli());

            LocalDateTime receivedAt = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(internalDate),
                    ZoneId.systemDefault()
            );

            JsonNode payload = body.get("payload");
            if (payload == null) return null;

            String subject = "";
            String senderFull = "";

            JsonNode headers = payload.get("headers");
            if (headers != null && headers.isArray()) {
                for (JsonNode header : headers) {
                    String name = header.path("name").asText("");
                    if ("Subject".equalsIgnoreCase(name)) {
                        subject = header.path("value").asText("");
                    } else if ("From".equalsIgnoreCase(name)) {
                        senderFull = header.path("value").asText("");
                    }
                }
            }

            ParsedSender sender = parseSender(senderFull);
            String fullBody = decodeEmailBody(payload);

            return new EmailSummary(
                    messageId,
                    subject,
                    sender.name(),
                    sender.email(),
                    snippet,
                    fullBody,
                    receivedAt
            );

        } catch (Exception e) {
            log.warn("Error processing message id={}", messageId, e);
            return null;
        }
    }

    // ===========================
    // SEND EMAIL
    // ===========================
    public boolean sendEmailWithGoogleToken(String googleAccessToken,
                                            String toEmail,
                                            String subject,
                                            String htmlBody) {

        log.info("Sending email to {}", toEmail);

        String rawEmail =
                "From: me\r\n" +
                "To: " + toEmail + "\r\n" +
                "Subject: " + subject + "\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n\r\n" +
                htmlBody;

        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(rawEmail.getBytes(StandardCharsets.UTF_8));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("raw", encoded);

        Request request = new Request.Builder()
                .url(GMAIL_BASE + "/messages/send")
                .header("Authorization", "Bearer " + googleAccessToken)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {

            if (response.isSuccessful()) {
                log.info("Email sent successfully");
                return true;
            } else {
                log.error("Failed to send email: {}", readBodySafely(response));
                return false;
            }

        } catch (IOException e) {
            log.error("Error sending email", e);
            return false;
        }
    }

    // ===========================
    // HELPERS
    // ===========================
    private String decodeEmailBody(JsonNode payload) {

        JsonNode body = payload.get("body");
        if (body != null && body.has("data")) {
            return decodeBase64(body.get("data").asText());
        }

        JsonNode parts = payload.get("parts");
        if (parts != null && parts.isArray()) {

            StringBuilder result = new StringBuilder();

            for (JsonNode part : parts) {
                String mime = part.path("mimeType").asText("");

                if ("text/plain".equals(mime) || "text/html".equals(mime)) {
                    JsonNode partBody = part.get("body");
                    if (partBody != null && partBody.has("data")) {
                        result.append(decodeBase64(partBody.get("data").asText()));
                    }
                } else if (part.has("parts")) {
                    result.append(decodeEmailBody(part));
                }
            }

            return result.toString();
        }

        return "";
    }

    private String decodeBase64(String data) {
        try {
            return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Base64 decode failed", e);
            return "";
        }
    }

    private ParsedSender parseSender(String senderFull) {
        if (senderFull == null || senderFull.isBlank()) {
            return new ParsedSender("", "");
        }

        if (senderFull.contains("<") && senderFull.contains(">")) {
            int start = senderFull.indexOf('<');
            int end = senderFull.indexOf('>');

            String email = senderFull.substring(start + 1, end).trim();
            String name = senderFull.substring(0, start).trim().replace("\"", "");

            return new ParsedSender(name, email);
        }

        return new ParsedSender(senderFull, senderFull);
    }

    private String readBodySafely(Response response) throws IOException {
        return response.body() != null ? response.body().string() : "";
    }

    private record ParsedSender(String name, String email) {}
}