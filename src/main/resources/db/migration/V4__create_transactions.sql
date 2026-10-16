create type transaction_type as enum ('DEPOSIT', 'WITHDRAWAL');

create table transactions (
    account_id       bigint           not null references accounts (account_id),
    transfer_id      bigint           not null references transfers (transfer_id),
    seq_id           bigint           not null,
    transaction_type transaction_type not null,
    amount           bigint           not null,
    balance_after    bigint           not null check (balance_after >= 0),
    currency         varchar(3)       not null,
    created_at       timestamptz      not null default now(),
    primary key (account_id, transfer_id),
    unique (account_id, seq_id)
);

create index idx_transactions_transfer_id on transactions (transfer_id);

create function forbid_transactions_change() returns trigger language plpgsql as $$
begin
    raise exception 'transactions are append-only';
end;
$$;

create trigger transactions_append_only
    before update or delete on transactions
    for each row execute function forbid_transactions_change();
