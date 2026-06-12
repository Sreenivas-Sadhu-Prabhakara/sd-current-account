package com.bank.bian.currentaccount.events;

import com.bank.bian.currentaccount.domain.Account;
import com.bank.bian.currentaccount.domain.CurrentAccountService;
import com.bank.bian.currentaccount.infrastructure.InMemoryAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The inbound loop handlers, exercised directly (no broker needed). */
class LoopEventConsumerTest {

    List<DomainEvent> published;
    CurrentAccountService service;
    LoopEventConsumer consumer;

    @BeforeEach
    void setUp() {
        published = new ArrayList<>();
        service = new CurrentAccountService(new InMemoryAccountRepository(),
                published::add, /* autoApprove */ false, Clock.systemUTC());
        consumer = new LoopEventConsumer(service);
    }

    @Test
    void kycVerdictOnTheWireActivatesThePendingAccount() {
        Account a = service.open("C-EVT", "INR", 0);
        assertThat(a.getStatus()).isEqualTo(Account.Status.PENDING_KYC);

        consumer.handleKycEvent(String.format("""
            {"type":"kyc.assessment.completed","payload":
             {"assessmentId":"KYC-1","customerReference":"C-EVT","accountRef":"%s",
              "outcome":"APPROVED","reasons":"CLEAN"}}""", a.getAccountId()));

        assertThat(service.retrieve(a.getAccountId()).getStatus()).isEqualTo(Account.Status.ACTIVE);
    }

    @Test
    void clearedChequeOnTheWireCreditsTheBeneficiary() {
        Account a = service.open("C-CHQ", "INR", 0);
        service.applyKycResult(a.getAccountId(), true, "CLEAN");

        consumer.handleChequeEvent(String.format("""
            {"type":"cheque.cleared","payload":
             {"chequeId":"CHQ-1","beneficiaryAccountRef":"%s","amountMinor":50000,
              "currency":"INR","reference":"cheque 123456"}}""", a.getAccountId()));

        assertThat(service.retrieve(a.getAccountId()).getBalanceMinor()).isEqualTo(50000);
    }

    @Test
    void foreignPrefixesAndUnknownAccountsAreSkippedQuietly() {
        consumer.handleChequeEvent("""
            {"type":"cheque.cleared","payload":{"beneficiaryAccountRef":"SA-OTHER","amountMinor":1}}""");
        consumer.handleKycEvent("""
            {"type":"kyc.assessment.completed","payload":{"accountRef":"CA-UNKNOWN","outcome":"APPROVED"}}""");
        consumer.handleKycEvent("garbage");
        // nothing thrown, nothing credited — the feed never wedges
        assertThat(service.list()).isEmpty();
    }
}
