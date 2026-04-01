package com.autoinvoice.integrations.calendar;

import com.autoinvoice.auth.AuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    private final GoogleCalendarService googleCalendarService;
    private final AuthSessionService authSessionService;

    public CalendarController(GoogleCalendarService googleCalendarService,
                              AuthSessionService authSessionService) {
        this.googleCalendarService = googleCalendarService;
        this.authSessionService = authSessionService;
    }

    @PostMapping("/follow-up")
    public ResponseEntity<?> schedule(@RequestBody FollowUpRequest body,
                                      HttpServletRequest request) {
        String userAuth0AccessToken = authSessionService.requireUserAccessToken(request);

        String eventId = googleCalendarService.scheduleFollowUpWithGoogleToken(
                userAuth0AccessToken,
                body.clientName(),
                body.clientEmail(),
                body.invoiceId(),
                body.amount()
        );

        return ResponseEntity.ok(eventId);
    }
}