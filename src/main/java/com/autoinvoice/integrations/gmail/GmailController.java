package com.autoinvoice.integrations.gmail;

import com.autoinvoice.auth.Auth0TokenVaultService;
import com.autoinvoice.auth.AuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/gmail")
public class GmailController {

    private final GmailService gmailService;
    private final AuthSessionService authSessionService;
    private final Auth0TokenVaultService auth0TokenVaultService;

    public GmailController(GmailService gmailService, 
                           AuthSessionService authSessionService, 
                           Auth0TokenVaultService auth0TokenVaultService) {
        this.gmailService = gmailService;
        this.authSessionService = authSessionService;
        this.auth0TokenVaultService = auth0TokenVaultService;
    }

    @GetMapping("/scan")
    public ResponseEntity<?> scan(HttpServletRequest request) {
        // 1. Just get the userId (e.g., "google-oauth2|123...")
        String userId = authSessionService.requireUserId(request);
        
        // 2. Fetch Google Token directly via Management API
        // Note: Using the new method name from Claude's suggestion
        String googleToken = auth0TokenVaultService.getGoogleAccessToken(userId);
        
        // 3. Scan the mailbox
        return ResponseEntity.ok(gmailService.scanInboxForInvoicesWithGoogleToken(googleToken));
    }

    @PostMapping("/send")
    public ResponseEntity<?> send(@RequestBody SendEmailRequest body, HttpServletRequest request) {
        // 1. Identify the user
        String userId = authSessionService.requireUserId(request);
        
        // 2. Fetch Google Token directly
        String googleToken = auth0TokenVaultService.getGoogleAccessToken(userId);
        
        // 3. Send the email
        boolean sent = gmailService.sendEmailWithGoogleToken(
                googleToken,
                body.toEmail(),
                body.subject(),
                body.htmlBody()
        );
        return ResponseEntity.ok(sent);
    }
}