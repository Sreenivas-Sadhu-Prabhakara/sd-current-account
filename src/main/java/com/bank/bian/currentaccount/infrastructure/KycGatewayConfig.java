package com.bank.bian.currentaccount.infrastructure;

import com.bank.bian.currentaccount.domain.KycGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Phase 2d-ii loop closure: when bian.kyc.url is set, account openings call
 * the KYC service domain's /initiate with a callback URL pointing back at
 * this service's kyc-result endpoint — and auto-approve is disabled.
 *
 *   bian.kyc.url:               http://sd-know-your-customer.bian-risk-compliance:8080
 *   bian.kyc.callback-base-url: http://sd-current-account.bian-operations:8080
 *   bian.kyc.documents:         ID,ADDRESS   # what onboarding collected (Phase 3: real doc refs)
 */
@Configuration
public class KycGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger("bian.kyc-gateway");
    private static final String CR = "current-account-facility-fulfillment-arrangement";

    @Bean
    KycGateway kycGateway(@Value("${bian.kyc.url:}") String kycUrl,
                          @Value("${bian.kyc.callback-base-url:}") String callbackBase,
                          @Value("${bian.kyc.documents:ID,ADDRESS}") List<String> documents,
                          RestClient.Builder restClientBuilder) {
        if (kycUrl == null || kycUrl.isBlank()) {
            return KycGateway.NONE;
        }
        RestClient rest = restClientBuilder.build();
        return new KycGateway() {
            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public boolean requestCheck(String accountId, String customerReference) {
                try {
                    rest.post()
                            .uri(kycUrl + "/v1/kyc-assessment-procedure/initiate")
                            .header("Content-Type", "application/json")
                            .body(Map.of(
                                    "customerReference", customerReference,
                                    "accountRef", accountId,
                                    "documents", documents,
                                    "callbackUrl", callbackBase + "/v1/" + CR + "/" + accountId + "/kyc-result"))
                            .retrieve()
                            .toBodilessEntity();
                    return true;
                } catch (Exception e) {
                    // Never fail account opening on KYC transport — the account
                    // stays PENDING_KYC; ops can drive the manual kyc-result.
                    log.warn("KYC check dispatch failed for {}: {}", accountId, e.getMessage());
                    return false;
                }
            }
        };
    }
}
