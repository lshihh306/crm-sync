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

// 사진 문자: 사진 id들 → {id: {mime, data(base64)}}
async function mediaMap(ids: (string | null | undefined)[]) {
  const want = [...new Set(ids.filter((x): x is string => !!x))];
  const out: Record<string, { mime: string; data: string }> = {};
  if (!want.length) return out;
  const { data } = await db.from("sms_media").select("id, mime, data").in("id", want);
  for (const m of data ?? []) out[m.id] = { mime: m.mime, data: m.data };
  return out;
}

Deno.serve(async (req) => {
  if (!APP_TOKEN || req.headers.get("x-app-token") !== APP_TOKEN) return json({ error: "unauthorized" }, 401);
  const body = await req.json().catch(() => ({} as Record<string, unknown>));
  const action = String(body.action ?? "due");

  // 앱이 살아 있다는 표시 (관리 시스템에서 "폰 연결" 상태로 보여줌)
  await db.from("sms_settings").update({ app_seen_at: new Date().toISOString(), app_info: String(body.app ?? "").slice(0, 80) || null }).eq("id", 1);

  if (action === "ping") return json({ ok: true });

  // 콜백 문자: 통화 끝/부재중/거절 직후 앱이 물어봄 → 이 번호에 처음이면 문구를 돌려주고 "보내는 중"으로 기록
  // 번호당 평생 한 번만 (key = cb:번호, 이미 있으면 안 보냄)
  if (action === "callback") {
    const type = String(body.type ?? "");
    const phone = String(body.phone ?? "").replace(/\D/g, "");
    if (!/^01\d{8,9}$/.test(phone)) return json({ send: false, reason: "휴대폰 번호 아님" });
    const { data: st } = await db.from("sms_settings").select("callback").eq("id", 1).single();
    type Conf = { on?: boolean; text?: string; image?: string; image2?: string };
    const cb = (st?.callback ?? {}) as Record<string, Conf | boolean>;
    const conf = cb[type] as Conf | undefined;
    if (cb.on === false || !conf || conf.on === false || !conf.text) return json({ send: false, reason: "꺼짐" });
    // 통화 끝난 뒤: 바로 보내지 않고 "대기"로 올려 둠 → 녹음 분석에서 고객 통화로 판단되면 ingest가 발송 목록에 올림
    if (type === "after") {
      const { data: done } = await db.from("sms_queue").select("id").eq("key", "cb:" + phone).limit(1);
      if (done && done.length) return json({ send: false, reason: "이미 보낸 번호" });
      const callAt = Number(body.at) > 0 ? new Date(Number(body.at)).toISOString() : new Date().toISOString();
      await db.from("callback_wait").insert({ phone, name: String(body.name ?? "").slice(0, 40) || null, call_at: callAt });
      return json({ send: false, waiting: true, reason: "통화 내용 확인 후 고객이면 발송" });
    }
    const labels: Record<string, string> = { after: "콜백 · 통화 후", missed: "콜백 · 부재중", rejected: "콜백 · 거절" };
    const label = labels[type] ?? "콜백";
    const now = new Date().toISOString();
    const { data: ins, error } = await db.from("sms_queue").upsert({
      // 부재중·거절은 모든 번호에 번호당 한 번 (통화 후 콜백과는 따로 셈), 글자 문자
      key: "cbm:" + phone, name: String(body.name ?? "").slice(0, 40) || null, phone, kind: "callback", label,
      body: conf.text, send_at: now, status: "sending", claimed_at: now, edited: true,
    }, { onConflict: "key", ignoreDuplicates: true }).select("id, body, media_id, media_id2");
    if (error) return json({ send: false, reason: error.message }, 500);
    if (!ins || !ins.length) return json({ send: false, reason: "이미 보낸 번호" });
    const media = await mediaMap([ins[0].media_id, ins[0].media_id2]);
    const images = [ins[0].media_id, ins[0].media_id2].filter((x) => x && media[x]).map((x) => media[x as string]);
    return json({ send: true, id: ins[0].id, body: ins[0].body, images, image: images[0]?.data ?? null, image_mime: images[0]?.mime ?? null });
  }

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
  // 3시간 넘게 녹음 분석 결과가 없는 통화 후 콜백 대기는 정리 (녹음이 없던 통화 등)
  await db.from("callback_wait").update({ status: "expired", note: "통화 녹음 분석 결과가 없어 보내지 않음", decided_at: new Date().toISOString() })
    .eq("status", "waiting").lt("call_at", new Date(Date.now() - 3 * 3600000).toISOString());
  const { data: set } = await db.from("sms_settings").select("enabled").eq("id", 1).single();

  // 폰이 가져간 뒤 30분 넘게 결과가 안 온 건 → 두 번 보내지 않도록 "실패(확인 필요)"로
  await db.from("sms_queue").update({
    status: "failed", error: "폰에서 결과를 받지 못했어요. 문자 앱에서 실제로 나갔는지 확인해 주세요", updated_at: new Date().toISOString(),
  }).eq("status", "sending").lt("claimed_at", new Date(Date.now() - 30 * 60000).toISOString());

  const enabled = !!set?.enabled;
  const h = kstHour();
  if (h < SEND_FROM || h >= SEND_UNTIL) return json({ enabled, night: true, items: [] });

  // 자동 발송이 꺼져 있어도 "내 폰으로 테스트"와 콜백 문자 다시 보내기는 보냄
  let q = db.from("sms_queue").select("id")
    .eq("status", "scheduled").lte("send_at", new Date().toISOString());
  if (!enabled) q = q.in("kind", ["test", "callback"]);
  const { data: due } = await q.order("send_at", { ascending: true }).limit(30);
  if (!due || !due.length) return json({ enabled, items: [] });

  // 동시에 두 번 가져가지 않도록 scheduled → sending 으로 바꾼 것만 돌려줌
  const { data: claimed } = await db.from("sms_queue")
    .update({ status: "sending", claimed_at: new Date().toISOString(), updated_at: new Date().toISOString() })
    .in("id", due.map((d) => d.id)).eq("status", "scheduled")
    .select("id, phone, body, name, label, media_id, media_id2");
  const media = await mediaMap((claimed ?? []).flatMap((c) => [c.media_id, c.media_id2]));
  const items = (claimed ?? []).map((c) => {
    const images = [c.media_id, c.media_id2].filter((x) => x && media[x]).map((x) => media[x as string]);
    return { ...c, images, image: images[0]?.data ?? null, image_mime: images[0]?.mime ?? null };
  });
  return json({ enabled, items });
});
