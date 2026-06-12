package com.bank.bian.currentaccount.domain;

import com.bank.bian.currentaccount.events.DomainEvent;
import com.bank.bian.currentaccount.events.EventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Business rules for the Current Account service domain.
 *
 * Rules implemented (the judgment lives here, not in the controller):
 *  - Accounts open in PENDING_KYC and emit kyc.check.requested. Until KYC
 *    approves, NO transactions post. With bian.kyc.auto-approve=true
 *    (Phase 2a default, until the KYC choreography is live) approval is
 *    immediate and an auto-approved event records that fact honestly.
 *  - Withdrawals may draw the balance negative down to -overdraftLimit
 *    (that's what a current account is). One minor unit beyond → rejected.
 *  - BLOCKED accounts accept credits but reject all debits.
 *  - Close requires balance == 0 exactly: negative must be repaid first,
 *    positive must be withdrawn first. CLOSED and REJECTED are terminal.
 *  - Every posting appends an immutable transaction with the running balance,
 *    and emits transaction.posted for Fraud Detection to consume.
 *
 * Concurrency: per-account locks make read-check-write postings atomic.
 * When the Postgres adapter replaces the in-memory one, this moves to DB
 * transactions with SELECT ... FOR UPDATE.
 */
@Service
public class CurrentAccountService {

    public static final String TOPIC_ACCOUNTS = "bian.accounts.current-account";
    public static final String TOPIC_KYC = "bian.kyc.check";

    private final AccountRepository repository;
    private final EventPublisher events;
    private final boolean kycAutoApprove;
    private final KycGateway kycGateway;
    private final Clock clock;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Autowired  // disambiguates from the Clock-injected test constructor below
    public CurrentAccountService(AccountRepository repository,
                                 EventPublisher events,
                                 KycGateway kycGateway,
                                 @Value("${bian.kyc.auto-approve:true}") boolean kycAutoApprove) {
        this(repository, events, kycGateway, kycAutoApprove, Clock.systemUTC());
    }

    public CurrentAccountService(AccountRepository repository, EventPublisher events,
                                 boolean kycAutoApprove, Clock clock) {
        this(repository, events, KycGateway.NONE, kycAutoApprove, clock);
    }

    public CurrentAccountService(AccountRepository repository, EventPublisher events,
                                 KycGateway kycGateway, boolean kycAutoApprove, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.kycGateway = kycGateway;
        this.kycAutoApprove = kycAutoApprove;
        this.clock = clock;
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    public Account open(String customerReference, String currency, long overdraftLimitMinor) {
        if (customerReference == null || customerReference.isBlank()) {
            throw DomainException.invalid("CUSTOMER_REQUIRED", "customerReference is required");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw DomainException.invalid("CURRENCY_INVALID", "currency must be an ISO 4217 code, e.g. INR");
        }
        if (overdraftLimitMinor < 0) {
            throw DomainException.invalid("OVERDRAFT_NEGATIVE", "overdraftLimitMinor must be >= 0");
        }

        Account account = Account.open("CA-" + UUID.randomUUID(), customerReference,
                currency, overdraftLimitMinor, clock.instant());
        repository.save(account);

        events.publish(DomainEvent.of(TOPIC_KYC, "kyc.check.requested", Map.of(
                "accountId", account.getAccountId(),
                "customerReference", customerReference)));

        if (kycGateway.isActive()) {
            // 2d-ii: a real KYC service is wired — dispatch the check and stay
            // PENDING_KYC until its verdict lands on the kyc-result callback.
            // Auto-approve is intentionally ignored in this mode.
            boolean delivered = kycGateway.requestCheck(account.getAccountId(), customerReference);
            events.publish(DomainEvent.of(TOPIC_KYC,
                    delivered ? "kyc.check.dispatched" : "kyc.check.dispatch-failed",
                    Map.of("accountId", account.getAccountId())));
        } else if (kycAutoApprove) {
            account.setStatus(Account.Status.ACTIVE);
            account.setUpdatedAt(clock.instant());
            repository.save(account);
            events.publish(DomainEvent.of(TOPIC_KYC, "kyc.assessment.auto-approved", Map.of(
                    "accountId", account.getAccountId(),
                    "note", "Phase 2a: bian.kyc.auto-approve=true until KYC choreography is live")));
        }
        events.publish(DomainEvent.of(TOPIC_ACCOUNTS, "account.opened", Map.of(
                "accountId", account.getAccountId(),
                "currency", currency,
                "overdraftLimitMinor", overdraftLimitMinor,
                "status", account.getStatus().name())));
        return account;
    }

    /** Callback from the KYC service domain (HTTP now, Kafka consumer later). */
    public Account applyKycResult(String accountId, boolean approved, String reason) {
        return withLock(accountId, account -> {
            if (account.getStatus() != Account.Status.PENDING_KYC) {
                throw DomainException.rule("KYC_NOT_PENDING",
                        "KYC result only applies to PENDING_KYC accounts (status: " + account.getStatus() + ")");
            }
            account.setStatus(approved ? Account.Status.ACTIVE : Account.Status.REJECTED);
            account.setUpdatedAt(clock.instant());
            repository.save(account);
            events.publish(DomainEvent.of(TOPIC_KYC,
                    approved ? "kyc.assessment.approved" : "kyc.assessment.rejected",
                    Map.of("accountId", accountId, "reason", reason == null ? "" : reason)));
            return account;
        });
    }

    public Account control(String accountId, String action) {
        return withLock(accountId, account -> {
            if (account.isTerminal()) {
                throw DomainException.rule("TERMINAL", "account is " + account.getStatus());
            }
            switch (action == null ? "" : action.toLowerCase()) {
                case "block" -> requireStatus(account, Account.Status.ACTIVE, "block");
                case "unblock" -> requireStatus(account, Account.Status.BLOCKED, "unblock");
                case "terminate" -> {
                    if (account.getBalanceMinor() != 0) {
                        throw DomainException.rule("BALANCE_NOT_ZERO",
                                "close requires balance 0; current balance is "
                                        + account.getBalanceMinor() + " minor units");
                    }
                }
                default -> throw DomainException.invalid("UNKNOWN_ACTION",
                        "action must be block | unblock | terminate");
            }
            Account.Status next = switch (action.toLowerCase()) {
                case "block" -> Account.Status.BLOCKED;
                case "unblock" -> Account.Status.ACTIVE;
                default -> Account.Status.CLOSED;
            };
            account.setStatus(next);
            account.setUpdatedAt(clock.instant());
            repository.save(account);
            events.publish(DomainEvent.of(TOPIC_ACCOUNTS, "account." + next.name().toLowerCase(),
                    Map.of("accountId", accountId)));
            return account;
        });
    }

    // ── postings (BIAN "Payments" behavior qualifier) ───────────────────────

    public AccountTransaction deposit(String accountId, long amountMinor, String reference) {
        return post(accountId, AccountTransaction.Type.DEPOSIT, amountMinor, reference, true);
    }

    public AccountTransaction withdraw(String accountId, long amountMinor, String reference) {
        return post(accountId, AccountTransaction.Type.WITHDRAWAL, amountMinor, reference, false);
    }

    /** Credit originating from a cleared cheque (Cheque Processing SD). */
    public AccountTransaction chequeCredit(String accountId, long amountMinor, String chequeRef) {
        return post(accountId, AccountTransaction.Type.CHEQUE_CREDIT, amountMinor, chequeRef, true);
    }

    private AccountTransaction post(String accountId, AccountTransaction.Type type,
                                    long amountMinor, String reference, boolean credit) {
        if (amountMinor <= 0) {
            throw DomainException.invalid("AMOUNT_NOT_POSITIVE", "amountMinor must be > 0");
        }
        return withLock(accountId, account -> {
            switch (account.getStatus()) {
                case PENDING_KYC -> throw DomainException.rule("KYC_PENDING",
                        "no transactions until KYC approves this account");
                case REJECTED, CLOSED -> throw DomainException.rule("TERMINAL",
                        "account is " + account.getStatus());
                case BLOCKED -> {
                    if (!credit) {
                        throw DomainException.rule("ACCOUNT_BLOCKED", "debits are rejected on a blocked account");
                    }
                }
                case ACTIVE -> { }
            }
            long signed = credit ? amountMinor : -amountMinor;
            long newBalance = account.getBalanceMinor() + signed;
            if (newBalance < -account.getOverdraftLimitMinor()) {
                throw DomainException.rule("OVERDRAFT_EXCEEDED",
                        "available " + account.availableMinor() + " minor units; requested " + amountMinor);
            }
            Instant now = clock.instant();
            account.setBalanceMinor(newBalance);
            account.setUpdatedAt(now);
            repository.save(account);

            AccountTransaction tx = new AccountTransaction("TX-" + UUID.randomUUID(),
                    accountId, type, signed, newBalance, reference, now);
            repository.saveTransaction(tx);

            // Fraud Detection's primary feed
            events.publish(DomainEvent.of(TOPIC_ACCOUNTS, "transaction.posted", Map.of(
                    "transactionId", tx.transactionId(),
                    "accountId", accountId,
                    "type", type.name(),
                    "amountMinor", signed,
                    "balanceAfterMinor", newBalance,
                    "currency", account.getCurrency())));
            return tx;
        });
    }

    // ── queries ──────────────────────────────────────────────────────────────

    public Account retrieve(String accountId) {
        return repository.findById(accountId)
                .orElseThrow(() -> DomainException.notFound("ACCOUNT_UNKNOWN", "no account " + accountId));
    }

    public Collection<Account> list() {
        return repository.findAll();
    }

    public List<AccountTransaction> transactions(String accountId) {
        retrieve(accountId); // 404 before empty list for unknown accounts
        return repository.findTransactions(accountId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void requireStatus(Account account, Account.Status expected, String action) {
        if (account.getStatus() != expected) {
            throw DomainException.rule("WRONG_STATUS",
                    action + " requires " + expected + " (status: " + account.getStatus() + ")");
        }
    }

    private <T> T withLock(String accountId, java.util.function.Function<Account, T> body) {
        ReentrantLock lock = locks.computeIfAbsent(accountId, k -> new ReentrantLock());
        lock.lock();
        try {
            return body.apply(retrieve(accountId));
        } finally {
            lock.unlock();
        }
    }
}
