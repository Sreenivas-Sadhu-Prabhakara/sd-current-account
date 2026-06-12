package com.bank.bian.currentaccount.domain;

/**
 * Outbound port to the Know Your Customer service domain (Phase 2d-ii loop
 * closure). When ACTIVE (bian.kyc.url configured), account opening dispatches
 * a real KYC check and the account stays PENDING_KYC until the verdict
 * arrives on the kyc-result callback. When inactive, the Phase 2a
 * auto-approve behavior applies.
 */
public interface KycGateway {

    /** @return true when a real KYC service is wired (disables auto-approve). */
    boolean isActive();

    /**
     * Dispatch the check. Delivery failure must never fail account opening —
     * implementations report it; the account simply remains PENDING_KYC
     * (the manual kyc-result endpoint is the operational fallback).
     *
     * @return true if the check request was delivered
     */
    boolean requestCheck(String accountId, String customerReference);

    /** Default adapter: no KYC service wired — Phase 2a semantics. */
    KycGateway NONE = new KycGateway() {
        @Override public boolean isActive() { return false; }
        @Override public boolean requestCheck(String accountId, String customerReference) { return false; }
    };
}
