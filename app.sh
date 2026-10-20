#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
trap 'kill 0' EXIT

docker compose up -d --wait
./mvnw -q -DskipTests package

java -jar ledger-app/target/ledger-app-*-SNAPSHOT.jar &
app=$!
until curl -s -o /dev/null localhost:8080; do
    kill -0 $app    # livness check
    sleep 1
done
java -jar ledger-relay/target/ledger-relay-*-SNAPSHOT.jar
