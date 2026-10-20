# Ledger

The main artifact of this repository is the system design: `Ledger System Design.svg`. 
It covers capacity estimates, the high-level design, the request path of a transfer, the data model, delivery guarantees, security and reconciliation, with the trade-offs behind each decision.
A basic implementation is attached as a part of that design. It is a small Spring Boot service with customers, accounts, idempotent transfers between accounts, and transaction history.
This README is for orientation only: please see the diagram `Ledger System Design.svg` for the full design.

## Running

Java 25 and Docker are required. PostgreSQL is the only persistence.

```bash
./app.sh
./mvnw test
./mvnw test -DexcludedGroups= -Dgroups=container
```
The tests with the `container` group start PostgreSQL and Kafka with Testcontainers, so Docker must be running.

More instances of the API and consumer can be started with `java -jar ledger-app/target/ledger-app-*-SNAPSHOT.jar --server.port=8081`. They share one consumer group, so Kafka splits the partitions between them.

## Modules

- `ledger-app`: REST API, Kafka consumer that settles transfers, services, migrations.
- `ledger-relay`: publishes the `outbox` table to Kafka. Shares no code with the app.
