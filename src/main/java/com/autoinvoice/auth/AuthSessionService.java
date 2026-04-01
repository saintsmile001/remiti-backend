package com.autoinvoice.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.Base64;

@Service
public class AuthSessionService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String requireUserAccessToken(HttpServletRequest request) {
    	System.out.println("{{{{{{{{{{{{IN REQUIRED USER ACCESS TOKEN}}}}}}}}}}}}}");
    	System.out.println("checking request in required access token " + request);
        HttpSession session = request.getSession(false);
        if (session == null) {
        	System.out.println("No session available ++++++++++++++++");
            throw new UnauthorizedException("No session");
        }

        String token = (String) session.getAttribute("auth0_access_token");
        if (token == null) {
        	System.out.println("No Access token in request ++++++++++++++++");
            throw new UnauthorizedException("No access token");
        }

        return token;
    }

    public String requireUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new UnauthorizedException("No session");
        }

        String idToken = (String) session.getAttribute("auth0_id_token");
        if (idToken == null) {
            throw new UnauthorizedException("No id token");
        }

        try {
            String[] parts = idToken.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));

            JsonNode json = objectMapper.readTree(payload);

            return json.get("sub").asText(); 
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid token");
        }
    }
}