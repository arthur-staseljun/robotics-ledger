create table accounts (
    account_id bigint generated always as identity primary key,
    balance    bigint      not null check (balance >= 0),
    currency   varchar(3)  not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
