package com.autoinvoice.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/session")
@Slf4j
public class AuthSessionController {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final UserAuthTokenRepository userAuthTokenRepository;

    @Value("${auth0.domain}")
    private String auth0Domain;

    @Value("${auth0.client-id}")
    private String auth0ClientId;

    @Value("${auth0.client-secret}")
    private String auth0ClientSecret;

    @Value("${auth0.login-redirect-uri}")
    private String auth0LoginRedirectUri;

    @Value("${auth0.audience:}")
    private String auth0Audience;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public AuthSessionController(OkHttpClient okHttpClient,
                                 ObjectMapper objectMapper,
                                 UserAuthTokenRepository userAuthTokenRepository) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
        this.userAuthTokenRepository = userAuthTokenRepository;
    }

    @GetMapping("/login")
    public void login(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(true);

        String state = randomState();
        session.setAttribute("auth0_state", state);

        String authorizeUrl =
                "https://" + auth0Domain + "/authorize" +
                "?response_type=code" +
                "&client_id=" + enc(auth0ClientId) +
                "&redirect_uri=" + enc(auth0LoginRedirectUri) +
                "&scope=" + enc("openid profile email offline_access") +
                (auth0Audience != null && !auth0Audience.isBlank() ? "&audience=" + enc(auth0Audience) : "") +
                "&state=" + enc(state);

        response.sendRedirect(authorizeUrl);
    }

    @GetMapping("/callback")
    public void callback(@RequestParam String code,
                         @RequestParam String state,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        if (session == null) {
            response.sendRedirect(frontendUrl + "/login?error=session_missing");
            return;
        }

        String expectedState = (String) session.getAttribute("auth0_state");
        if (expectedState == null || !expectedState.equals(state)) {
            response.sendRedirect(frontendUrl + "/login?error=invalid_state");
            return;
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("grant_type", "authorization_code");
        body.put("client_id", auth0ClientId);
        body.put("client_secret", auth0ClientSecret);
        body.put("code", code);
        body.put("redirect_uri", auth0LoginRedirectUri);

        Request tokenRequest = new Request.Builder()
                .url("https://" + auth0Domain + "/oauth/token")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response tokenResponse = okHttpClient.newCall(tokenRequest).execute()) {
            String responseBody = tokenResponse.body() != null ? tokenResponse.body().string() : "";

            if (!tokenResponse.isSuccessful()) {
                log.error("Auth0 code exchange failed: status={}, body={}", tokenResponse.code(), responseBody);
                response.sendRedirect(frontendUrl + "/login?error=token_exchange_failed");
                return;
            }

            JsonNode node = objectMapper.readTree(responseBody);

            String accessToken = node.path("access_token").asText(null);
            String idToken = node.path("id_token").asText(null);
            String refreshToken = node.hasNonNull("refresh_token")
                    ? node.get("refresh_token").asText()
                    : null;

            session.setAttribute("auth0_access_token", accessToken);
            session.setAttribute("auth0_id_token", idToken);

            if (refreshToken != null) {
                session.setAttribute("auth0_refresh_token", refreshToken);

                String userId = extractUserId(idToken);

                UserAuthToken token = userAuthTokenRepository
                        .findById(userId)
                        .orElse(new UserAuthToken());

                token.setUserId(userId);
                token.setRefreshToken(refreshToken);
                token.setUpdatedAt(LocalDateTime.now());

                if (token.getCreatedAt() == null) {
                    token.setCreatedAt(LocalDateTime.now());
                }

                userAuthTokenRepository.save(token);
            }

            response.sendRedirect(frontendUrl + "/connect-tools?login=success");
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("authenticated", false));
        }

        String accessToken = (String) session.getAttribute("auth0_access_token");
        String idToken = (String) session.getAttribute("auth0_id_token");

        if (accessToken == null || accessToken.isBlank() || idToken == null || idToken.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("authenticated", false));
        }

        try {
            String[] parts = idToken.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(payload);

            return ResponseEntity.ok(Map.of(
                    "authenticated", true,
                    "user", Map.of(
                            "sub", json.path("sub").asText(""),
                            "name", json.path("name").asText(""),
                            "email", json.path("email").asText(""),
                            "picture", json.path("picture").asText("")
                    )
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("authenticated", true, "user", Map.of()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("message", "logged out"));
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String randomState() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String extractUserId(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(payload);
            return json.get("sub").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract userId", e);
        }
    }
}