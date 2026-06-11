package com.bank.bian.currentaccount;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Boot + API smoke test against the real domain (auto-approve KYC default). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationTests {

    static final String CR = "/v1/current-account-facility-fulfillment-arrangement";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    String url(String path) { return "http://localhost:" + port + path; }

    @Test
    void fullAccountJourneyThroughTheApi() {
        // open
        var opened = rest.postForEntity(url(CR + "/initiate"),
                Map.of("customerReference", "C-API-1", "currency", "INR", "overdraftLimitMinor", 100_00),
                Map.class);
        assertThat(opened.getStatusCode().value()).isEqualTo(201);
        String id = (String) opened.getBody().get("accountId");
        assertThat(opened.getBody().get("status")).isEqualTo("ACTIVE");

        // deposit + withdraw
        rest.postForEntity(url(CR + "/" + id + "/payments/deposit"),
                Map.of("amountMinor", 500_00, "reference", "seed"), Map.class);
        var withdrawal = rest.postForEntity(url(CR + "/" + id + "/payments/withdraw"),
                Map.of("amountMinor", 200_00, "reference", "atm"), Map.class);
        assertThat(((Number) withdrawal.getBody().get("balanceAfterMinor")).longValue()).isEqualTo(300_00);

        // balance view includes overdraft headroom
        var balance = rest.getForObject(url(CR + "/" + id + "/balance"), Map.class);
        assertThat(((Number) balance.get("availableMinor")).longValue()).isEqualTo(400_00);

        // business rule through the API: overdraft breach → 409
        var breach = rest.postForEntity(url(CR + "/" + id + "/payments/withdraw"),
                Map.of("amountMinor", 999_99, "reference", "too-much"), Map.class);
        assertThat(breach.getStatusCode().value()).isEqualTo(409);
        assertThat(breach.getBody().get("code")).isEqualTo("OVERDRAFT_EXCEEDED");
    }

    @Test
    void serviceDomainMetadataReportsDeepPhase() {
        var meta = rest.getForObject(url("/v1/service-domain"), Map.class);
        assertThat(meta.get("serviceDomain")).isEqualTo("Current Account");
        assertThat(meta.get("phase")).isEqualTo("2a-deep");
    }
}
