-- Pitzki's voice (ElevenLabs). Run once in Supabase: SQL Editor > New query > paste > Run. Safe to run again.
-- Before this: add the secret ELEVENLABS_API_KEY in Supabase (Edge Functions > Secrets). Never paste the key here.

-- 1) The voice: Pitzki's voice in ElevenLabs (its Voice ID).
insert into public.film_settings (key, value) values ('pitzki_voice_id', 'rhjghUahMNz0SDYIYW96')
on conflict (key) do update set value = excluded.value, updated_at = now();

-- 2) Pitzki's shared folder also keeps the voice lines (mp3), made once for every family.
update storage.buckets set allowed_mime_types = array['image/jpeg', 'video/mp4', 'audio/mpeg'] where id = 'pitzki-shared';

-- Later, to let every family's child hear their own name in Pitzki's voice (it's "admin" while testing):
--   insert into public.film_settings (key, value) values ('pitzki_voice_access', 'everyone')
--   on conflict (key) do update set value = excluded.value, updated_at = now();
