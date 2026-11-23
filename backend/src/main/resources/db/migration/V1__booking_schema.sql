-- Bookings and the outbox of effects a confirmed booking owes.
--
-- The two exclusion constraints are the real guard against double bookings: two blocking bookings
-- (pending or accepted) of the same world (live or sandbox) can never overlap in their block range,
-- no matter how many requests race for the same slot.

create table bookings (
  id                  bigint generated always as identity primary key,
  uid                 text not null unique,
  sandbox             boolean not null default false,
  event_type_id       integer not null,
  status              text not null check (status in ('pending', 'accepted', 'rejected')),
  starts_at           timestamptz not null,
  ends_at             timestamptz not null,
  block_start         timestamptz not null,
  block_end           timestamptz not null,
  attendee_name       text not null,
  attendee_email      text not null,
  attendee_time_zone  text not null,
  attendee_language   text not null default 'de',
  metadata            jsonb not null default '{}'::jsonb,
  rejection_reason    text,
  created_at          timestamptz not null,
  updated_at          timestamptz not null,
  confirmed_at        timestamptz,
  rejected_at         timestamptz,
  constraint bookings_time_order check (ends_at > starts_at and block_end > block_start),
  constraint bookings_no_overlap_live
    exclude using gist (tstzrange(block_start, block_end, '[)') with &&)
    where (status in ('pending', 'accepted') and not sandbox),
  constraint bookings_no_overlap_sandbox
    exclude using gist (tstzrange(block_start, block_end, '[)') with &&)
    where (status in ('pending', 'accepted') and sandbox)
);

create index bookings_list_idx on bookings (sandbox, status, created_at, id);

create table outbox (
  id               bigint generated always as identity primary key,
  booking_uid      text not null references bookings (uid),
  sandbox          boolean not null default false,
  kind             text not null check (kind in ('webhook', 'notification', 'calendar')),
  payload          text not null,
  status           text not null default 'pending' check (status in ('pending', 'delivered', 'dead')),
  attempts         integer not null default 0,
  next_attempt_at  timestamptz not null,
  locked_until     timestamptz,
  last_error       text,
  created_at       timestamptz not null,
  delivered_at     timestamptz
);

create index outbox_due_idx on outbox (next_attempt_at, id) where status = 'pending';
