// sms: 본폰 앱(상담 자동전송)이 자동 문자 발송 목록을 가져가고(due), 보낸 결과를 알려주는(report) 함수
// 문자는 서버가 아니라 폰 유심으로 나감 → 본번호로 발송, 발송 업체 필요 없음
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});
const APP_TOKEN = Deno.env.get("APP_TOKEN") ?? "";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

// 밤에 문자가 몰려가지 않도록: 한국 시각 9시~20시 59분에만 발송
const kstHour = () => new Date(Date.now() + 9 * 3600 * 1000).getUTCHours();
const SEND_FROM = 9, SEND_UNTIL = 21;

Deno.serve(async (req) => {
  if (!APP_TOKEN || req.headers.get("x-app-token") !== APP_TOKEN) return json({ error: "unauthorized" }, 401);
  const body = await req.json().catch(() => ({} as Record<string, unknown>));
  const action = String(body.action ?? "due");

  // 앱이 살아 있다는 표시 (관리 시스템에서 "폰 연결" 상태로 보여줌)
  await db.from("sms_settings").update({ app_seen_at: new Date().toISOString(), app_info: String(body.app ?? "").slice(0, 80) || null }).eq("id", 1);

  if (action === "ping") return json({ ok: true });

  if (action === "report") {
    const results = Array.isArray(body.results) ? body.results : [];
    for (const r of results as { id: string; ok: boolean; error?: string }[]) {
      if (!r || !r.id) continue;
      await db.from("sms_queue").update(
        r.ok
          ? { status: "sent", sent_at: new Date().toISOString(), error: null, updated_at: new Date().toISOString() }
          : { status: "failed", error: String(r.error ?? "발송 실패").slice(0, 300), updated_at: new Date().toISOString() },
      ).eq("id", r.id).eq("status", "sending");
    }
    return json({ ok: true, count: results.length });
  }

  // action === "due": 지금 보낼 문자를 가져감
  const { data: set } = await db.from("sms_settings").select("enabled").eq("id", 1).single();

  // 폰이 가져간 뒤 30분 넘게 결과가 안 온 건 → 두 번 보내지 않도록 "실패(확인 필요)"로
  await db.from("sms_queue").update({
    status: "failed", error: "폰에서 결과를 받지 못했어요. 문자 앱에서 실제로 나갔는지 확인해 주세요", updated_at: new Date().toISOString(),
  }).eq("status", "sending").lt("claimed_at", new Date(Date.now() - 30 * 60000).toISOString());

  const enabled = !!set?.enabled;
  const h = kstHour();
  if (h < SEND_FROM || h >= SEND_UNTIL) return json({ enabled, night: true, items: [] });

  // 자동 발송이 꺼져 있어도 "내 폰으로 테스트" 문자는 보냄
  let q = db.from("sms_queue").select("id")
    .eq("status", "scheduled").lte("send_at", new Date().toISOString());
  if (!enabled) q = q.eq("kind", "test");
  const { data: due } = await q.order("send_at", { ascending: true }).limit(30);
  if (!due || !due.length) return json({ enabled, items: [] });

  // 동시에 두 번 가져가지 않도록 scheduled → sending 으로 바꾼 것만 돌려줌
  const { data: claimed } = await db.from("sms_queue")
    .update({ status: "sending", claimed_at: new Date().toISOString(), updated_at: new Date().toISOString() })
    .in("id", due.map((d) => d.id)).eq("status", "scheduled")
    .select("id, phone, body, name, label");
  return json({ enabled, items: claimed ?? [] });
});
