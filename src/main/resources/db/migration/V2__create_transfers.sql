create type transfer_status as enum ('PENDING', 'SUCCESSFUL', 'FAILED');

create table transfers (
    transfer_id  bigint generated always as identity primary key,
    account_from bigint          not null references accounts (account_id),
    account_to   bigint          not null references accounts (account_id),
    amount       bigint          not null check (amount > 0),
    currency     varchar(3)      not null,
    status       transfer_status not null default 'PENDING',
    error_code   varchar(10),
    created_at   timestamptz     not null default now(),
    updated_at   timestamptz     not null default now(),
    check (account_from <> account_to),
    check ((status = 'FAILED') = (error_code is not null))
);
