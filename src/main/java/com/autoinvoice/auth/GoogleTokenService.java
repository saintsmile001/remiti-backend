package com.autoinvoice.auth;

import com.autoinvoice.security.EncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;

@Service
@Slf4j
public class GoogleTokenService {

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final EncryptionService encryptionService;
    private final ConnectedToolRepository connectedToolRepository;

    @Value("${google.client-id}")
    private String googleClientId;

    @Value("${google.client-secret}")
    private String googleClientSecret;

    public GoogleTokenService(OkHttpClient okHttpClient,
                              ObjectMapper objectMapper,
                              EncryptionService encryptionService,
                              ConnectedToolRepository connectedToolRepository) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
        this.encryptionService = encryptionService;
        this.connectedToolRepository = connectedToolRepository;
    }

    /**
     * Get a fresh Google access token for a user.
     * Uses the stored encrypted refresh token to get a new access token from Google.
     */
    public String getFreshGoogleAccessToken(String userId) {
        ConnectedTool tool = connectedToolRepository
                .findByUserIdAndToolNameAndIsActiveTrue(userId, "gmail")
                .orElseThrow(() -> new TokenNotFoundException(
                        "Gmail not connected for user: " + userId));

        if (tool.getEncryptedApiKey() == null || tool.getEncryptedApiKey().isBlank()) {
            throw new TokenNotFoundException(
                    "No Google refresh token stored for user: " + userId);
        }

        String refreshToken = encryptionService.decrypt(tool.getEncryptedApiKey());
        return exchangeRefreshTokenForAccessToken(refreshToken);
    }

    /**
     * Exchange a Google refresh token for a fresh access token.
     */
    public String exchangeRefreshTokenForAccessToken(String refreshToken) {
        log.info("Exchanging Google refresh token for access token");

        RequestBody body = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", googleClientId)
                .add("client_secret", googleClientSecret)
                .add("refresh_token", refreshToken)
                .build();

        Request request = new Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(body)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Google token refresh failed: status={} body={}", response.code(), responseBody);
                throw new VaultException("Google token refresh failed: " + response.code());
            }

            JsonNode node = objectMapper.readTree(responseBody);
            String accessToken = node.path("access_token").asText();

            if (accessToken == null || accessToken.isBlank()) {
                throw new VaultException("Google returned empty access_token");
            }

            log.info("Google access token refreshed successfully, starts_with_ya29={}",
                    accessToken.startsWith("ya29."));
            return accessToken.trim();

        } catch (IOException e) {
            throw new VaultException("Failed to refresh Google token", e);
        }
    }

    /**
     * Store an encrypted Google refresh token for a user and tool.
     */
    public void storeGoogleRefreshToken(String userId, String toolName, String refreshToken) {
        ConnectedTool tool = connectedToolRepository
                .findByUserIdAndToolName(userId, toolName)
                .orElseGet(ConnectedTool::new);

        tool.setUserId(userId);
        tool.setToolName(toolName);
        tool.setVaultRef("google-direct");
        tool.setEncryptedApiKey(encryptionService.encrypt(refreshToken));
        tool.setConnectedAt(LocalDateTime.now());
        tool.setIsActive(true);

        connectedToolRepository.save(tool);
        log.info("Stored encrypted Google refresh token for user={} tool={}", userId, toolName);
    }
}
