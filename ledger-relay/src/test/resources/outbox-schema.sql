create type event_type as enum ('created', 'processed');

create table outbox (
    id          bigint generated always as identity primary key,
    account_id  bigint       not null,
    transfer_id bigint       not null,
    event_type  event_type   not null,
    topic       varchar(255) not null,
    payload     jsonb        not null,
    sent_at     timestamptz,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    unique (transfer_id, event_type)
);

create index idx_outbox_to_be_sent on outbox (created_at, id) where sent_at is null;
