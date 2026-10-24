// Supabase Edge Function: cartoon-film
// The app never sees the fal key. It asks this function to (1) reserve a credit, (2) draw a frame in a cartoon style,
// (3) draw the other frames like that first "anchor" character, (4) animate a drawn frame, (5) poll, (6) refund.
// Models and prompts can be changed from the film_settings table (SQL), without touching this code.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const FAL_KEY = Deno.env.get("FAL_KEY") ?? "";
// Pitzki's voice (ElevenLabs). Set as a Supabase secret, never in the app or in chat.
const ELEVEN_KEY = Deno.env.get("ELEVENLABS_API_KEY") ?? "";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
const SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

// ---------- defaults (film_settings rows override them) ----------
const DEFAULTS: Record<string, string> = {
  style_model: "fal-ai/flux-pro/kontext",
  ref_model: "fal-ai/nano-banana/edit",
  // Used only when the app sends profile photos for the faces. Must accept prompt + image_urls.
  face_model: "fal-ai/nano-banana/edit",
  anim_model: "fal-ai/kling-video/v2.5-turbo/pro/image-to-video",
  // A second pass that makes an "edited" picture fully 3D-animated (the face/character models keep too much photo).
  // Set to "off" in film_settings to skip it.
  polish_model: "fal-ai/flux-pro/kontext",
  anim_cfg: "0.6",
  // Payments through Google Play: "on" to sell books (needs the GOOGLE_PLAY_SERVICE_ACCOUNT secret). Off = credits only.
  payments: "off",
  // Printed books (Lulu): the product. Hardcover (casewrap), US Letter 8.5x11 in, full color premium, gloss.
  print_pod: "0850X1100FCPRECW080CW444GXX",
  // "everyone" lets every user order prints (only once customers pay for them); otherwise only the admin.
  print_orders: "admin",
  // The animated book: how a finished page comes to life. {scene} = what happens on the page.
  book_motion_prompt:
    "A scene from a high-end 3D animated family film. {scene} Bring the picture to life with gentle, natural motion: " +
    "breathing, blinking, small movements of the characters, hair and clothes swaying, soft light moving. " +
    "Slow cinematic camera push-in. Faces, characters and art style stay exactly the same as in the picture. " +
    "No text, no new characters, no sudden movements.",
  // Pitzki's lesson film: the drawn scene (Pitzki and the child) comes to life with strong, lively motion for 10 seconds.
  // {scene} = what happens in the scene. Model and prompt can be changed in film_settings like the others.
  pitzki_anim_model: "fal-ai/kling-video/v2.5-turbo/pro/image-to-video",
  pitzki_motion_prompt:
    "A scene from a high-end 3D animated children's feature film, full of life. {scene} " +
    "Both characters are clearly animated, never frozen: they walk, turn toward each other, gesture with their hands, point, " +
    "and react with big, readable facial expressions (wide smiles, surprise, delight, laughing). " +
    "The elf talks to the child with natural mouth movement as if speaking; the child listens, answers and reacts happily. " +
    "Smooth cinematic camera that follows the action. Faces, characters, clothes and art style stay exactly the same as in the picture. " +
    "No text, no new characters, no morphing, no extra limbs, no sudden cuts.",
  // Pitzki talking / singing with his lips matched to a sound (a picture + a sound -> a video). Any fal model that
  // takes image_url + audio_url: Kling AI Avatar (default), or e.g. "minimax/h3-max/lip-sync/image-to-video".
  pitzki_talk_model: "fal-ai/kling-video/ai-avatar/v2/standard",
  pitzki_talk_prompt:
    "Pitzki, a cheerful 3D animated forest elf, sings and talks to the viewer with lively, expressive facial expressions, " +
    "natural head and body movement and hand gestures, mouth exactly matching the sound. The art style and the character stay exactly the same.",
  // "admin" while testing (only the admin can use it); "everyone" to open it for all.
  pitzki_talk_access: "admin",
  // Pitzki's voice: the ElevenLabs voice id (from the voice's page), the model (Eleven v3 speaks Hebrew), and who
  // may make lines ("admin" while testing; "everyone" so every family's child hears their own name in his voice).
  pitzki_voice_id: "",
  pitzki_voice_model: "eleven_v3",
  pitzki_voice_access: "admin",
  // Fully cartoon, never "half real": realistic faces with cartoon eyes look creepy (the uncanny valley).
  style_prompt:
    "Redraw this photo as a frame from a warm, family-friendly 3D animated movie (Pixar / Disney animation style). " +
    "Turn every person into a fully stylized cartoon character: simplified cute features, soft rounded face, small nose, " +
    "friendly natural-size eyes with a happy expression, smooth matte skin with no skin texture or pores, soft toy-like look. " +
    "Children look adorable and sweet and keep their real young age: a toddler stays a toddler, with chubby cheeks, " +
    "small baby-like features and thin light eyebrows, never adult-looking features or wrinkles. " +
    "The photo may be taken with a wide-angle lens very close to the face: fix that distortion, give natural proportions, " +
    "and frame the character a little wider so the face is not huge or stretched. " +
    "Keep what makes each person recognizable: hair color and hairstyle, clothing and its colors, pose. " +
    "Redraw the background and every object in the same 3D animated style, with bright soft colors and gentle warm lighting. " +
    "Nothing may look photographic or realistic. No distorted faces, no extra fingers, no creepy or eerie expressions.",
  // Image 1 = the real photo of this scene. Image 2 = the approved cartoon character (the anchor).
  ref_prompt:
    "Edit the FIRST image. Take everything from the first image: the composition, framing, camera angle, pose, action, " +
    "background, objects and clothes. The second image is ONLY a character reference: it shows the same people already drawn " +
    "as 3D animated movie characters. Redraw the first image as a frame of that same 3D animated movie (Pixar / Disney style), " +
    "and draw every person with exactly the same cartoon face, age, hair and art style as in the second image. " +
    "Do NOT copy the pose, framing or background of the second image. A toddler stays a toddler. " +
    "Fix wide-angle lens distortion so faces are not huge or stretched. Nothing may look photographic. " +
    "No distorted faces, no extra fingers, no creepy expressions.",
  // Image 1 = the scene. Images 2+ = the family's profile photos of the people in it (for their faces only).
  face_prompt:
    "Edit the FIRST image. Take everything from the first image: the composition, framing, camera angle, pose, action, " +
    "background, objects and clothes. The other images are ONLY close-up face photos of the people in the first image: " +
    "use them for the faces and nothing else. Redraw the first image as a frame from a warm, family-friendly 3D animated movie " +
    "(Pixar / Disney animation style), and make each person clearly recognizable from the face photos: same face shape, " +
    "eye shape and color, eyebrows, nose, smile, skin tone, hair color, texture and hairstyle, as a cute 3D cartoon character " +
    "of the same age. Do NOT use the pose, framing, background or clothes of the face photos. A toddler stays a toddler. " +
    "Smooth stylized skin, soft warm lighting, nothing photographic. Fix wide-angle lens distortion so the face is not huge " +
    "or stretched. No distorted faces, no extra fingers, no creepy expressions.",
  polish_prompt:
    "Turn this picture into a frame of a high-quality, warm 3D animated family movie (Pixar / Disney animation style). " +
    "Keep the same people, faces, hair, clothes, pose, composition and background, but render EVERYTHING as stylized 3D " +
    "animation: smooth stylized skin with no photographic texture, soft rounded features, friendly natural-size eyes, " +
    "soft cinematic lighting, rich gentle colors. A toddler stays a toddler. Nothing may look like a photo. " +
    "No distorted faces, no oversized eyes, no extra fingers, no creepy expressions.",
  // {action} = what the moment is about (from the app). Kling sees the picture; it continues what is in it.
  motion_prompt:
    "A scene from a warm 3D animated family movie. {action} Gentle, natural, slow motion that continues what is happening " +
    "in the picture. The characters' faces stay stable, cute and consistent. Soft, slow camera movement.",
  negative_prompt:
    "realistic, photographic, uncanny, creepy, scary, distorted face, deformed face, morphing face, melting, " +
    "extra fingers, extra limbs, bad anatomy, big sudden movements, flicker, blur, low quality",
  // ---------- personal children's books ----------
  // Must accept prompt + image_urls (several references).
  // GPT Image draws the pages too: it keeps every character the same from page to page (the cheaper
  // "fal-ai/nano-banana/edit" can be set here or in film_settings, at a cost in likeness and style).
  book_model: "openai/gpt-image-2/edit",
  // Images = 1–3 real photos of the child (face crops). {age} and {outfit} come from the app.
  book_character_prompt:
    "Create the main character of a children's picture book from the child in these photos. " +
    "A {age}. Full body, standing, friendly relaxed pose, facing the viewer, on a plain soft cream background. " +
    "Art style: warm, gentle hand-painted picture-book illustration (soft gouache and watercolor textures, " +
    "rounded friendly shapes, soft light, muted warm colors). Keep exactly what makes the child recognizable: " +
    "face shape, eye shape and color, eyebrows, nose, hair color, texture and hairstyle, skin tone. " +
    "Keep the child's real age and proportions. Outfit: {outfit}. Only this one child. " +
    "No text, no letters, no logos, no frame.",
  // The character the parent approves (the face every page copies) is drawn by a model that keeps likeness best.
  // openai/* models (GPT Image on fal) get their own input; any other model gets the book_model input.
  book_character_model: "openai/gpt-image-2/edit",
  // The look of the whole book (the pages copy the character's style). Change it here or in film_settings.
  book_style:
    "a still from a Pixar / Disney 3D animated feature film: clearly stylized animated characters - a slightly larger head, " +
    "big expressive eyes, soft rounded cartoon shapes - while every person keeps their own recognizable face, hair and smile; " +
    "finely detailed, textured hair, skin " +
    "and clothing; a rich, detailed, believable real-world setting; vibrant saturated colors; warm golden-hour sunlight with " +
    "soft shadows (or warm lamp light at night); cinematic depth of field; crisp, sharp, highly detailed 4K film-still " +
    "quality - clearly an animated movie, never a photo",
  // Picture sizes for the GPT drawings ("WIDTHxHEIGHT"): tall pages fill a phone screen sharply, like a real film still.
  book_page_size: "1152x2048",
  // A film's first frame drawn with the family's approved book characters.
  film_chars_prompt:
    "Image 1 is a frame from a family's home video. Redraw it as a still from an animated feature film, in this style: " +
    "{style}. Keep image 1 exactly: the place, the camera angle and framing, everyone's pose, action and expression, and " +
    "their clothes. The people and pets in it are these characters of the family's books: {refs}. Draw each of them with " +
    "exactly that character's face, hair, skin tone and proportions, so they are instantly the same characters. Anyone " +
    "else in the frame is drawn in the same style. No text, no letters, no frame, no border.",
  // Enlarges finished pages for print (cheap: charged per second of computing).
  print_upscale_model: "fal-ai/esrgan",
  book_character_size: "1024x1536",
  // Pages: "openai/..." models get the GPT input (better at keeping references); others the book_model input.
  book_page_quality: "high",
  book_character_quality: "high",
  book_character_prompt_gpt:
    "Create the main character of a children's picture book: an animated movie character based on the child in these " +
    "photos. Style: {style}. It must NOT look like a photograph or a realistic child. Even as a cartoon, the parents must " +
    "recognize THIS child at once: keep their face shape, eye shape and color, eyebrows, nose, smile, hair color, curls " +
    "and hairstyle, and skin tone. Do not make them older or younger. A {age}. Full body, standing, relaxed friendly " +
    "pose, facing the viewer, on a plain soft cream background, portrait orientation. Outfit: {outfit}. Only this one " +
    "child. No text, no letters, no logos, no frame.",
  // Image 1 = the approved character. Image 2 = a real photo of the child (face only). {scene} {outfit} from the app.
  book_page_prompt:
    "Create a new illustration for the same children's picture book, rendered in this style: {style}. " +
    "The FIRST image is the book's approved main character: draw the SAME child - the same face, hair, age, proportions " +
    "and outfit ({outfit}) - and match the first image's style. The second image is a real photo of the child: use it " +
    "only to keep the face recognizable. Do not copy the pose or background of any reference image.{parent} Scene: {scene} " +
    "Gentle, warm, reassuring mood. The child is clearly visible. No text, no letters, no speech bubbles, no frame.",
  // Pages with labelled references (every person in the picture has a character sheet).
  book_page_cast_prompt:
    "Create one full-page illustration for a children's picture book, rendered in this style: {style}. " +
    "Reference images (character sheets of this book): {refs}. Draw every person exactly like their character sheet - " +
    "the same face, hair, skin tone, body proportions, age and outfit - so they are instantly recognizable as the same " +
    "characters on every page. Do not copy the sheets' poses or plain backgrounds; place them in a full, detailed, " +
    "cinematic scene.{people} Scene (people are named by their [id]): {scene} {layout} Warm, emotional, beautifully lit, " +
    "like a still from an animated feature film: vivid colors, crisp detail, a real, lived-in place full of small details. " +
    "If a real photo of a person is given, use it for that person's true face (the character sheet for the style and outfit). " +
    "No text, no letters, no signs, no speech bubbles, no frame, no border.",
  book_page_layout: "Tall portrait composition (like a phone screen); keep the lower third of the picture calm and simple (soft floor, sky or wall) because the story text is printed there.",
  book_cover_layout: "Portrait book-cover composition; the hero large and central, and keep the upper fifth calm and simple because the title is printed there.",
  // Another person in the story (from their photo when the parent gave one), always in the hero's style.
  book_cast_prompt_photo:
    "Create a character for the same children's picture book. Image 1 is a real photo of the person: turn them into an " +
    "animated movie character that their family recognizes at once (keep their face shape, eyes, eyebrows, nose, hair, " +
    "beard or no beard, skin tone, build and approximate age). Image 2 is the book's main character ({hero}): use exactly " +
    "the same art style, rendering and proportions. This person is: {who}. Full body, standing, relaxed and friendly, " +
    "facing the viewer, on a plain soft cream background, everyday clothes that fit them. Only this one character. " +
    "If this is an animal (the family's pet): keep its breed, size, fur color and markings, ears and face exactly, as an " +
    "animated movie animal on all fours, without clothes. No text, no letters, no logos, no frame.",
  book_cast_prompt_nophoto:
    "Create a character for the same children's picture book. The image is the book's main character ({hero}): use " +
    "exactly the same art style, rendering and proportions. Draw a new character: {who}. Warm, kind and believable. " +
    "Full body, standing, relaxed and friendly, facing the viewer, on a plain soft cream background, everyday clothes. " +
    "Only this one character (an animal is drawn as an animated movie animal, without clothes). No text, no letters, no logos, no frame.",
  book_page_parent:
    " The third image is a real photo of the child's {role}: whenever the {role} appears in the scene, draw them as this " +
    "real person - their face shape, hair, beard or no beard, skin tone and build - in the same animated style, so the " +
    "family recognizes them.",
};
const ASPECTS = ["9:16", "16:9", "3:4", "4:3", "1:1"];

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};
const json = (o: unknown, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { ...cors, "Content-Type": "application/json" } });

// deno-lint-ignore no-explicit-any
async function settings(admin: any): Promise<Record<string, string>> {
  const out = { ...DEFAULTS };
  try {
    const { data } = await admin.from("film_settings").select("key,value");
    for (const row of data ?? []) {
      const v = String(row.value ?? "").trim();
      if (v) out[String(row.key)] = v;
    }
  } catch (_) { /* no table yet: defaults */ }
  return out;
}

// ---------- printed books (Lulu) ----------
const PRINT_ADMINS = ["yossefrevivo@gmail.com"];
const LULU = Deno.env.get("LULU_SANDBOX") === "1" ? "https://api.sandbox.lulu.com" : "https://api.lulu.com";
let luluAuth: { token: string; until: number } | null = null;

/** An access token for Lulu's Print API, from the LULU_CLIENT_KEY / LULU_CLIENT_SECRET secrets. */
async function luluToken(): Promise<string> {
  if (luluAuth && luluAuth.until > Date.now() + 60_000) return luluAuth.token;
  const key = Deno.env.get("LULU_CLIENT_KEY"), secret = Deno.env.get("LULU_CLIENT_SECRET");
  if (!key || !secret) throw new Error("no-lulu-keys");
  const r = await fetch(`${LULU}/auth/realms/glasstree/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded", Authorization: "Basic " + btoa(`${key}:${secret}`) },
    body: "grant_type=client_credentials",
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.access_token) throw new Error("lulu-auth " + r.status);
  luluAuth = { token: j.access_token, until: Date.now() + Number(j.expires_in ?? 3600) * 1000 };
  return luluAuth.token;
}

async function lulu(method: string, path: string, body?: unknown): Promise<{ ok: boolean; status: number; j: any }> {
  const r = await fetch(`${LULU}${path}`, {
    method, headers: { Authorization: "Bearer " + await luluToken(), "Content-Type": "application/json", "Cache-Control": "no-cache" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) console.log(JSON.stringify({ ev: "lulu_failed", path, status: r.status, body: JSON.stringify(j).slice(0, 600) }));
  return { ok: r.ok, status: r.status, j };
}

const cleanAddress = (a: any) => ({
  name: clean(a?.name, 80), street1: clean(a?.street1, 120), street2: clean(a?.street2, 120) || undefined,
  city: clean(a?.city, 80), postcode: clean(a?.postcode, 20), country_code: clean(a?.country_code || "IL", 2).toUpperCase(),
  state_code: clean(a?.state_code, 20) || undefined, phone_number: clean(a?.phone_number, 30),
});
const SHIPPING = ["MAIL", "PRIORITY_MAIL", "GROUND", "EXPEDITED", "EXPRESS"];
/** Lulu's own words for what went wrong, short (so the screenshot says what to fix). */
const luluWhy = (j: any) => JSON.stringify(j?.detail ?? j?.errors ?? j?.message ?? j).slice(0, 240);

async function printAction(admin: any, uid: string, action: string, body: any, s: Record<string, string>) {
  const pod = /^[0-9A-Z]{27}$/.test(String(body.pod ?? "")) ? String(body.pod) : s.print_pod;
  const pages = Math.max(0, Math.min(800, Math.floor(Number(body.pages ?? 0))));
  try {
    // Cover size for this book (the spine depends on the page count).
    if (action === "print_cover") {
      const r = await lulu("POST", "/cover-dimensions/", { pod_package_id: pod, interior_page_count: pages, unit: "pt" });
      if (!r.ok) return json({ error: "Lulu: " + luluWhy(r.j) }, 502);
      return json({ ok: true, pod, width: Number(r.j.width), height: Number(r.j.height) });
    }
    // Price of the book and the shipping, to this address.
    if (action === "print_quote") {
      const ship = SHIPPING.includes(String(body.shipping)) ? String(body.shipping) : "MAIL";
      const r = await lulu("POST", "/print-job-cost-calculations/", {
        line_items: [{ pod_package_id: pod, page_count: pages, quantity: 1 }],
        shipping_address: cleanAddress(body.address), shipping_option: ship,
      });
      if (!r.ok) return json({ error: "Lulu: " + luluWhy(r.j) }, 502);
      return json({
        ok: true, currency: r.j.currency ?? "USD", total: r.j.total_cost_incl_tax, total_excl_tax: r.j.total_cost_excl_tax,
        book: r.j.line_item_costs?.[0]?.total_cost_incl_tax, shipping: r.j.shipping_cost?.total_cost_incl_tax,
        fees: r.j.fulfillment_cost?.total_cost_incl_tax,
      });
    }
    // The order: the two PDFs the phone uploaded to the "print" storage (only this account's folder) go to Lulu.
    if (action === "print_order") {
      const interior = String(body.interior ?? ""), cover = String(body.cover ?? "");
      if (!interior.startsWith(uid + "/") || !cover.startsWith(uid + "/") || interior.includes("..") || cover.includes("..")) return json({ error: "קבצים לא תקינים." }, 400);
      const sign = async (p: string) => (await admin.storage.from("print").createSignedUrl(p, 60 * 60 * 24 * 14)).data?.signedUrl ?? "";
      const [iu, cu] = [await sign(interior), await sign(cover)];
      if (!iu || !cu) return json({ error: "קבצי ההדפסה לא נמצאו (צריך להריץ את print-books.sql)." }, 400);
      const ship = SHIPPING.includes(String(body.shipping)) ? String(body.shipping) : "MAIL";
      const base = {
        external_id: clean(body.book, 60), contact_email: clean(body.email, 120), shipping_level: ship,
        shipping_address: cleanAddress(body.address),
      };
      const title = clean(body.title, 120) || "Moments book";
      let r = await lulu("POST", "/print-jobs/", { ...base, line_items: [{ title, quantity: 1,
        printable_normalization: { pod_package_id: pod, cover: { source_url: cu }, interior: { source_url: iu } } }] });
      // Older form of the same request, if this account's API still expects it.
      if (!r.ok && r.status === 400 && /printable_normalization|pod_package_id/.test(JSON.stringify(r.j))) {
        r = await lulu("POST", "/print-jobs/", { ...base, line_items: [{ title, quantity: 1, pod_package_id: pod,
          cover: { source_url: cu }, interior: { source_url: iu } }] });
      }
      if (!r.ok) return json({ error: "Lulu: " + luluWhy(r.j) }, 502);
      return json({ ok: true, id: r.j.id, status: r.j.status?.name ?? "" });
    }
    if (action === "print_status") {
      const id = String(body.id ?? "");
      if (!/^\d{1,12}$/.test(id)) return json({ error: "מזהה לא תקין." }, 400);
      const r = await lulu("GET", `/print-jobs/${id}/`);
      if (!r.ok) return json({ error: "Lulu: " + luluWhy(r.j) }, 502);
      const li = r.j.line_items?.[0] ?? {};
      return json({
        ok: true, status: r.j.status?.name ?? "", message: r.j.status?.message ?? "",
        tracking: li.tracking_urls ?? li.tracking_url ?? [], total: r.j.costs?.total_cost_incl_tax, currency: r.j.costs?.currency,
      });
    }
    return json({ error: "בקשה לא מוכרת." }, 400);
  } catch (e) {
    const m = String(e);
    if (m.includes("no-lulu-keys")) return json({ error: "השרת עוד לא מחובר ל-Lulu (חסרים המפתחות LULU_CLIENT_KEY ו-LULU_CLIENT_SECRET)." }, 503);
    console.log(JSON.stringify({ ev: "print_failed", err: m }));
    return json({ error: "Lulu לא זמינה כרגע. נסו שוב." }, 502);
  }
}

// ---------- deleting an account ----------

/** Every file under [prefix] in [bucket] (folders are opened, a few levels deep). */
async function listFiles(admin: any, bucket: string, prefix: string, depth = 0): Promise<string[]> {
  if (depth > 3) return [];
  const out: string[] = [];
  for (let offset = 0; offset < 10_000; offset += 1000) {
    const { data, error } = await admin.storage.from(bucket).list(prefix, { limit: 1000, offset });
    if (error || !data || data.length === 0) break;
    for (const e of data) {
      const path = `${prefix}/${e.name}`;
      if (e.id) out.push(path); else out.push(...await listFiles(admin, bucket, path, depth + 1));
    }
    if (data.length < 1000) break;
  }
  return out;
}

async function removeFiles(admin: any, bucket: string, paths: string[]) {
  const clean = [...new Set(paths.filter((p) => p && !p.includes("..")))];
  for (let i = 0; i < clean.length; i += 100) await admin.storage.from(bucket).remove(clean.slice(i, i + 100));
}

/** A stored media value (a path, or a link to our storage) as its path inside [bucket]. */
const pathIn = (value: unknown, bucket: string) => {
  const v = String(value ?? "");
  if (!v || v === "null") return "";
  if (!v.includes("://")) return v;
  const i = v.indexOf(`/${bucket}/`);
  return i >= 0 ? v.slice(i + bucket.length + 2).split("?")[0] : "";
};

/**
 * Removes the user and what they put on the server: their family posts, comments, reactions and messages (with
 * the files), their community posts (with the files), likes, reports and blocks, credits and purchase records.
 * A family they own goes to the member who joined first; a family left with nobody is deleted with its files.
 */
async function deleteAccount(admin: any, uid: string): Promise<{ ok: boolean; error?: string }> {
  try {
    // Families: files and posts they shared, then ownership.
    const { data: mine } = await admin.from("family_memberships").select("circle_id").eq("user_id", uid);
    const circles: string[] = (mine ?? []).map((m: any) => String(m.circle_id));
    const { data: posts } = await admin.from("posts").select("id,video,cover").eq("author_id", uid);
    const famFiles: string[] = [];
    for (const p of posts ?? []) famFiles.push(pathIn(p.video, "moments"), pathIn(p.cover, "moments"));
    for (const c of circles) famFiles.push(...await listFiles(admin, "moments", `${c}/${uid}`));
    await admin.from("comments").delete().eq("author_id", uid);
    await admin.from("reactions").delete().eq("author_id", uid);
    await admin.from("family_messages").delete().eq("author_id", uid);
    await admin.from("posts").delete().eq("author_id", uid);
    const { data: owned } = await admin.from("circles").select("id").eq("owner_id", uid);
    for (const c of owned ?? []) {
      const { data: next } = await admin.from("family_memberships").select("user_id,joined_at").eq("circle_id", c.id)
        .neq("user_id", uid).order("joined_at", { ascending: true }).limit(1);
      if (next && next.length > 0) {
        await admin.from("circles").update({ owner_id: next[0].user_id }).eq("id", c.id);
      } else {
        const { data: left } = await admin.from("posts").select("video,cover").eq("circle_id", c.id);
        for (const p of left ?? []) famFiles.push(pathIn(p.video, "moments"), pathIn(p.cover, "moments"));
        famFiles.push(...await listFiles(admin, "moments", String(c.id)));
        await admin.from("circles").delete().eq("id", c.id);
      }
    }
    await removeFiles(admin, "moments", famFiles);
    // Community: every file in their folder (posts, book pages).
    await removeFiles(admin, "community", await listFiles(admin, "community", uid));
    await removeFiles(admin, "print", await listFiles(admin, "print", uid));
    await admin.from("ai_usage").delete().eq("budget_key", `user:${uid}`);
    // The account itself; the rest (memberships, community rows, credits, purchases, receipts, votes) goes with it.
    const { error } = await admin.auth.admin.deleteUser(uid);
    if (error) {
      console.log(JSON.stringify({ ev: "delete_account_failed", err: String(error.message ?? error) }));
      return { ok: false, error: "לא הצלחנו למחוק את החשבון. נסו שוב או כתבו לנו." };
    }
    console.log(JSON.stringify({ ev: "account_deleted" }));
    return { ok: true };
  } catch (e) {
    console.log(JSON.stringify({ ev: "delete_account_failed", err: String(e) }));
    return { ok: false, error: "לא הצלחנו למחוק את החשבון. נסו שוב או כתבו לנו." };
  }
}

// ---------- Google Play purchases ----------
// Product id -> credits it gives. The same ids are set up in Play Console as one-time (consumable) products.
const PLAY_PRODUCTS: Record<string, number> = { book_digital: 1, book_animated: 2, book_animation: 1 };
const PLAY_PACKAGE = "com.regaim.app";

let playAuth: { token: string; until: number } | null = null;

const b64url = (b: ArrayBuffer | Uint8Array | string) => {
  const bytes = typeof b === "string" ? new TextEncoder().encode(b) : new Uint8Array(b as ArrayBuffer);
  let s = ""; for (const x of bytes) s += String.fromCharCode(x);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

/** An access token for the Google Play Developer API, from the service account kept in the GOOGLE_PLAY_SERVICE_ACCOUNT secret. */
async function playToken(): Promise<string> {
  if (playAuth && playAuth.until > Date.now() + 60_000) return playAuth.token;
  const raw = Deno.env.get("GOOGLE_PLAY_SERVICE_ACCOUNT");
  if (!raw) throw new Error("no-service-account");
  const sa = JSON.parse(raw);
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600,
  }));
  const pem = String(sa.private_key).replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${head}.${claim}`));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${head}.${claim}.${b64url(sig)}` }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.access_token) throw new Error("google-auth " + r.status);
  playAuth = { token: j.access_token, until: Date.now() + (Number(j.expires_in ?? 3600) * 1000) };
  return playAuth.token;
}

/** Asks Google whether this purchase is real and paid. */
async function verifyPlayPurchase(product: string, token: string):
  Promise<{ ok: true; account: string; order: string } | { ok: false; error: string; status: number }> {
  let access: string;
  try { access = await playToken(); } catch (e) {
    console.log(JSON.stringify({ ev: "play_auth_failed", err: String(e) }));
    return { ok: false, error: "השרת עוד לא מחובר ל-Google Play.", status: 503 };
  }
  const url = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PLAY_PACKAGE}/purchases/products/` +
    `${encodeURIComponent(product)}/tokens/${encodeURIComponent(token)}`;
  const r = await fetch(url, { headers: { Authorization: "Bearer " + access } });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) {
    console.log(JSON.stringify({ ev: "play_verify_failed", status: r.status, body: JSON.stringify(j).slice(0, 300) }));
    return { ok: false, error: "Google לא אישרה את הרכישה.", status: 402 };
  }
  // purchaseState: 0 = paid, 1 = cancelled, 2 = pending.
  if (Number(j.purchaseState) !== 0) return { ok: false, error: "התשלום עוד לא הושלם.", status: 402 };
  return { ok: true, account: String(j.obfuscatedExternalAccountId ?? ""), order: String(j.orderId ?? "") };
}

/** "1152x2048" -> { width, height } for GPT drawings (multiples of 16, within the model's limits), else undefined = auto. */
function size(v: string | undefined): { width: number; height: number } | undefined {
  const m = /^(\d{3,4})x(\d{3,4})$/.exec(String(v ?? "").trim());
  if (!m) return undefined;
  const fit = (n: number) => Math.max(512, Math.min(3840, Math.round(n / 16) * 16));
  return { width: fit(Number(m[1])), height: fit(Number(m[2])) };
}

// ---------- paid services running out (pilot: the admin screen shows which one) ----------
/** A service's answer that means "no money / no quota / bad key", not an ordinary error. */
const outOfCredit = (status: number, body: string) =>
  status === 401 || status === 402 || /balance|locked|quota|credit|insufficient|billing|exhausted|payment/i.test(body);
/** Writes (or refreshes) the alert for [service]. Never fails the request. */
async function serviceAlert(admin: any, service: string, status: number, detail: string) {
  try {
    await admin.from("service_alerts").upsert({ service, status, message: String(detail).slice(0, 300), at: new Date().toISOString(), ok_at: null });
  } catch (_) { /* no table yet */ }
}
/** [service] works again: an open alert for it is marked fixed. */
async function serviceOk(admin: any, service: string) {
  try { await admin.from("service_alerts").update({ ok_at: new Date().toISOString() }).eq("service", service).is("ok_at", null); } catch (_) { /* no table yet */ }
}

const clean = (s: unknown, max: number) => String(s ?? "").replace(/[\r\n\t]+/g, " ").replace(/[{}]/g, "").trim().slice(0, max);

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    if (!FAL_KEY) return json({ error: "השרת עוד לא הוגדר (חסר מפתח fal)." }, 500);
    const auth = req.headers.get("Authorization") ?? "";
    const userClient = createClient(SUPABASE_URL, ANON, { global: { headers: { Authorization: auth } } });
    const { data: u } = await userClient.auth.getUser();
    const uid = u?.user?.id;
    if (!uid) return json({ error: "צריך להתחבר לחשבון." }, 401);
    // Guest (anonymous) sign-ins can't spend film credits: otherwise anyone could script free films on our fal key.
    if ((u?.user as { is_anonymous?: boolean })?.is_anonymous) return json({ error: "כדי ליצור סרט צריך להתחבר עם מייל או Google." }, 403);
    const admin = createClient(SUPABASE_URL, SERVICE);
    const body = await req.json().catch(() => ({}));
    const action = String(body.action ?? "");

    if (action === "credits") {
      const { data } = await admin.rpc("film_balance", { p_user: uid });
      // "pay": payments through Google Play are on (film_settings "payments" = "on"); off by default.
      const s = await settings(admin);
      return json({ credits: data ?? 0, pay: s.payments === "on" });
    }
    // Printed books through Lulu (print on demand). For now only the admin account can order (orders are paid from the
    // Lulu account); film_settings "print_orders" = "everyone" opens it once customers pay for prints.
    if (action.startsWith("print_")) {
      const s = await settings(admin);
      const email = String(u?.user?.email ?? "").toLowerCase();
      if (s.print_orders !== "everyone" && !PRINT_ADMINS.includes(email)) return json({ error: "הזמנת ספר מודפס עוד לא פתוחה." }, 403);
      return await printAction(admin, uid, action, body, s);
    }
    // Deleting the account and everything it put on the server (Google Play requires this in the app).
    // The phone's own memories stay on the phone (they never were on the server).
    if (action === "delete_account") {
      if (body.confirm !== "DELETE") return json({ error: "חסר אישור למחיקה." }, 400);
      const result = await deleteAccount(admin, uid);
      return result.ok ? json({ ok: true }) : json({ error: result.error }, 500);
    }
    // A Google Play purchase: checked with Google, then its credits are added once (the same purchase never twice).
    if (action === "play_purchase") {
      const token = String(body.token ?? "");
      const product = String(body.product ?? "");
      const credits = PLAY_PRODUCTS[product];
      if (!credits || !token || token.length > 1000) return json({ error: "רכישה לא תקינה." }, 400);
      const check = await verifyPlayPurchase(product, token);
      if (!check.ok) return json({ error: check.error }, check.status);
      // The purchase must belong to this account (the app sends the account id with the purchase).
      if (check.account && check.account !== uid) return json({ error: "הרכישה שייכת לחשבון אחר." }, 403);
      const { data, error } = await admin.rpc("film_grant_purchase", {
        p_user: uid, p_token: token, p_product: product, p_credits: credits, p_order: check.order,
      });
      if (error) return json({ error: "השרת עוד לא מוכן לתשלומים (צריך להריץ את payments-and-community-books.sql)." }, 500);
      if (data === "other") return json({ error: "הרכישה כבר שויכה לחשבון אחר." }, 403);
      return json({ ok: true, granted: data === "granted" });
    }
    // A book sends its own reference ("ref"): asking again for the same book never takes a second credit
    // (a phone on a weak signal may resend a request whose answer was lost). Needs book-credits.sql; without it, the old way.
    const ref = clean(body.ref, 80);
    if (action === "reserve" && ref) {
      const { data, error } = await admin.rpc("film_reserve_once", { p_user: uid, p_ref: ref, p_again: body.again === true });
      if (!error) {
        if (data === "active" || data === "charged") return json({ ok: true, charged: data === "charged" });
        if (data === "expired") return json({ error: "expired", expired: true }, 409);
        return json({ error: "אין לך קרדיטים לסרטים. אפשר להוסיף קרדיטים בקרוב." }, 402);
      }
    }
    if (action === "refund" && ref) {
      const { data, error } = await admin.rpc("film_refund_once", { p_user: uid, p_ref: ref });
      if (!error) return json({ ok: true, refunded: data === true });
    }
    if (action === "reserve") {
      const { data } = await admin.rpc("film_reserve", { p_user: uid });
      if (!data) return json({ error: "אין לך קרדיטים לסרטים. אפשר להוסיף קרדיטים בקרוב." }, 402);
      return json({ ok: true });
    }
    if (action === "refund") {
      await admin.rpc("film_refund", { p_user: uid });
      return json({ ok: true });
    }
    // The admin screen: which paid service ran out (and whether it works again).
    if (action === "service_alerts") {
      const email = String(u?.user?.email ?? "").toLowerCase();
      if (!PRINT_ADMINS.includes(email)) return json({ error: "not allowed" }, 403);
      const { data, error } = await admin.from("service_alerts").select("service,status,message,at,ok_at").order("at", { ascending: false });
      if (error) return json({ error: "no table", detail: error.message }, 404);
      return json({ alerts: data ?? [] });
    }
    // Pitzki says one line in his voice (ElevenLabs). No film credit: lines are short and cheap; the app keeps each
    // line on the phone and the shared ones in the shared folder, so each is made once.
    if (action === "tts") {
      const s = await settings(admin);
      const email = String(u?.user?.email ?? "").toLowerCase();
      if (s.pitzki_voice_access !== "everyone" && !PRINT_ADMINS.includes(email)) return json({ error: "עוד בבדיקה." }, 403);
      if (!ELEVEN_KEY || !s.pitzki_voice_id) return json({ error: "הקול של פיצקי עוד לא הוגדר בשרת." }, 503);
      const text = String(body.text ?? "").replace(/[\r\n\t]+/g, " ").trim().slice(0, 400);
      if (!text) return json({ error: "חסר טקסט." }, 400);
      const r = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(s.pitzki_voice_id)}?output_format=mp3_44100_128`, {
        method: "POST",
        headers: { "xi-api-key": ELEVEN_KEY, "Content-Type": "application/json", Accept: "audio/mpeg" },
        body: JSON.stringify({ text, model_id: s.pitzki_voice_model }),
      });
      if (!r.ok) {
        const why = (await r.text().catch(() => "")).slice(0, 300);
        console.log(JSON.stringify({ ev: "tts_failed", status: r.status, body: why }));
        if (outOfCredit(r.status, why)) await serviceAlert(admin, "elevenlabs", r.status, why);
        return json({ error: `שירות הקול לא זמין כרגע (${r.status}).`, detail: why }, 502);
      }
      await serviceOk(admin, "elevenlabs");
      const bytes = new Uint8Array(await r.arrayBuffer());
      let bin = "";
      for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
      return json({ audio: btoa(bin) });
    }
    if (action === "submit") {
      const kind = String(body.kind ?? "");
      // Preparing a finished book for print (a cheap enlargement of pages already paid for) doesn't use the credit's calls.
      if (kind !== "book_upscale") {
        const { data: allowed } = await admin.rpc("film_use_call", { p_user: uid });
        if (!allowed) return json({ error: "אין הרשאה להמשיך. התחילו סרט מחדש." }, 402);
      }
      const s = await settings(admin);
      const image = String(body.image ?? "");
      const aspect = ASPECTS.includes(String(body.aspect ?? "")) ? String(body.aspect) : undefined;
      if (!image || image.length > 4_000_000) return json({ error: "תמונה לא תקינה." }, 400);
      let model: string;
      let input: Record<string, unknown>;
      // Up to two face photos from the family's profile (data: URIs from the app, kept small).
      const refs = (Array.isArray(body.refs) ? body.refs : [])
        .map((r: unknown) => String(r ?? ""))
        .filter((r: string) => (r.startsWith("data:image/") || r.startsWith("https://")) && r.length <= 1_500_000)
        .slice(0, 9);
      if (kind === "style_chars" && refs.length > 0) {
        // A film frame drawn with the family's approved book characters (labelled), so the film matches the books.
        model = s.book_model;
        const pics = [image, ...refs].filter((r: string) => r.startsWith("data:image/")).slice(0, 7);
        const labels = (Array.isArray(body.labels) ? body.labels : []).slice(0, pics.length - 1)
          .map((l: unknown, k: number) => `Image ${k + 2} = ${clean(l, 160)}`);
        const prompt = s.film_chars_prompt.replace("{style}", s.book_style).replace("{refs}", labels.join("; "));
        const sizes: Record<string, string> = { "9:16": "1152x2048", "3:4": "1536x2048", "16:9": "2048x1152", "4:3": "2048x1536", "1:1": "1536x1536" };
        const quality = ["low", "medium", "high"].includes(s.book_page_quality) ? s.book_page_quality : "high";
        input = { prompt, image_urls: pics, quality, num_images: 1, output_format: "jpeg", ...(size(sizes[aspect ?? "9:16"]) ? { image_size: size(sizes[aspect ?? "9:16"]) } : {}) };
      } else if (kind === "style" && refs.length > 0) {
        model = s.face_model;
        input = { prompt: s.face_prompt, image_urls: [image, ...refs.slice(0, 2)], output_format: "jpeg", ...(aspect ? { aspect_ratio: aspect } : {}) };
      } else if (kind === "style") {
        model = s.style_model;
        input = { prompt: s.style_prompt, image_url: image, output_format: "jpeg", ...(aspect ? { aspect_ratio: aspect } : {}) };
      } else if (kind === "style_ref") {
        const ref = String(body.ref ?? "");
        if (!ref.startsWith("https://")) return json({ error: "חסרה דמות העוגן." }, 400);
        model = s.ref_model;
        input = { prompt: s.ref_prompt, image_urls: [image, ref], output_format: "jpeg", ...(aspect ? { aspect_ratio: aspect } : {}) };
      } else if (kind === "book_character") {
        // Up to three real photos of the child: the first is `image`, the rest come in refs (data: URIs only).
        model = s.book_character_model || s.book_model;
        const photos = [image, ...refs].filter((r: string) => r.startsWith("data:image/")).slice(0, 3);
        if (photos.length === 0) return json({ error: "חסרה תמונה של הילד." }, 400);
        const age = clean(body.age, 80) || "young child";
        const outfit = clean(body.outfit, 200) || "simple comfortable everyday clothes";
        if (model.startsWith("openai/")) {
          const prompt = s.book_character_prompt_gpt.replace("{style}", s.book_style).replace("{age}", age).replace("{outfit}", outfit);
          const quality = ["low", "medium", "high"].includes(s.book_character_quality) ? s.book_character_quality : "high";
          input = { prompt, image_urls: photos, quality, num_images: 1, output_format: "jpeg", ...(size(s.book_character_size) ? { image_size: size(s.book_character_size) } : {}) };
        } else {
          const prompt = s.book_character_prompt.replace("{age}", age).replace("{outfit}", outfit);
          input = { prompt, image_urls: photos, output_format: "jpeg", ...(aspect ? { aspect_ratio: aspect } : {}) };
        }
      } else if (kind === "book_cast") {
        model = s.book_character_model || s.book_model;
        const pics = [image, ...refs].filter((r: string) => r.startsWith("data:image/")).slice(0, 2);
        const withPhoto = body.photo === true && pics.length >= 2;
        const prompt = (withPhoto ? s.book_cast_prompt_photo : s.book_cast_prompt_nophoto)
          .replace("{hero}", clean(body.hero, 40) || "the hero").replace("{who}", clean(body.who, 200) || "a kind adult");
        const quality = ["low", "medium", "high"].includes(s.book_character_quality) ? s.book_character_quality : "high";
        input = model.startsWith("openai/")
          ? { prompt, image_urls: withPhoto ? pics : pics.slice(0, 1), quality, num_images: 1, output_format: "jpeg", ...(size(s.book_character_size) ? { image_size: size(s.book_character_size) } : {}) }
          : { prompt, image_urls: withPhoto ? pics : pics.slice(0, 1), output_format: "jpeg", ...(aspect ? { aspect_ratio: aspect } : {}) };
      } else if (kind === "book_page" && Array.isArray(body.labels) && body.labels.length > 0) {
        // Every person in the picture comes with their labelled character sheet (image 1 = the hero).
        model = s.book_model;
        const scene = clean(body.scene, 900);
        if (!scene) return json({ error: "חסר תיאור העמוד." }, 400);
        const pics = [image, ...refs].filter((r: string) => r.startsWith("data:image/")).slice(0, 10);
        const labels = (body.labels as unknown[]).slice(0, pics.length).map((l, k) => `Image ${k + 1} = ${clean(l, 220)}`);
        const people = clean(body.people, 500);
        const prompt = s.book_page_cast_prompt.replace("{style}", s.book_style).replace("{refs}", labels.join("; "))
          .replace("{people}", people ? ` Also in the scene, without a reference image (draw them simply and keep them consistent): ${people}.` : "")
          .replace("{scene}", scene).replace("{layout}", body.cover === true ? s.book_cover_layout : s.book_page_layout);
        if (model.startsWith("openai/")) {
          const quality = ["low", "medium", "high"].includes(s.book_page_quality) ? s.book_page_quality : "high";
          input = { prompt, image_urls: pics, quality, num_images: 1, output_format: "jpeg", ...(size(s.book_page_size) ? { image_size: size(s.book_page_size) } : {}) };
        } else {
          input = { prompt, image_urls: pics, output_format: "jpeg", ...(aspect ? { aspect_ratio: aspect } : {}) };
        }
      } else if (kind === "book_page") {
        // `image` = the approved character (sent from the phone each time, so it never depends on an expired link).
        model = s.book_model;
        const scene = clean(body.scene, 700);
        if (!scene) return json({ error: "חסר תיאור העמוד." }, 400);
        // Image 3 (optional) = the parent's face, from their profile photo, so the parent in the story looks like them.
        const role = clean(body.parent, 30).replace(/[^a-z ]/gi, "");
        const pics = [image, ...refs.slice(0, role ? 2 : 1)];
        const parentLine = role && pics.length >= 3 ? s.book_page_parent.replaceAll("{role}", role) : "";
        const prompt = s.book_page_prompt.replace("{style}", s.book_style).replace("{parent}", parentLine)
          .replace("{scene}", scene).replace("{outfit}", clean(body.outfit, 200) || "the same outfit");
        if (model.startsWith("openai/")) {
          const quality = ["low", "medium", "high"].includes(s.book_page_quality) ? s.book_page_quality : "medium";
          input = { prompt, image_urls: pics, quality, num_images: 1, output_format: "jpeg", ...(size(s.book_page_size) ? { image_size: size(s.book_page_size) } : {}) };
        } else {
          input = { prompt, image_urls: pics, output_format: "jpeg", ...(aspect ? { aspect_ratio: aspect } : {}) };
        }
      } else if (kind === "book_upscale") {
        // Print quality: the finished page, enlarged twice (sharp at print size). Only the page picture is sent.
        if (!image.startsWith("data:image/")) return json({ error: "תמונה לא תקינה." }, 400);
        model = s.print_upscale_model;
        input = { image_url: image, scale: 2, model: "RealESRGAN_x4plus", output_format: "jpeg" };
      } else if (kind === "polish") {
        if (!image.startsWith("https://")) return json({ error: "כתובת תמונה לא תקינה." }, 400);
        if (s.polish_model === "off") return json({ skip: true });
        model = s.polish_model;
        input = { prompt: s.polish_prompt, image_url: image, output_format: "jpeg", ...(aspect ? { aspect_ratio: aspect } : {}) };
      } else if (kind === "book_anim") {
        // The animated book: a page the family already approved comes to life for 5 seconds (no drawing again).
        if (!image.startsWith("data:image/") && !image.startsWith("https://")) return json({ error: "תמונה לא תקינה." }, 400);
        model = s.anim_model;
        const scene = clean(body.scene, 500);
        const prompt = s.book_motion_prompt.includes("{scene}") ? s.book_motion_prompt.replace("{scene}", scene) : `${s.book_motion_prompt} ${scene}`;
        const cfg = Number(s.anim_cfg);
        input = {
          prompt: prompt.trim(), image_url: image, duration: "5", negative_prompt: s.negative_prompt,
          ...(Number.isFinite(cfg) ? { cfg_scale: cfg } : {}),
        };
      } else if (kind === "pitzki_talk") {
        // Lips matched to a sound: a picture of Pitzki + a short sound (his song, or a line) -> a video.
        const email = String(u?.user?.email ?? "").toLowerCase();
        if (s.pitzki_talk_access !== "everyone" && !PRINT_ADMINS.includes(email)) return json({ error: "עוד בבדיקה." }, 403);
        if (!image.startsWith("data:image/") && !image.startsWith("https://")) return json({ error: "תמונה לא תקינה." }, 400);
        const audio = String(body.audio ?? "");
        if (!(audio.startsWith("data:audio/") || audio.startsWith("https://")) || audio.length > 3_000_000) return json({ error: "קול לא תקין." }, 400);
        model = s.pitzki_talk_model;
        input = model.startsWith("minimax/")
          ? { image_url: image, audio_url: audio, resolution: "768P", enable_transcription: false }
          : { image_url: image, audio_url: audio, prompt: (clean(body.scene, 300) + " " + s.pitzki_talk_prompt).trim() };
      } else if (kind === "pitzki_anim") {
        // Pitzki's lesson: a drawn scene (sent by the app) comes to life with lively motion, 10 seconds by default.
        if (!image.startsWith("data:image/") && !image.startsWith("https://")) return json({ error: "תמונה לא תקינה." }, 400);
        model = s.pitzki_anim_model;
        const scene = clean(body.scene, 600);
        const prompt = s.pitzki_motion_prompt.includes("{scene}") ? s.pitzki_motion_prompt.replace("{scene}", scene) : `${s.pitzki_motion_prompt} ${scene}`;
        const cfg = Number(s.anim_cfg);
        const duration = String(body.duration ?? "10") === "5" ? "5" : "10";
        input = {
          prompt: prompt.trim(), image_url: image, duration, negative_prompt: s.negative_prompt,
          ...(Number.isFinite(cfg) ? { cfg_scale: cfg } : {}),
        };
      } else if (kind === "anim") {
        if (!image.startsWith("https://")) return json({ error: "כתובת תמונה לא תקינה." }, 400);
        model = s.anim_model;
        const act = clean(body.motion, 200);
        const prompt = s.motion_prompt.includes("{action}") ? s.motion_prompt.replace("{action}", act) : `${s.motion_prompt} ${act}`;
        const cfg = Number(s.anim_cfg);
        // The approved main scene is animated for 10 seconds, the others for 5 (Kling accepts "5" or "10").
        const duration = String(body.duration ?? "5") === "10" ? "10" : "5";
        input = {
          prompt: prompt.trim(), image_url: image, duration, negative_prompt: s.negative_prompt,
          ...(Number.isFinite(cfg) ? { cfg_scale: cfg } : {}),
        };
      } else return json({ error: "בקשה לא מוכרת." }, 400);
      const r = await fetch("https://queue.fal.run/" + model, {
        method: "POST",
        headers: {
          Authorization: "Key " + FAL_KEY, "Content-Type": "application/json",
          // Results are public links on fal's CDN until they expire: keep them for one hour only (the app downloads
          // them right away, and asks fal to delete the request with "forget").
          "X-Fal-Object-Lifecycle-Preference": JSON.stringify({ expiration_duration_seconds: 3600 }),
        },
        body: JSON.stringify(input),
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) {
        // Say why (the model's own message), so the parent's screenshot tells us what to fix.
        const why = String((j as any)?.detail?.[0]?.msg ?? (j as any)?.detail ?? (j as any)?.error ?? (j as any)?.message ?? "").slice(0, 160);
        console.log(JSON.stringify({ ev: "fal_submit_failed", model, status: r.status, body: JSON.stringify(j).slice(0, 500) }));
        if (outOfCredit(r.status, JSON.stringify(j))) await serviceAlert(admin, "fal", r.status, JSON.stringify(j));
        return json({ error: `שירות הציור לא זמין כרגע (${model}, ${r.status}${why ? ": " + why : ""}).`, detail: JSON.stringify(j).slice(0, 300) }, 502);
      }
      await serviceOk(admin, "fal");
      return json({ status_url: j.status_url, response_url: j.response_url, request_id: j.request_id ?? "" });
    }
    // After the phone saved a result: delete the request's inputs, outputs and output files at fal.
    // (Needs a fal key with the models:requests:write and assets:write permissions; the answer says if it worked.)
    if (action === "forget") {
      const id = String(body.request_id ?? "");
      if (!/^[0-9a-f-]{36}$/i.test(id)) return json({ error: "מזהה לא תקין." }, 400);
      const r = await fetch(`https://api.fal.ai/v1/models/requests/${id}/payloads`, {
        method: "DELETE", headers: { Authorization: "Key " + FAL_KEY, "Idempotency-Key": id },
      });
      const j = await r.json().catch(() => ({}));
      const failed = (j.cdn_delete_results ?? []).filter((x: { exception: unknown }) => x.exception).length;
      return json({ ok: r.ok && failed === 0, status: r.status, detail: r.ok ? "" : JSON.stringify(j).slice(0, 200) });
    }

    if (action === "poll") {
      const su = String(body.status_url ?? "");
      const ru = String(body.response_url ?? "");
      if (!su.startsWith("https://queue.fal.run/") || !ru.startsWith("https://queue.fal.run/")) return json({ error: "כתובת לא תקינה." }, 400);
      const H = { Authorization: "Key " + FAL_KEY };
      const st = await (await fetch(su, { headers: H })).json().catch(() => ({}));
      if (st.status === "COMPLETED") {
        const rr = await fetch(ru, { headers: H });
        const j = await rr.json().catch(() => ({}));
        if (!rr.ok) return json({ status: "FAILED", detail: JSON.stringify(j).slice(0, 300) });
        const url = j.video?.url ?? j.images?.[0]?.url ?? j.image?.url ?? "";
        return json({ status: url ? "DONE" : "FAILED", url, ...(url ? {} : { detail: JSON.stringify(j).slice(0, 300) }) });
      }
      if (st.status === "FAILED" || st.error) return json({ status: "FAILED", detail: JSON.stringify(st.error ?? st).slice(0, 300) });
      return json({ status: "PENDING" });
    }
    return json({ error: "בקשה לא מוכרת." }, 400);
  } catch (e) {
    return json({ error: "שגיאה בשרת.", detail: String(e).slice(0, 200) }, 500);
  }
});
