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

// 쉬는 날(2026-11-07): 주말·공휴일엔 출고 감사·안부·보험·렌트 문자를 쉬고 다음 평일에 나감
// 콜백·테스트·직접 보낸 문자·명절 인사(hol: 새해·설·추석·크리스마스 등)는 주말·공휴일에도 그대로 나감
const FIXED_HOL = new Set(["01-01", "03-01", "05-05", "06-06", "08-15", "10-03", "10-09", "12-25"]);
async function offDay() {
  const k = new Date(Date.now() + 9 * 3600 * 1000);
  const dow = k.getUTCDay();
  if (dow === 0 || dow === 6) return true;
  const ymd = k.toISOString().slice(0, 10);
  if (FIXED_HOL.has(ymd.slice(5))) return true;
  const { data } = await db.from("kr_holidays").select("d").eq("d", ymd).maybeSingle();
  return !!data;
}

// 수신거부 링크 코드 (세컨폰과 같은 규칙, 본폰은 ?m=)
const K = 0x5a3c9e17;
function optCode(phone: string) {
  const v = parseInt(String(phone).replace(/\D/g, ""), 10);
  const x = (v ^ K) >>> 0;
  const chk = ((v % 1296) * 7 + 13) % 1296;
  return x.toString(36) + chk.toString(36).padStart(2, "0");
}
const OPT_URL = "https://lshihh306.github.io/crm-sync/n/?m=";

// 출고 고객 자동 문자는 (광고)·무료수신거부를 붙이지 않음 (2026-10-21 사용자 요청)
// 단, 광고 단체문자(kind=promo, 2026-11-10)는 맨 앞 (광고) + 끝에 고객별 무료수신거부 링크
function adWrap(kind: string, body: string, phone: string) {
  const b = body.replace(/^\s*\(광고\)\s*/, "").replace(/\n*무료수신거부[^\n]*\s*$/, "");
  if (kind === "promo") return `(광고)${b}\n무료수신거부 ${OPT_URL}${optCode(phone)}`;
  return b;
}
// 광고 단체문자는 하루 이만큼만 (KT: 하루 150건 넘는 날이 한 달 10번 넘으면 제한)
const PROMO_PER_DAY = 110;

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
    // 단, 연락처가 ☆·@·₩ 로 저장된 고객이면 분석 기다리지 않고 바로 보냄
    const savedName = String(body.name ?? "").trim();
    const knownCustomer = /^[☆★@₩]/.test(savedName);
    if (type === "after" && !knownCustomer) {
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
      // 통화 후(저장된 고객)는 cb: 키로, 사진 포함
      key: (type === "after" ? "cb:" : "cbm:") + phone, name: savedName.slice(0, 40) || null, phone, kind: "callback", label,
      body: conf.text, send_at: now, status: "sending", claimed_at: now, edited: true,
      media_id: type === "after" ? (conf.image || conf.image2 || null) : null,
      media_id2: type === "after" && conf.image && conf.image2 ? conf.image2 : null,
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
  const night = h < SEND_FROM || h >= SEND_UNTIL;

  // 콜백 문자는 통화에 대한 답이라 밤에도, 자동 발송이 꺼져 있어도 바로 보냄. 테스트 문자도 꺼져 있어도 보냄
  let q = db.from("sms_queue").select("id, key, phone, kind, body, send_at")
    .eq("status", "scheduled").lte("send_at", new Date().toISOString());
  if (night) q = q.eq("kind", "callback");
  else if (!enabled) q = q.in("kind", ["test", "callback"]);
  else if (await offDay()) q = q.in("kind", ["test", "callback", "manual", "hol"]);
  const { data: cand } = await q.order("send_at", { ascending: true }).limit(300);
  if (!cand || !cand.length) return json({ enabled, items: [] });

  // ===== 같은 문자·겹치는 문자 차단 (2026-11-03) =====
  // ① 같은 번호에는 하루(한국 시각 0시 기준) 자동 문자 1통만 → 나머지는 다음 날 자동으로 나감 (보험 > 렌트·리스 > 명절 > 출고 감사 > 안부 > 광고 순)
  // ② 같은 번호로 같은 명절 인사를 이미 보냈으면(현대·BMW 명단에 둘 다 있는 경우 등) 취소
  // ③ 같은 문구를 60일 안에 같은 번호로 이미 보냈으면 취소
  // ④ 본폰 수신거부한 번호는 자동·광고 문자 취소 (2026-11-10)
  // ⑤ 광고 단체문자는 하루 PROMO_PER_DAY 건까지만, 나머지는 다음 평일
  // 콜백·테스트·직접 보낸 문자는 예외
  const EXEMPT = new Set(["callback", "test", "manual"]);
  const PRI: Record<string, number> = { ins: 5, rent: 4, hol: 3, thanks: 2, care: 1, promo: 0 };
  const norm = (s: string) => String(s ?? "").replace(/\s+/g, " ").trim();
  const holOf = (k: string) => (String(k ?? "").match(/:hol:([^#]+)/) ?? [])[1] ?? null;
  const phones = [...new Set(cand.filter((c) => !EXEMPT.has(c.kind)).map((c) => c.phone).filter(Boolean))];
  const kstMidnight = new Date(Math.floor((Date.now() + 9 * 3600e3) / 86400e3) * 86400e3 - 9 * 3600e3).toISOString();
  const busy = new Set<string>(), bodies = new Set<string>(), hols = new Set<string>(), opted = new Set<string>();
  if (phones.length) {
    const { data: hist } = await db.from("sms_queue").select("phone, key, kind, body, sent_at, claimed_at")
      .in("phone", phones).in("status", ["sent", "sending"])
      .gte("updated_at", new Date(Date.now() - 60 * 86400e3).toISOString());
    for (const r of hist ?? []) {
      if (EXEMPT.has(r.kind)) continue;
      const at = r.sent_at ?? r.claimed_at;
      if (at && at >= kstMidnight) busy.add(r.phone);
      bodies.add(r.phone + "\u0000" + norm(r.body));
      const ho = holOf(r.key); if (ho) hols.add(r.phone + "|" + ho);
    }
    const { data: oo } = await db.from("main_sms_optout").select("phone").in("phone", phones);
    for (const o of oo ?? []) opted.add(o.phone);
  }
  let promoLeft = PROMO_PER_DAY;
  if (cand.some((c) => c.kind === "promo")) {
    const { count } = await db.from("sms_queue").select("id", { count: "exact", head: true })
      .eq("kind", "promo").in("status", ["sent", "sending"]).gte("claimed_at", kstMidnight);
    promoLeft = Math.max(0, PROMO_PER_DAY - (count ?? 0));
  }
  const sorted = [...cand].sort((a, b) => {
    const pa = EXEMPT.has(a.kind) ? 99 : (PRI[a.kind] ?? 0), pb = EXEMPT.has(b.kind) ? 99 : (PRI[b.kind] ?? 0);
    return pb - pa || String(a.send_at).localeCompare(String(b.send_at));
  });
  const due: { id: string }[] = [];
  for (const c of sorted) {
    if (due.length >= 30) break;
    if (EXEMPT.has(c.kind)) { due.push(c); continue; }
    const ho = holOf(c.key), bk = c.phone + "\u0000" + norm(c.body);
    let why: string | null = null;
    if (opted.has(c.phone)) why = "본폰 수신거부한 번호";
    else if (ho && hols.has(c.phone + "|" + ho)) why = "같은 번호로 같은 명절 인사를 이미 보냄 (명단 중복)";
    else if (bodies.has(bk)) why = "같은 문구를 최근 60일 안에 이 번호로 이미 보냄";
    if (why) {
      await db.from("sms_queue").update({ status: "canceled", note: why, updated_at: new Date().toISOString() })
        .eq("id", c.id).eq("status", "scheduled");
      continue;
    }
    if (busy.has(c.phone)) continue; // 오늘 이미 이 번호로 다른 자동 문자가 나감 → 다음 날 나감
    if (c.kind === "promo") { if (promoLeft <= 0) continue; promoLeft--; }
    busy.add(c.phone); bodies.add(bk); if (ho) hols.add(c.phone + "|" + ho);
    due.push(c);
  }
  if (!due.length) return json({ enabled, items: [] });

  // 동시에 두 번 가져가지 않도록 scheduled → sending 으로 바꾼 것만 돌려줌
  const { data: claimed } = await db.from("sms_queue")
    .update({ status: "sending", claimed_at: new Date().toISOString(), updated_at: new Date().toISOString() })
    .in("id", due.map((d) => d.id)).eq("status", "scheduled")
    .select("id, phone, body, name, label, kind, media_id, media_id2");
  const media = await mediaMap((claimed ?? []).flatMap((c) => [c.media_id, c.media_id2]));
  const items = (claimed ?? []).map((c) => {
    const images = [c.media_id, c.media_id2].filter((x) => x && media[x]).map((x) => media[x as string]);
    return { ...c, body: adWrap(c.kind, c.body, c.phone), images, image: images[0]?.data ?? null, image_mime: images[0]?.mime ?? null };
  });
  return json({ enabled, items });
});
