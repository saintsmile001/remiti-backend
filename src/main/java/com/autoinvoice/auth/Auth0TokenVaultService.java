package com.autoinvoice.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;

@Service
@Slf4j
public class Auth0TokenVaultService {

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    @Value("${auth0.domain}")
    private String domain;

    @Value("${auth0.client-id}")
    private String clientId;

    @Value("${auth0.client-secret}")
    private String clientSecret;

    // Cached management token
    private String cachedMgmtToken;
    private Instant mgmtTokenExpiry = Instant.EPOCH;

    public Auth0TokenVaultService(OkHttpClient okHttpClient,
                                  ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Get a Google access token for a user from Auth0 Token Vault.
     * Uses the Management API endpoint — no token exchange grant needed.
     *
     * @param auth0UserId the Auth0 user ID e.g. "google-oauth2|108672066599121291980"
     * @return a valid Google access token starting with "ya29."
     */
    public String getGoogleAccessToken(String auth0UserId) {
        log.info("Fetching Google access token from Token Vault for user={}",
                 auth0UserId);

        String mgmtToken = getManagementToken();

        String url = String.format("https://%s/api/v2/users/%s/federated-connections/google-oauth2/access-token", 
                domain, 
                auth0UserId.replace("|", "%7C"));

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer " + mgmtToken)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body() != null
                    ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Token Vault fetch failed: status={} body={}",
                          response.code(), body);
                throw new VaultException(
                    "Token Vault fetch failed: " + response.code() +
                    " " + body);
            }

            JsonNode node = objectMapper.readTree(body);
            String token = node.path("access_token").asText();

            if (token == null || token.isBlank()) {
                throw new VaultException(
                    "Token Vault returned empty access_token. " +
                    "Check that Token Vault is enabled and user " +
                    "has connected Google.");
            }

            // Validate it looks like a real Google token
            if (!token.startsWith("ya29.")) {
                log.warn("Token does not start with ya29. " +
                         "Prefix: {}",
                         token.substring(0, Math.min(10, token.length())));
            } else {
                log.info("Google token retrieved successfully, " +
                         "length={}", token.length());
            }

            return token;

        } catch (IOException e) {
            throw new VaultException(
                "Failed to call Token Vault API", e);
        }
    }

    /**
     * Get an Auth0 Management API token using client credentials.
     * Result is cached until 60 seconds before expiry.
     */
    private synchronized String getManagementToken() {
        if (cachedMgmtToken != null &&
            Instant.now().isBefore(mgmtTokenExpiry.minusSeconds(60))) {
            return cachedMgmtToken;
        }

        log.info("Fetching new Auth0 management token");

        RequestBody body = new FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("audience", "https://" + domain + "/api/v2/")
                .build();

        Request request = new Request.Builder()
                .url("https://" + domain + "/oauth/token")
                .post(body)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            String responseBody = response.body() != null
                    ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new VaultException(
                    "Management token fetch failed: " +
                    response.code() + " " + responseBody);
            }

            JsonNode node = objectMapper.readTree(responseBody);
            cachedMgmtToken = node.get("access_token").asText();
            long expiresIn  = node.path("expires_in").asLong(86400);
            mgmtTokenExpiry = Instant.now().plusSeconds(expiresIn);

            log.info("Management token cached, expires in {}s", expiresIn);
            return cachedMgmtToken;

        } catch (IOException e) {
            throw new VaultException(
                "Failed to fetch management token", e);
        }
    }
}