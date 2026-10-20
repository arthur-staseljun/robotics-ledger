create type finding_kind as enum ('BALANCE', 'ENTRY_COUNT', 'STUCK_TRANSFER', 'UNSENT_OUTBOX');
create type finding_status as enum ('OPEN', 'RESOLVED');

create table reconciliation_run (
    id                bigint generated always as identity primary key,
    started_at        timestamptz not null,
    finished_at       timestamptz,
    new_findings      int,
    open_findings     int,
    resolved_findings int
);

create table reconciliation_finding (
    id            bigint generated always as identity primary key,
    kind          finding_kind   not null,
    subject_id    bigint         not null,
    details       text           not null,
    status        finding_status not null default 'OPEN',
    first_run_id  bigint         not null references reconciliation_run (id),
    last_run_id   bigint         not null references reconciliation_run (id),
    first_seen_at timestamptz    not null default now(),
    last_seen_at  timestamptz    not null default now(),
    resolved_at   timestamptz,
    check ((status = 'RESOLVED') = (resolved_at is not null))
);

create unique index idx_reconciliation_finding_open on reconciliation_finding (kind, subject_id) where status = 'OPEN';
