-- Film look settings. Run once in Supabase: SQL Editor -> New query -> paste -> Run.
-- Afterwards, to change the look or the AI models, edit the rows here (SQL), no code editing needed.
-- An empty or missing row means "use the default written in the cartoon-film function".
create table if not exists public.film_settings (
  key text primary key,
  value text not null,
  updated_at timestamptz not null default now()
);
alter table public.film_settings enable row level security;
-- No policies: only the server (service role) can read or write it.

-- No rows needed: without rows the defaults written in the cartoon-film function are used.

-- Examples:
-- Switch the animation model:
--   update public.film_settings set value = 'fal-ai/kling-video/v2.6/pro/image-to-video', updated_at = now() where key = 'anim_model';
-- Change the drawing style (write the whole instruction in English):
--   update public.film_settings set value = 'Redraw this photo as ...', updated_at = now() where key = 'style_prompt';
-- Back to the default style:
--   update public.film_settings set value = '', updated_at = now() where key = 'style_prompt';
