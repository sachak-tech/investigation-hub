# Demo Expanded Bank — financial crime investigation policy

Policy ID: `synthetic-expanded-aml-policy`, version 1. Effective 12 November 2026. This is synthetic policy text for development, not a bank-issued policy.

## Case grouping

Join a new alert to an open case only when it has the same verified subject and the case was created within the previous 30 days. If no eligible open case exists, create a new case. A closed case is never reopened automatically; create a linked case instead.

## Classification

- A transaction anomaly alone creates `TRANSACTION_REVIEW`.
- A source-of-funds KYC alert alone creates `KYC_REVIEW`.
- A possible sanctions match alone creates `SANCTIONS_REVIEW`.
- A PEP or adverse-media alert alone creates `PEP_REVIEW`.
- Transaction plus KYC becomes `TRANSACTION_AND_KYC_REVIEW`.
- Transaction plus sanctions becomes `TRANSACTION_AND_SANCTIONS_REVIEW`.
- KYC plus PEP becomes `KYC_AND_PEP_REVIEW`.
- Transaction plus KYC plus PEP becomes `TRANSACTION_KYC_AND_PEP_REVIEW`.
- More specific combinations take precedence over single-alert classifications.

## Investigation steps

- Transaction anomaly: human reviews the transaction timeline and alert rationale; an agent analyzes the transactions and customer profile.
- KYC source of funds: human reviews declared source of funds and the bank source-of-funds section.
- Transaction plus KYC: add a combined-risk assessment using transaction and source-of-funds evidence.
- Possible sanctions match: human dispositions match candidates and customer identifiers; an agent enriches the candidate match.
- PEP or adverse media: human assesses the PEP and media hits.
- Transaction plus KYC plus PEP: add a cross-signal assessment using transactions, source-of-funds information, and media hits.
- A step becomes required as soon as its alert combination is present, including when an alert joins an existing case.

## Review and closure

L1 may complete steps or escalate to L2 or L3. L2 may complete steps or escalate to L3. L3 may approve closure or return work to L1 or L2. Agents may complete authorized steps and recommend closure for transaction, KYC, and transaction-plus-KYC cases. No case type may be closed autonomously. Independent review and L3 approval are required for closure.
