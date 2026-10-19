#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
trap 'kill 0' EXIT

docker compose up -d --wait
./mvnw -q -DskipTests package

java -jar ledger-relay/target/ledger-relay-*-SNAPSHOT.jar &
java -jar ledger-app/target/ledger-app-*-SNAPSHOT.jar
