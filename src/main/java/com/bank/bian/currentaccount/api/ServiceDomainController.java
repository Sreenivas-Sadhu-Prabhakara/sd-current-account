package com.bank.bian.currentaccount.api;

import com.bank.bian.currentaccount.domain.Account;
import com.bank.bian.currentaccount.domain.AccountTransaction;
import com.bank.bian.currentaccount.domain.CurrentAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * BIAN semantic API for "Current Account" — Phase 2a, real domain.
 * Control record: Current Account Facility Fulfillment Arrangement.
 * The "payments" sub-resource is the BIAN Payments behavior qualifier.
 *
 * Contract: api/openapi.yaml (owned by this repo).
 */
@RestController
@RequestMapping("/v1")
public class ServiceDomainController {

    static final String CR = "current-account-facility-fulfillment-arrangement";

    private final CurrentAccountService service;

    public ServiceDomainController(CurrentAccountService service) {
        this.service = service;
    }

    @GetMapping("/service-domain")
    public Map<String, String> serviceDomain() {
        return Map.of(
                "serviceDomain", "Current Account",
                "businessArea", "Operations and Execution",
                "businessDomain", "Account Management",
                "functionalPattern", "Fulfill",
                "assetType", "Current Account Facility",
                "controlRecord", "Current Account Facility Fulfillment Arrangement",
                "version", "0.2.0",
                "phase", "2a-deep"
        );
    }

    // ── control record lifecycle ─────────────────────────────────────────────

    public record OpenRequest(String customerReference, String currency, Long overdraftLimitMinor) {}

    @PostMapping("/" + CR + "/initiate")
    public ResponseEntity<Account> initiate(@RequestBody OpenRequest req) {
        Account account = service.open(
                req.customerReference(),
                req.currency() == null ? "INR" : req.currency(),
                req.overdraftLimitMinor() == null ? 0L : req.overdraftLimitMinor());
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }

    @GetMapping("/" + CR)
    public Collection<Account> list() {
        return service.list();
    }

    @GetMapping("/" + CR + "/{accountId}/retrieve")
    public Account retrieve(@PathVariable String accountId) {
        return service.retrieve(accountId);
    }

    @PutMapping("/" + CR + "/{accountId}/control")
    public Account control(@PathVariable String accountId, @RequestBody Map<String, String> body) {
        return service.control(accountId, body.get("action"));
    }

    @PutMapping("/" + CR + "/{accountId}/kyc-result")
    public Account kycResult(@PathVariable String accountId, @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        return service.applyKycResult(accountId, approved, (String) body.get("reason"));
    }

    // ── Payments behavior qualifier ──────────────────────────────────────────

    public record PostingRequest(long amountMinor, String reference) {}

    @PostMapping("/" + CR + "/{accountId}/payments/deposit")
    public ResponseEntity<AccountTransaction> deposit(@PathVariable String accountId,
                                                      @RequestBody PostingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.deposit(accountId, req.amountMinor(), req.reference()));
    }

    @PostMapping("/" + CR + "/{accountId}/payments/withdraw")
    public ResponseEntity<AccountTransaction> withdraw(@PathVariable String accountId,
                                                       @RequestBody PostingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.withdraw(accountId, req.amountMinor(), req.reference()));
    }

    /** Inbound credit from Cheque Processing (HTTP now; Kafka consumer later). */
    @PostMapping("/" + CR + "/{accountId}/payments/cheque-credit")
    public ResponseEntity<AccountTransaction> chequeCredit(@PathVariable String accountId,
                                                           @RequestBody PostingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.chequeCredit(accountId, req.amountMinor(), req.reference()));
    }

    @GetMapping("/" + CR + "/{accountId}/payments")
    public List<AccountTransaction> transactions(@PathVariable String accountId) {
        return service.transactions(accountId);
    }

    @GetMapping("/" + CR + "/{accountId}/balance")
    public Map<String, Object> balance(@PathVariable String accountId) {
        Account a = service.retrieve(accountId);
        return Map.of(
                "accountId", a.getAccountId(),
                "currency", a.getCurrency(),
                "balanceMinor", a.getBalanceMinor(),
                "availableMinor", a.availableMinor(),
                "overdraftLimitMinor", a.getOverdraftLimitMinor(),
                "status", a.getStatus().name());
    }
}
