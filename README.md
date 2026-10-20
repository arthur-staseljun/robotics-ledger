# Ledger

See [Ledger System Design.svg](Ledger%20System%20Design.svg). Authentication and the audit trail from the design are not implemented yet.

## Running

Java 25 and Docker are required.

```bash
./app.sh                                          # start postgre and kafka, application (:8080) and the relay
./mvnw test                                       # unit tests, no docker needed
./mvnw test -DexcludedGroups= -Dgroups=container  # tests with postgre and kafka (testcontainers)
```
