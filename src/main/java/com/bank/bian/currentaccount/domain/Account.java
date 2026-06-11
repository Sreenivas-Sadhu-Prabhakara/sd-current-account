package com.bank.bian.currentaccount.domain;

import java.time.Instant;

/**
 * The control record of this service domain, made real:
 * "Current Account Facility Fulfillment Arrangement" — i.e. a current account.
 *
 * Money is held as long minor units (cents/paise) — never floating point.
 * Balance may go negative down to -overdraftLimitMinor (the overdraft facility
 * is the defining feature of a current account vs a savings account).
 */
public class Account {

    /**
     * PENDING_KYC: opened, awaiting KYC outcome — no transactions allowed.
     * ACTIVE:      fully operational.
     * BLOCKED:     debits rejected; credits still accepted (standard bank
     *              practice for frozen accounts — money may come in, not out).
     * REJECTED:    KYC failed — terminal, never transacted.
     * CLOSED:      closed at zero balance — terminal.
     */
    public enum Status { PENDING_KYC, ACTIVE, BLOCKED, REJECTED, CLOSED }

    private String accountId;
    private String customerReference;
    private String currency;
    private long balanceMinor;
    private long overdraftLimitMinor;
    private Status status = Status.PENDING_KYC;
    private Instant openedAt;
    private Instant updatedAt;

    public static Account open(String accountId, String customerReference,
                               String currency, long overdraftLimitMinor, Instant now) {
        Account a = new Account();
        a.accountId = accountId;
        a.customerReference = customerReference;
        a.currency = currency;
        a.overdraftLimitMinor = overdraftLimitMinor;
        a.openedAt = now;
        a.updatedAt = now;
        return a;
    }

    /** Funds usable right now: balance plus undrawn overdraft. */
    public long availableMinor() {
        return balanceMinor + overdraftLimitMinor;
    }

    public boolean isTerminal() {
        return status == Status.CLOSED || status == Status.REJECTED;
    }

    public String getAccountId() { return accountId; }
    public String getCustomerReference() { return customerReference; }
    public String getCurrency() { return currency; }
    public long getBalanceMinor() { return balanceMinor; }
    public void setBalanceMinor(long balanceMinor) { this.balanceMinor = balanceMinor; }
    public long getOverdraftLimitMinor() { return overdraftLimitMinor; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
