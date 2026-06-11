package com.bank.bian.currentaccount.domain;

import java.time.Instant;

/**
 * An immutable posted transaction. amountMinor is signed from the account's
 * perspective: credits positive, debits negative. balanceAfterMinor is the
 * running balance — the audit trail must always reconcile.
 */
public record AccountTransaction(
        String transactionId,
        String accountId,
        Type type,
        long amountMinor,
        long balanceAfterMinor,
        String reference,
        Instant postedAt
) {
    public enum Type { DEPOSIT, WITHDRAWAL, FEE, CHEQUE_CREDIT }
}
