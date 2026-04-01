package com.autoinvoice.agent;

import com.autoinvoice.audit.ActionType;
import com.autoinvoice.audit.AgentAction;
import com.autoinvoice.audit.AgentActionRepository;
import com.autoinvoice.auth.GoogleTokenService;
import com.autoinvoice.auth.ConnectedTool;
import com.autoinvoice.auth.ConnectedToolRepository;
import com.autoinvoice.auth.UserAuthToken;
import com.autoinvoice.auth.UserAuthTokenRepository;
import com.autoinvoice.integrations.calendar.GoogleCalendarService;
import com.autoinvoice.integrations.gmail.EmailSummary;
import com.autoinvoice.integrations.gmail.GmailService;
import com.autoinvoice.integrations.paystack.PaystackService;
import com.autoinvoice.invoice.Invoice;
import com.autoinvoice.invoice.InvoiceRepository;
import com.autoinvoice.invoice.InvoiceStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AgentOrchestrator {

	private final ConnectedToolRepository connectedToolRepository;
	private final InvoiceRepository invoiceRepository;
	private final AgentActionRepository agentActionRepository;
	private final GmailService gmailService;
	private final InvoiceExtractorService invoiceExtractorService;
	private final PaystackService paystackService;
	private final GoogleCalendarService googleCalendarService;
	private final GoogleTokenService googleTokenService;
	private final UserAuthTokenRepository userAuthTokenRepository;
	private final BigDecimal approvalThreshold;
	private final ObjectMapper objectMapper;
	private final Set<String> activeScans = Collections.newSetFromMap(new ConcurrentHashMap<>());

	public AgentOrchestrator(ConnectedToolRepository connectedToolRepository, InvoiceRepository invoiceRepository,
			AgentActionRepository agentActionRepository, GmailService gmailService,
			InvoiceExtractorService invoiceExtractorService, PaystackService paystackService,
			GoogleCalendarService googleCalendarService, GoogleTokenService googleTokenService,
			UserAuthTokenRepository userAuthTokenRepository,
			@Value("${agent.approval-threshold-ngn}") BigDecimal approvalThreshold, ObjectMapper objectMapper) {
		this.connectedToolRepository = connectedToolRepository;
		this.invoiceRepository = invoiceRepository;
		this.agentActionRepository = agentActionRepository;
		this.gmailService = gmailService;
		this.invoiceExtractorService = invoiceExtractorService;
		this.paystackService = paystackService;
		this.googleCalendarService = googleCalendarService;
		this.googleTokenService = googleTokenService;
		this.userAuthTokenRepository = userAuthTokenRepository;
		this.approvalThreshold = approvalThreshold;
		this.objectMapper = objectMapper;
	}

	//@Scheduled(fixedDelayString = "${agent.scan-interval-ms}")
	public void runAgentCycle() {
	    log.info("Starting agent scan cycle");

	    List<ConnectedTool> gmailTools =
	        connectedToolRepository.findByToolNameAndIsActiveTrue("gmail");

	    Set<String> userIds = gmailTools.stream()
	            .map(ConnectedTool::getUserId)
	            .collect(Collectors.toSet());

	    for (String userId : userIds) {
	        try {
	            // Decrypt stored refresh token and exchange with Google directly
	            String googleAccessToken =
	                googleTokenService.getFreshGoogleAccessToken(userId);

	            processUserInvoicesWithGoogleToken(userId, googleAccessToken);

	        } catch (Exception e) {
	            log.error("Error processing user={}: {}",
	                      userId, e.getMessage(), e);
	        }
	    }

	    log.info("Agent cycle complete. Processed {} users", userIds.size());
	}

	public void processUserInvoicesWithGoogleToken(String userId, String googleAccessToken) {
		List<EmailSummary> emails = gmailService.scanInboxForInvoicesWithGoogleToken(googleAccessToken);

		for (EmailSummary email : emails) {
			AgentAction action = new AgentAction();
			action.setActionType(ActionType.EMAIL_READ);
			action.setUserId(userId);
			action.setExecutedAt(LocalDateTime.now());
			try {
				action.setResultJson(objectMapper.writeValueAsString(email.messageId()));
			} catch (JsonProcessingException ignored) {
			}
			agentActionRepository.save(action);

			InvoiceExtractionResult result = invoiceExtractorService.extractInvoiceDetails(email);
			if (result == null) {
				continue;
			}

			Invoice invoice = new Invoice();
			invoice.setUserId(userId);
			invoice.setClientName(result.clientName());
			invoice.setClientEmail(result.clientEmail());
			invoice.setAmount(result.amount());
			invoice.setCurrency(result.currency());
			invoice.setDueDate(result.dueDate());
			invoice.setSourceEmailId(email.messageId());
			invoice.setStatus(InvoiceStatus.DETECTED);

			invoice = invoiceRepository.save(invoice);

			AgentAction extractAction = new AgentAction();
			extractAction.setActionType(ActionType.INVOICE_EXTRACTED);
			extractAction.setInvoiceId(invoice.getId());
			extractAction.setUserId(userId);
			extractAction.setExecutedAt(LocalDateTime.now());
			try {
				extractAction.setResultJson(objectMapper.writeValueAsString(result));
			} catch (JsonProcessingException ignored) {
			}
			agentActionRepository.save(extractAction);

			if (invoice.getAmount() != null && invoice.getAmount().compareTo(approvalThreshold) >= 0) {
				invoice.setStatus(InvoiceStatus.PENDING_APPROVAL);
				invoiceRepository.save(invoice);

				AgentAction approvalAction = new AgentAction();
				approvalAction.setActionType(ActionType.APPROVAL_REQUESTED);
				approvalAction.setRequiresApproval(true);
				approvalAction.setInvoiceId(invoice.getId());
				approvalAction.setUserId(userId);
				approvalAction.setExecutedAt(LocalDateTime.now());
				agentActionRepository.save(approvalAction);

				log.info("Invoice {} queued for approval: ₦{}", invoice.getId(), invoice.getAmount());
			} else {
				executePaymentFlow(userId, googleAccessToken, invoice);
			}
		}
	}

	public void executePaymentFlow(String userId, String googleAccessToken, Invoice invoice) {
		if (InvoiceStatus.PENDING_APPROVAL.equals(invoice.getStatus())) {
			List<AgentAction> actions = agentActionRepository.findByInvoiceId(invoice.getId());
			boolean hasApproval = actions.stream().anyMatch(a -> ActionType.USER_APPROVED.equals(a.getActionType()));
			if (!hasApproval) {
				log.warn("Cannot execute payment flow for PENDING_APPROVAL invoice {} without USER_APPROVED action",
						invoice.getId());
				return;
			}
		}

		String paymentUrl = paystackService.createPaymentLink(userId, invoice.getClientEmail(), invoice.getAmount(),
				invoice.getId(), invoice.getClientName());

		invoice.setPaystackPaymentUrl(paymentUrl);
		invoice.setStatus(InvoiceStatus.PAYMENT_SENT);
		invoiceRepository.save(invoice);

		String subject = "Invoice Payment Request — " + invoice.getClientName();
		String body = String.format(
				"Hi %s,\n\nPlease find your payment link below.\n\nAmount: ₦%s\nPayment Link: %s\n\nThank you.",
				invoice.getClientName(), invoice.getAmount(), paymentUrl);

		gmailService.sendEmailWithGoogleToken(googleAccessToken, invoice.getClientEmail(), subject, body);

		AgentAction paymentAction = new AgentAction();
		paymentAction.setActionType(ActionType.PAYMENT_LINK_SENT);
		paymentAction.setInvoiceId(invoice.getId());
		paymentAction.setUserId(userId);
		paymentAction.setExecutedAt(LocalDateTime.now());
		agentActionRepository.save(paymentAction);

		connectedToolRepository.findByUserIdAndToolNameAndIsActiveTrue(userId, "gcal").ifPresent(tool -> {
			try {

				googleCalendarService.scheduleFollowUpWithGoogleToken(googleAccessToken, invoice.getClientName(),
						invoice.getClientEmail(), invoice.getId(), invoice.getAmount());

				AgentAction calendarAction = new AgentAction();
				calendarAction.setActionType(ActionType.MEETING_SCHEDULED);
				calendarAction.setInvoiceId(invoice.getId());
				calendarAction.setUserId(userId);
				calendarAction.setExecutedAt(LocalDateTime.now());
				agentActionRepository.save(calendarAction);
			} catch (Exception e) {
				log.error("Failed to schedule follow-up for invoice={}", invoice.getId(), e);
			}
		});
	}

	public boolean isUserBeingScanned(String userId) {
		return activeScans.contains(userId);
	}

	@Async
	public void triggerManualScan(String userId, String googleAccessToken) {

		activeScans.add(userId);

		try {
			AgentAction action = new AgentAction();
			action.setUserId(userId);
			action.setActionType(ActionType.SCAN_TRIGGERED);
			action.setExecutedAt(LocalDateTime.now());
			action.setCreatedAt(LocalDateTime.now());
			agentActionRepository.save(action);

			log.info("+++++++++++++++++ Manual scan started for user: {} ++++++++++++++++++++", userId);
			log.info("Google token received, length={}", googleAccessToken != null ? googleAccessToken.length() : 0);
			processUserInvoicesWithGoogleToken(userId, googleAccessToken);
		} finally {
			
			activeScans.remove(userId);
			log.info("+++++++++++++++++ Manual scan finished for user: {} ++++++++++++++++++++", userId);
		}
	}

	@Scheduled(cron = "0 0 8 * * *")
	public void markOverdueInvoices() {
		List<Invoice> overdueInvoices = invoiceRepository.findByStatusAndDueDateBefore(InvoiceStatus.PAYMENT_SENT,
				LocalDate.now());

		for (Invoice invoice : overdueInvoices) {
			invoice.setStatus(InvoiceStatus.OVERDUE);
			invoiceRepository.save(invoice);
			log.warn("Invoice {} marked as OVERDUE", invoice.getId());
		}
	}
}