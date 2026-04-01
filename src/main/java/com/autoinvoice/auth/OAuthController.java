package com.autoinvoice.auth;

import com.autoinvoice.integrations.paystack.PaystackService;
import com.autoinvoice.security.EncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class OAuthController {

    private final ConnectedToolRepository connectedToolRepository;
    private final AuthSessionService authSessionService;
    private final PaystackService paystackService;
    private final EncryptionService encryptionService;
    private final GoogleTokenService googleTokenService;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${auth0.domain}")
    private String auth0Domain;

    @Value("${auth0.client-id}")
    private String auth0ClientId;

    @Value("${auth0.connection-redirect-uri}")
    private String auth0ConnectionRedirectUri;

    @Value("${google.client-id}")
    private String googleClientId;

    @Value("${google.client-secret}")
    private String googleClientSecret;

    @Value("${google.redirect-uri}")
    private String googleRedirectUri;

    public OAuthController(ConnectedToolRepository connectedToolRepository,
                           AuthSessionService authSessionService,
                           PaystackService paystackService,
                           EncryptionService encryptionService,
                           GoogleTokenService googleTokenService,
                           OkHttpClient okHttpClient,
                           ObjectMapper objectMapper) {
        this.connectedToolRepository = connectedToolRepository;
        this.authSessionService = authSessionService;
        this.paystackService = paystackService;
        this.encryptionService = encryptionService;
        this.googleTokenService = googleTokenService;
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }


    @GetMapping("/connect/{toolName}")
    public ResponseEntity<Void> connect(@PathVariable String toolName,
                                        HttpServletRequest request) {

        String userId = authSessionService.requireUserId(request);

        if ("paystack".equalsIgnoreCase(toolName)) {
            return ResponseEntity.badRequest().build();
        }

        String scope = buildGoogleScope(toolName);
        if (scope == null) {
            return ResponseEntity.badRequest().build();
        }

        String statePayload = userId + ":" + toolName.toLowerCase();
        String state = Base64.getUrlEncoder()
                .encodeToString(statePayload.getBytes(StandardCharsets.UTF_8));

        // Go DIRECTLY to Google OAuth — bypass Auth0 connection flow
        String googleAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + enc(googleClientId)
                + "&redirect_uri=" + enc(googleRedirectUri)
                + "&response_type=code"
                + "&access_type=offline"
                + "&prompt=consent"
                + "&scope=" + enc(scope)
                + "&state=" + enc(state);

        return ResponseEntity.status(302)
                .header("Location", googleAuthUrl)
                .build();
    }

    private String buildGoogleScope(String toolName) {
        return switch (toolName.toLowerCase()) {
            case "gmail" ->
                    "https://www.googleapis.com/auth/gmail.readonly " +
                    "https://www.googleapis.com/auth/gmail.send";
            case "gcal" ->
                    "https://www.googleapis.com/auth/calendar.events";
            default -> null;
        };
    }


    @PostMapping("/connect/paystack")
    public ResponseEntity<Map<String, String>> connectPaystack(@RequestBody PaystackConnectRequest body,
                                                               HttpServletRequest request) {
        String userId = authSessionService.requireUserId(request);

        String apiKey = body.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Paystack API key is required"));
        }

        if (!(apiKey.startsWith("sk_test_") || apiKey.startsWith("sk_live_"))) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid Paystack secret key format"));
        }

        boolean valid = paystackService.validateSecretKey(apiKey);
        if (!valid) {
            return ResponseEntity.badRequest().body(Map.of("message", "Paystack key could not be verified"));
        }

        Optional<ConnectedTool> existing =
                connectedToolRepository.findByUserIdAndToolName(userId, "paystack");

        ConnectedTool tool = existing.orElseGet(ConnectedTool::new);
        tool.setUserId(userId);
        tool.setToolName("paystack");
        tool.setVaultRef("manual-api-key");
        tool.setEncryptedApiKey(encryptionService.encrypt(apiKey));
        tool.setConnectedAt(LocalDateTime.now());
        tool.setIsActive(true);

        connectedToolRepository.save(tool);

        return ResponseEntity.ok(Map.of("message", "Paystack connected successfully"));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam String code,
                                         @RequestParam String state) {

        String decoded = new String(
                Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
        String[] parts = decoded.split(":", 2);

        if (parts.length < 2) {
            return redirectError("unknown");
        }

        String userId = parts[0];
        String toolName = parts[1].toLowerCase();

        try {
            // Exchange code for Google tokens DIRECTLY
            okhttp3.RequestBody tokenBody = new FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("client_id", googleClientId)
                    .add("client_secret", googleClientSecret)
                    .add("code", code)
                    .add("redirect_uri", googleRedirectUri)
                    .build();

            Request tokenRequest = new Request.Builder()
                    .url("https://oauth2.googleapis.com/token")
                    .post(tokenBody)
                    .build();

            String refreshToken;
            try (Response tokenResponse = okHttpClient.newCall(tokenRequest).execute()) {
                String body = tokenResponse.body() != null ? tokenResponse.body().string() : "";
                if (!tokenResponse.isSuccessful()) {
                    log.error("Google token exchange failed: {}", body);
                    return redirectError(toolName);
                }
                JsonNode tokens = objectMapper.readTree(body);
                refreshToken = tokens.path("refresh_token").asText();

                if (refreshToken == null || refreshToken.isBlank()) {
                    log.error("Google did not return a refresh_token. " +
                              "Ensure access_type=offline and prompt=consent are set.");
                    return redirectError(toolName);
                }
            }

            // Store encrypted refresh token
            googleTokenService.storeGoogleRefreshToken(userId, toolName, refreshToken);

            log.info("Google token stored for user={} tool={}", userId, toolName);

            return ResponseEntity.status(302)
                    .header("Location", frontendUrl +
                            "/connect-tools?status=success&tool=" + enc(toolName))
                    .build();

        } catch (Exception e) {
            log.error("Callback failed for tool={} user={}", toolName, userId, e);
            return redirectError(toolName);
        }
    }

    @DeleteMapping("/disconnect/{toolName}")
    public ResponseEntity<Map<String, String>> disconnect(@PathVariable String toolName,
                                                          HttpServletRequest request) {

        String userId = authSessionService.requireUserId(request);

        Optional<ConnectedTool> existing =
                connectedToolRepository.findByUserIdAndToolNameAndIsActiveTrue(userId, toolName.toLowerCase());

        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ConnectedTool tool = existing.get();
        tool.setIsActive(false);

        if ("paystack".equalsIgnoreCase(toolName)) {
            tool.setEncryptedApiKey(null);
        }

        connectedToolRepository.save(tool);

        return ResponseEntity.ok(Map.of("message", "Disconnected successfully"));
    }

    @GetMapping("/tools/status")
    public ResponseEntity<List<ToolStatusDto>> toolsStatus(HttpServletRequest request) {

        String userId = authSessionService.requireUserId(request);

        List<ConnectedTool> activeTools = connectedToolRepository.findByUserIdAndIsActiveTrue(userId);

        boolean gmail = activeTools.stream().anyMatch(t -> "gmail".equalsIgnoreCase(t.getToolName()));
        boolean gcal = activeTools.stream().anyMatch(t -> "gcal".equalsIgnoreCase(t.getToolName()));
        boolean paystack = activeTools.stream().anyMatch(t ->
                "paystack".equalsIgnoreCase(t.getToolName()) &&
                        t.getEncryptedApiKey() != null &&
                        !t.getEncryptedApiKey().isBlank()
        );

        return ResponseEntity.ok(List.of(
                new ToolStatusDto("gmail", gmail),
                new ToolStatusDto("gcal", gcal),
                new ToolStatusDto("paystack", paystack)
        ));
    }

    private ResponseEntity<Void> redirectError(String toolName) {
        return ResponseEntity.status(302)
                .header("Location", frontendUrl + "/connect-tools?status=error&tool=" + enc(toolName))
                .build();
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}