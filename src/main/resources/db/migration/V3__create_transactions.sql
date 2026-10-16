create type transaction_type as enum ('DEPOSIT', 'WITHDRAWAL');

create table transactions (
    transaction_id   uuid primary key,
    account_id       bigint           not null references accounts (account_id),
    transfer_id      bigint           references transfers (transfer_id),
    transaction_type transaction_type not null,
    amount           bigint           not null,
    balance_after    bigint           not null check (balance_after >= 0),
    currency         varchar(3)       not null,
    created_at       timestamptz      not null default now(),
    unique (account_id, transfer_id)
);

create index idx_transactions_account_id_created_at on transactions (account_id, created_at desc);
create index idx_transactions_transfer_id on transactions (transfer_id);
