package com.bank.bian.currentaccount.events;

import com.bank.bian.currentaccount.domain.CurrentAccountService;
import com.bank.bian.currentaccount.domain.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 2d-ii (profile `kafka`): the inbound halves of the flagship loops.
 *
 *   bian.kyc.assessment  type=kyc.assessment.completed → applyKycResult
 *   bian.cheques.lifecycle type=cheque.cleared          → chequeCredit
 *
 * Events naming accounts that don't live in THIS service domain (savings
 * refs, other banks) are skipped silently — every account SD sees the same
 * topics and acts only on its own records.
 */
@Component
@Profile("kafka")
public class LoopEventConsumer {

    private static final Logger log = LoggerFactory.getLogger("bian.loop-feed");
    private static final String PREFIX = "CA-";

    private final CurrentAccountService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public LoopEventConsumer(CurrentAccountService service) {
        this.service = service;
    }

    @KafkaListener(topics = "bian.kyc.assessment", groupId = "sd-current-account")
    public void onKycEvent(String message) {
        handleKycEvent(message);
    }

    @KafkaListener(topics = "bian.cheques.lifecycle", groupId = "sd-current-account")
    public void onChequeEvent(String message) {
        handleChequeEvent(message);
    }

    void handleKycEvent(String message) {
        try {
            JsonNode e = mapper.readTree(message);
            if (!"kyc.assessment.completed".equals(e.path("type").asText())) {
                return;
            }
            JsonNode p = e.path("payload");
            String accountRef = p.path("accountRef").asText("");
            if (!accountRef.startsWith(PREFIX)) {
                return; // not ours
            }
            boolean approved = "APPROVED".equals(p.path("outcome").asText());
            service.applyKycResult(accountRef, approved, p.path("reasons").asText(""));
        } catch (DomainException ex) {
            log.info("kyc verdict not applicable: {}", ex.getMessage()); // unknown / not pending
        } catch (Exception ex) {
            log.warn("skipping malformed kyc event: {}", ex.getMessage());
        }
    }

    void handleChequeEvent(String message) {
        try {
            JsonNode e = mapper.readTree(message);
            if (!"cheque.cleared".equals(e.path("type").asText())) {
                return;
            }
            JsonNode p = e.path("payload");
            String beneficiary = p.path("beneficiaryAccountRef").asText("");
            if (!beneficiary.startsWith(PREFIX)) {
                return; // savings or external beneficiary
            }
            service.chequeCredit(beneficiary, p.path("amountMinor").asLong(),
                    p.path("reference").asText("cheque " + p.path("chequeId").asText()));
        } catch (DomainException ex) {
            log.info("cheque credit not applicable: {}", ex.getMessage());
        } catch (Exception ex) {
            log.warn("skipping malformed cheque event: {}", ex.getMessage());
        }
    }
}
