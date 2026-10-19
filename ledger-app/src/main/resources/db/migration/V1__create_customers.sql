create table customers (
    customer_id bigint generated always as identity primary key,
    created_at  timestamptz not null default now()
);
