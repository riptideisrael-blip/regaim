-- A film credit stays open for 7 days (was 3 hours): a parent can draw a scene again tomorrow without paying again.
-- Inside it: up to 20 paid steps (a drawing or an animation; a whole film uses about 12). Run once in Supabase:
-- SQL Editor -> New query -> paste -> Run.
create or replace function public.film_reserve_once(p_user uuid, p_ref text, p_again boolean) returns text language plpgsql security definer set search_path = public as $$
declare r film_credits%rowtype;
begin
  insert into film_credits(user_id) values (p_user) on conflict do nothing;
  select * into r from film_credits where user_id = p_user for update;
  if r.active_ref = p_ref and r.active_until > now() and r.calls < 40 then return 'active'; end if;
  if r.active_ref = p_ref and not coalesce(p_again, false) then return 'expired'; end if;
  if r.balance <= 0 then return 'empty'; end if;
  update film_credits set balance = balance - 1, active_until = now() + interval '7 days', calls = 0, active_ref = p_ref, updated_at = now()
   where user_id = p_user;
  return 'charged';
end $$;
revoke all on function public.film_reserve_once(uuid, text, boolean) from public, anon, authenticated;
grant execute on function public.film_reserve_once(uuid, text, boolean) to service_role;

-- New accounts start with 0 film credits (was 3 free): friends get credits only when the admin adds them.
alter table public.film_credits alter column balance set default 0;

-- Pitzki's voice (ElevenLabs) for every family, not only the admin: their child's name and boy/girl lines are made
-- in his voice too (on the admin's ElevenLabs account).
insert into public.film_settings (key, value) values ('pitzki_voice_access', 'everyone')
on conflict (key) do update set value = excluded.value;
