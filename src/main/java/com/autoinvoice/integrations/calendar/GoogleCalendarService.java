package com.autoinvoice.integrations.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@Slf4j
public class GoogleCalendarService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final ZoneId lagosZone = ZoneId.of("Africa/Lagos");

    public GoogleCalendarService(OkHttpClient okHttpClient,
                                 ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    public String scheduleFollowUpWithGoogleToken(String googleAccessToken,
                                                  String clientName,
                                                  String clientEmail,
                                                  UUID invoiceId,
                                                  BigDecimal amount) {
        log.info("Scheduling follow-up for invoice {}", invoiceId);

        LocalDateTime followUpLocal = calculateFollowUpDate();
        ZonedDateTime startZoned = followUpLocal.atZone(lagosZone);
        ZonedDateTime endZoned = startZoned.plusMinutes(30);

        String startRfc3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(startZoned);
        String endRfc3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(endZoned);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("summary", "Follow-up: Unpaid Invoice — " + safe(clientName));
        body.put(
                "description",
                "Invoice ID: " + invoiceId +
                "\nAmount: ₦" + amount +
                "\nAction: Follow up on outstanding payment"
        );

        ObjectNode startNode = body.putObject("start");
        startNode.put("dateTime", startRfc3339);
        startNode.put("timeZone", lagosZone.getId());

        ObjectNode endNode = body.putObject("end");
        endNode.put("dateTime", endRfc3339);
        endNode.put("timeZone", lagosZone.getId());

        if (clientEmail != null && !clientEmail.isBlank()) {
            ArrayNode attendees = body.putArray("attendees");
            attendees.addObject().put("email", clientEmail);
        }

        Request request = new Request.Builder()
                .url("https://www.googleapis.com/calendar/v3/calendars/primary/events")
                .header("Authorization", "Bearer " + googleAccessToken)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new RuntimeException(
                        "Failed to create Google Calendar event: " +
                        response.code() + " " + responseBody
                );
            }

            JsonNode json = objectMapper.readTree(responseBody);
            String eventId = json.path("id").asText();

            log.info("Successfully created GCal follow-up event: {}", eventId);
            return eventId;
        } catch (IOException e) {
            throw new RuntimeException("Error scheduling follow-up", e);
        }
    }

    private LocalDateTime calculateFollowUpDate() {
        ZonedDateTime now = ZonedDateTime.now(lagosZone);
        LocalDateTime date = now.toLocalDateTime();

        int businessDaysAdded = 0;
        while (businessDaysAdded < 3) {
            date = date.plusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY &&
                date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                businessDaysAdded++;
            }
        }

        return date.withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}