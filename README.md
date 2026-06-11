# Current Account

BIAN Service Domain microservice — **Phase 2a DEEP build** (graduated from the golden template; the generator no longer touches this repo — see `.bian-graduated`).

| | |
|---|---|
| **Business Area** | Operations and Execution |
| **Business Domain** | Account Management |
| **Functional Pattern** | Fulfill |
| **Control Record** | Current Account Facility Fulfillment Arrangement |
| **K8s Namespace** | `bian-operations` |
| **Stack** | Java 21 · Spring Boot 3 · Cilium mesh |

## Business rules implemented

- **KYC gating** — accounts open `PENDING_KYC`, emit `kyc.check.requested`, and accept **no transactions** until approved. `bian.kyc.auto-approve=true` (Phase 2a default) approves instantly and records the auto-approval event; flip to `false` when the KYC choreography is live. Rejection → `REJECTED` (terminal).
- **Overdraft** — withdrawals may take the balance negative down to exactly `-overdraftLimitMinor`; one minor unit further is rejected (`409 OVERDRAFT_EXCEEDED`).
- **Blocking** — `BLOCKED` accounts reject debits but accept credits (standard freeze semantics).
- **Closing** — requires balance exactly 0. `CLOSED` is terminal.
- **Audit** — every posting appends an immutable transaction with running balance and emits `transaction.posted` (the **fraud flagship feed**).
- Money is `long` minor units everywhere. No floats, ever.

## API & contracts (owned by this repo)

- REST contract: [`api/openapi.yaml`](api/openapi.yaml) · Event contract: [`api/events.yaml`](api/events.yaml)
- Base path: `/v1/current-account-facility-fulfillment-arrangement`
- Lifecycle: `POST /initiate` · `GET /{id}/retrieve` · `PUT /{id}/control` (`block|unblock|terminate`) · `PUT /{id}/kyc-result`
- Payments BQ: `POST /{id}/payments/deposit|withdraw|cheque-credit` · `GET /{id}/payments` · `GET /{id}/balance`

```bash
mvn spring-boot:run
CR=/v1/current-account-facility-fulfillment-arrangement
ID=$(curl -s -X POST localhost:8080$CR/initiate -H 'content-type: application/json' \
     -d '{"customerReference":"C-1","currency":"INR","overdraftLimitMinor":10000}' | jq -r .accountId)
curl -s -X POST localhost:8080$CR/$ID/payments/deposit -H 'content-type: application/json' -d '{"amountMinor":50000,"reference":"salary"}'
curl -s localhost:8080$CR/$ID/balance
```

## Persistence

In-memory (port/adapter). **Postgres is ready to hydrate, deliberately not wired**: DDL in [`db/schema.sql`](db/schema.sql) (+ `db/seed.sql`), provisioning via `bian-platform/platform-infra/postgres/hydrate.sh` — run only on explicit go-ahead. The schema enforces the overdraft invariant at the database level too.

## Tests

`mvn verify` — unit tests for every rule above (`CurrentAccountServiceTest`) + a boot/API journey test (`ApplicationTests`).
