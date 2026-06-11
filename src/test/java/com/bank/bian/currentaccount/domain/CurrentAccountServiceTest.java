package com.bank.bian.currentaccount.domain;

import com.bank.bian.currentaccount.events.DomainEvent;
import com.bank.bian.currentaccount.events.EventPublisher;
import com.bank.bian.currentaccount.infrastructure.InMemoryAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The business rules, exercised directly — no Spring context needed. */
class CurrentAccountServiceTest {

    static class RecordingPublisher implements EventPublisher {
        final List<DomainEvent> events = new ArrayList<>();
        @Override public void publish(DomainEvent event) { events.add(event); }
        List<String> types() { return events.stream().map(DomainEvent::type).toList(); }
    }

    RecordingPublisher events;
    CurrentAccountService service;          // auto-approve ON (Phase 2a default)
    CurrentAccountService manualKycService; // auto-approve OFF

    @BeforeEach
    void setUp() {
        events = new RecordingPublisher();
        service = new CurrentAccountService(new InMemoryAccountRepository(), events, true, Clock.systemUTC());
        manualKycService = new CurrentAccountService(new InMemoryAccountRepository(), events, false, Clock.systemUTC());
    }

    @Nested
    class Opening {
        @Test
        void autoApproveOpensActiveAndEmitsKycEvents() {
            Account a = service.open("C-001", "INR", 50_00);
            assertThat(a.getStatus()).isEqualTo(Account.Status.ACTIVE);
            assertThat(events.types()).containsExactly(
                    "kyc.check.requested", "kyc.assessment.auto-approved", "account.opened");
        }

        @Test
        void withoutAutoApproveAccountIsPendingAndLocked() {
            Account a = manualKycService.open("C-002", "INR", 0);
            assertThat(a.getStatus()).isEqualTo(Account.Status.PENDING_KYC);
            assertThatThrownBy(() -> manualKycService.deposit(a.getAccountId(), 100, "x"))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining("KYC");
        }

        @Test
        void kycRejectionIsTerminal() {
            Account a = manualKycService.open("C-003", "INR", 0);
            manualKycService.applyKycResult(a.getAccountId(), false, "document mismatch");
            assertThat(manualKycService.retrieve(a.getAccountId()).getStatus())
                    .isEqualTo(Account.Status.REJECTED);
            assertThatThrownBy(() -> manualKycService.deposit(a.getAccountId(), 100, "x"))
                    .hasMessageContaining("REJECTED");
        }

        @Test
        void invalidCurrencyRejected() {
            assertThatThrownBy(() -> service.open("C-004", "rupees", 0))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining("ISO 4217");
        }
    }

    @Nested
    class Postings {
        @Test
        void depositAndWithdrawKeepRunningBalance() {
            Account a = service.open("C-010", "INR", 0);
            service.deposit(a.getAccountId(), 1_000_00, "salary");
            AccountTransaction w = service.withdraw(a.getAccountId(), 250_00, "rent");
            assertThat(w.balanceAfterMinor()).isEqualTo(750_00);
            assertThat(service.transactions(a.getAccountId())).hasSize(2);
        }

        @Test
        void overdraftIsUsableToTheLimitAndNoFurther() {
            Account a = service.open("C-011", "INR", 500_00);
            service.withdraw(a.getAccountId(), 500_00, "od-to-limit"); // exactly the limit
            assertThat(service.retrieve(a.getAccountId()).getBalanceMinor()).isEqualTo(-500_00);
            assertThatThrownBy(() -> service.withdraw(a.getAccountId(), 1, "one-paisa-too-far"))
                    .hasMessageContaining("available");
        }

        @Test
        void zeroAndNegativeAmountsRejected() {
            Account a = service.open("C-012", "INR", 0);
            assertThatThrownBy(() -> service.deposit(a.getAccountId(), 0, "zero"))
                    .hasMessageContaining("must be > 0");
        }

        @Test
        void transactionPostedEventCarriesFraudFeedFields() {
            Account a = service.open("C-013", "INR", 0);
            service.deposit(a.getAccountId(), 9_999_00, "large-cash");
            DomainEvent posted = events.events.stream()
                    .filter(e -> e.type().equals("transaction.posted")).findFirst().orElseThrow();
            assertThat(posted.topic()).isEqualTo(CurrentAccountService.TOPIC_ACCOUNTS);
            assertThat(posted.payload()).containsKeys("transactionId", "accountId", "amountMinor", "currency");
        }
    }

    @Nested
    class BlockingAndClosing {
        @Test
        void blockedAccountRejectsDebitsButAcceptsCredits() {
            Account a = service.open("C-020", "INR", 0);
            service.deposit(a.getAccountId(), 100_00, "seed");
            service.control(a.getAccountId(), "block");
            assertThatThrownBy(() -> service.withdraw(a.getAccountId(), 10_00, "blocked-debit"))
                    .hasMessageContaining("blocked");
            service.deposit(a.getAccountId(), 50_00, "credit-ok"); // must not throw
            service.control(a.getAccountId(), "unblock");
            service.withdraw(a.getAccountId(), 10_00, "post-unblock");
        }

        @Test
        void closeRequiresExactlyZeroBalance() {
            Account a = service.open("C-021", "INR", 100_00);
            service.deposit(a.getAccountId(), 30_00, "seed");
            assertThatThrownBy(() -> service.control(a.getAccountId(), "terminate"))
                    .hasMessageContaining("balance 0");
            service.withdraw(a.getAccountId(), 30_00, "empty-it");
            service.control(a.getAccountId(), "terminate");
            assertThat(service.retrieve(a.getAccountId()).getStatus()).isEqualTo(Account.Status.CLOSED);
            assertThatThrownBy(() -> service.deposit(a.getAccountId(), 1, "post-close"))
                    .hasMessageContaining("CLOSED");
        }
    }
}
