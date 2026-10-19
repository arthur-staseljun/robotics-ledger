create table idempotency_keys (
    client_id       varchar(64) not null,
    idempotency_key uuid        not null,
    transfer_id     bigint references transfers (transfer_id),
    request_hash    bytea       not null,
    response_json   jsonb,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    primary key (client_id, idempotency_key)
);
