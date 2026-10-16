# Ledger

The main artifact of this repository is the system design: `Ledger System Design.svg`. 
It covers capacity estimates, the high-level design, the request path of a transfer, the data model, delivery guarantees, security and reconciliation, with the trade-offs behind each decision.
A basic implementation is attached as a part of that design. It is a small Spring Boot service with accounts, deposits and withdrawals, and transaction history.
This README is for orientation only: please see the diagram `Ledger System Design.svg` for the full design.
