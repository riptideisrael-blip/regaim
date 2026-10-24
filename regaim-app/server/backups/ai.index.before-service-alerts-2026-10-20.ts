// Supabase Edge Function: ai
// One gate for all of Moments' AI. The app never holds an AI key: this function checks the account, keeps a
// monthly budget per family (table ai_usage, see ai-gate.sql), and forwards the request to Claude or Gemini.
// Nothing sent here is stored: no pictures, no questions, no answers. Only a call counter per family per month.
//
// Secrets (Supabase → Edge Functions → Secrets):
//   ANTHROPIC_API_KEY  – required (descriptions, questions, stories)
//   GEMINI_API_KEY     – optional (comic drawings)
//   AI_MONTHLY_LIMIT   – optional, requests per family per month (default 300)
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const ANTHROPIC = Deno.env.get("ANTHROPIC_API_KEY") ?? "";
const GEMINI = Deno.env.get("GEMINI_API_KEY") ?? "";
const LIMIT = Number(Deno.env.get("AI_MONTHLY_LIMIT") ?? "300") || 300;
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
const SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const CLAUDE_MODELS = new Set(["claude-sonnet-5-5", "claude-haiku-4-5-20251001"]);
const GEMINI_MODELS = new Set(["gemini-3.1-flash-image"]);
const MAX_TOKENS = 4000;
const MAX_IMAGES = 8; // 3 for a description, 8 for "find the exact moment"
const MAX_BYTES = 8 * 1024 * 1024;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};
const json = (o: unknown, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { ...cors, "Content-Type": "application/json" } });

// deno-lint-ignore no-explicit-any
function countClaudeImages(messages: any[]): number {
  let n = 0;
  for (const m of messages ?? []) {
    if (Array.isArray(m?.content)) for (const b of m.content) if (b?.type === "image") n++;
  }
  return n;
}

// deno-lint-ignore no-explicit-any
function countGeminiImages(body: any): number {
  let n = 0;
  for (const c of body?.contents ?? []) for (const p of c?.parts ?? []) if (p?.inline_data || p?.inlineData) n++;
  return n;
}

// Never wait forever on one step: a stuck step answers with a clear error instead of the platform's 150-second cut,
// and every step is written to the Logs tab with its time (to see where time goes).
class StepTimeout extends Error {}
function within<T>(p: PromiseLike<T>, ms: number, label: string): Promise<T> {
  let t: number | undefined;
  return Promise.race([
    Promise.resolve(p).finally(() => clearTimeout(t)),
    new Promise<T>((_, rej) => { t = setTimeout(() => rej(new StepTimeout(label)), ms); }),
  ]);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  const T0 = Date.now();
  const step = (s: string, extra: Record<string, unknown> = {}) => console.log(JSON.stringify({ ev: "step", s, ms: Date.now() - T0, ...extra }));
  try {
    const raw = await req.text();
    step("received", { bytes: raw.length });
    if (raw.length > MAX_BYTES) return json({ error: "too large" }, 413);
    const auth = req.headers.get("Authorization") ?? "";
    const userClient = createClient(SUPABASE_URL, ANON, { global: { headers: { Authorization: auth } } });
    const { data: u } = await within<any>(userClient.auth.getUser(), 15_000, "auth");
    step("auth");
    const uid = u?.user?.id;
    if (!uid) return json({ error: "sign in" }, 401);
    // Guest sign-ins can't spend the family AI budget (otherwise anyone could script free AI on our key).
    if ((u?.user as { is_anonymous?: boolean })?.is_anonymous) return json({ error: "email or Google account needed" }, 403);
    const admin = createClient(SUPABASE_URL, SERVICE);
    const body = JSON.parse(raw || "{}");
    const action = String(body.action ?? "");

    if (action === "usage") {
      const { data } = await admin.rpc("ai_left", { p_user: uid, p_limit: LIMIT });
      return json({ left: Math.max(0, Number(data ?? LIMIT)), limit: LIMIT, drawing: GEMINI.length > 0 });
    }

    if (action === "claude") {
      if (!ANTHROPIC) return json({ error: "not configured" }, 503);
      const b = body.body ?? {};
      const model = String(b.model ?? "");
      if (!CLAUDE_MODELS.has(model)) return json({ error: "model not allowed" }, 400);
      const messages = Array.isArray(b.messages) ? b.messages : [];
      const images = countClaudeImages(messages);
      if (images > MAX_IMAGES) return json({ error: "too many pictures" }, 400);
      const { data: used } = await within<any>(admin.rpc("ai_use", { p_user: uid, p_images: images, p_limit: LIMIT }), 15_000, "budget");
      step("budget", { used });
      if (Number(used ?? -1) < 0) return json({ error: "budget" }, 429);
      const request: Record<string, unknown> = {
        model,
        max_tokens: Math.min(Number(b.max_tokens ?? 500) || 500, MAX_TOKENS),
        messages,
      };
      if (typeof b.system === "string") request.system = b.system;
      const t0 = Date.now();
      const r = await fetch("https://api.anthropic.com/v1/messages", {
        method: "POST",
        headers: { "content-type": "application/json", "x-api-key": ANTHROPIC, "anthropic-version": "2023-06-01" },
        body: JSON.stringify(request),
      });
      console.log(JSON.stringify({ ev: "claude", purpose: String(body.purpose ?? ""), model, status: r.status, ms: Date.now() - t0 }));
      return new Response(await r.text(), { status: r.status, headers: { ...cors, "Content-Type": "application/json" } });
    }

    // Long answers (a whole book's story): Claude's answer is passed on piece by piece while it is written, so the
    // phone gets a first byte at once (no 150-second "no answer yet" cut) and a mobile network never sees a quiet line.
    if (action === "claude_stream") {
      if (!ANTHROPIC) return json({ error: "not configured" }, 503);
      const b = body.body ?? {};
      const model = String(b.model ?? "");
      if (!CLAUDE_MODELS.has(model)) return json({ error: "model not allowed" }, 400);
      const messages = Array.isArray(b.messages) ? b.messages : [];
      const images = countClaudeImages(messages);
      if (images > MAX_IMAGES) return json({ error: "too many pictures" }, 400);
      const { data: used } = await within<any>(admin.rpc("ai_use", { p_user: uid, p_images: images, p_limit: LIMIT }), 15_000, "budget");
      step("budget", { used });
      if (Number(used ?? -1) < 0) return json({ error: "budget" }, 429);
      const request: Record<string, unknown> = {
        model,
        max_tokens: Math.min(Number(b.max_tokens ?? 500) || 500, MAX_TOKENS),
        messages,
        stream: true,
      };
      if (typeof b.system === "string") request.system = b.system;
      const t0 = Date.now();
      const ctl = new AbortController();
      const startLimit = setTimeout(() => ctl.abort(), 100_000);
      step("anthropic_call", { model });
      let r: Response;
      try {
        r = await fetch("https://api.anthropic.com/v1/messages", {
          method: "POST",
          headers: { "content-type": "application/json", "x-api-key": ANTHROPIC, "anthropic-version": "2023-06-01" },
          body: JSON.stringify(request),
          signal: ctl.signal,
        });
      } catch (e) {
        step("anthropic_no_answer", { error: String((e as Error)?.message ?? e).slice(0, 120) });
        return json({ error: "Claude didn't start answering in time" }, 504);
      } finally {
        clearTimeout(startLimit);
      }
      console.log(JSON.stringify({ ev: "claude_stream_start", purpose: String(body.purpose ?? ""), model, status: r.status, ms: Date.now() - t0 }));
      if (!r.ok || !r.body) return new Response(await r.text(), { status: r.status, headers: { ...cors, "Content-Type": "application/json" } });
      const timing = new TransformStream({
        flush() { console.log(JSON.stringify({ ev: "claude_stream_end", purpose: String(body.purpose ?? ""), model, ms: Date.now() - t0 })); },
      });
      return new Response(r.body.pipeThrough(timing), { status: 200, headers: { ...cors, "Content-Type": "text/event-stream", "Cache-Control": "no-cache" } });
    }

    if (action === "gemini") {
      if (!GEMINI) return json({ error: "not configured" }, 503);
      const model = String(body.model ?? "");
      const version = body.version === "v1" ? "v1" : "v1beta";
      if (!GEMINI_MODELS.has(model)) return json({ error: "model not allowed" }, 400);
      const images = countGeminiImages(body.body);
      if (images > 4) return json({ error: "too many pictures" }, 400);
      const { data: used } = await within<any>(admin.rpc("ai_use", { p_user: uid, p_images: images, p_limit: LIMIT }), 15_000, "budget");
      step("budget", { used });
      if (Number(used ?? -1) < 0) return json({ error: "budget" }, 429);
      const r = await fetch(`https://generativelanguage.googleapis.com/${version}/models/${model}:generateContent`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-goog-api-key": GEMINI },
        body: JSON.stringify(body.body ?? {}),
      });
      return new Response(await r.text(), { status: r.status, headers: { ...cors, "Content-Type": "application/json" } });
    }

    return json({ error: "unknown action" }, 400);
  } catch (e) {
    step("error", { error: String((e as Error)?.message ?? e).slice(0, 200) });
    if (e instanceof StepTimeout) return json({ error: "server step too slow: " + e.message }, 503);
    return json({ error: String((e as Error)?.message ?? e).slice(0, 200) }, 500);
  }
});
