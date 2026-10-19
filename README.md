# Ledger

The main artifact of this repository is the system design: `Ledger System Design.svg`. 
It covers capacity estimates, the high-level design, the request path of a transfer, the data model, delivery guarantees, security and reconciliation, with the trade-offs behind each decision.
A basic implementation is attached as a part of that design. It is a small Spring Boot service with customers, accounts, idempotent transfers between accounts, and transaction history.
This README is for orientation only: please see the diagram `Ledger System Design.svg` for the full design.

## Running

Java 25 and Docker are required. PostgreSQL is the only persistence.

```bash
docker compose up -d
./mvnw spring-boot:run
./mvnw test
./mvnw test -DexcludedGroups= -Dgroups=container
```
The tests with the `container` group start PostgreSQL with Testcontainers, so Docker must be running.
