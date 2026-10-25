-- Family chat: each message carries an id made on the phone, so a message sent twice (the first answer was lost
-- on a bad connection) is stored only once. The app works without this too, just without that guard.
-- Supabase: SQL Editor > New query > paste all > Run. Safe to run again.
alter table public.family_messages add column if not exists client_id uuid;
create unique index if not exists family_messages_client on public.family_messages(author_id, client_id);
