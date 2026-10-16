create type account_status as enum ('ACTIVE', 'FROZEN', 'DISABLED');

create table accounts (
    account_id bigint generated always as identity primary key,
    owner_id   bigint         not null,
    balance    bigint         not null check (balance >= 0),
    currency   varchar(3)     not null,
    status     account_status not null default 'ACTIVE',
    last_seq   bigint         not null default 0,
    created_at timestamptz    not null default now(),
    updated_at timestamptz    not null default now(),
    unique (account_id, currency),
    unique (account_id, owner_id)
);

create index idx_accounts_owner_id on accounts (owner_id);
