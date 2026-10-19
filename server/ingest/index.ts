// ingest: 갤럭시 앱이 보내는 통화녹음 / 카톡·문자 캡처 / 텍스트를 받아
// 1) 파일 저장  2) (녹음이면) 텍스트 변환  3) Claude 분석  4) inbox에 "확인 대기"로 저장
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const APP_TOKEN = Deno.env.get("APP_TOKEN") ?? "";
const ANTHROPIC_KEY = Deno.env.get("ANTHROPIC_API_KEY") ?? "";
const OPENAI_KEY = Deno.env.get("OPENAI_API_KEY") ?? "";
const CLAUDE_MODELS = [Deno.env.get("ANTHROPIC_MODEL") ?? "claude-haiku-5-5", "claude-haiku-4-5"];
const STT_MODELS = [Deno.env.get("OPENAI_STT_MODEL") ?? "gpt-4o-mini-transcribe", "whisper-1"];

const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function kstNow() {
  const d = new Date(Date.now() + 9 * 3600 * 1000);
  return d.toISOString().slice(0, 16).replace("T", " ");
}

// 갤럭시 통화녹음 파일명 예: "통화 녹음 홍길동_261014_101900.m4a", "Call recording 01012345678_261014_101900.m4a"
function parseCallFileName(name: string) {
  const base = name.replace(/\.[^.]+$/, "").replace(/^(통화\s*녹음|통화|Call\s*recording)\s+/i, "");
  const m = base.match(/^(.*)_(\d{6})_(\d{6})$/);
  let who = m ? m[1] : base;
  let at: string | null = null;
  if (m) {
    const [y, mo, d] = [m[2].slice(0, 2), m[2].slice(2, 4), m[2].slice(4, 6)];
    const [h, mi, s] = [m[3].slice(0, 2), m[3].slice(2, 4), m[3].slice(4, 6)];
    at = `20${y}-${mo}-${d}T${h}:${mi}:${s}+09:00`;
  }
  who = who.trim();
  const digits = who.replace(/[^0-9]/g, "");
  const phone = digits.length >= 9 && digits.length === who.replace(/[\s-]/g, "").length ? digits : null;
  return { name: phone ? null : who || null, phone, at };
}

async function transcribe(file: Blob, fileName: string): Promise<string> {
  if (!OPENAI_KEY) throw new Error("OPENAI_API_KEY 비밀값이 없어요");
  let lastErr = "";
  // 한글 파일 이름은 OpenAI가 형식을 못 알아봐서, 영문 이름 + 올바른 형식으로 다시 감싸서 보냄
  const ext = ((fileName.match(/\.([a-z0-9]+)$/i)?.[1]) ?? "m4a").toLowerCase();
  const MIME: Record<string, string> = { m4a: "audio/mp4", mp4: "audio/mp4", "3gp": "audio/mp4", aac: "audio/mp4", mp3: "audio/mpeg", wav: "audio/wav", ogg: "audio/ogg", amr: "audio/amr", webm: "audio/webm", flac: "audio/flac" };
  const safeExt = ["3gp", "aac"].includes(ext) ? "m4a" : ext;
  const audio = new Blob([await file.arrayBuffer()], { type: MIME[ext] ?? "audio/mp4" });
  for (const model of STT_MODELS) {
    const fd = new FormData();
    fd.append("file", audio, `call.${safeExt}`);
    fd.append("model", model);
    fd.append("language", "ko");
    const r = await fetch("https://api.openai.com/v1/audio/transcriptions", {
      method: "POST",
      headers: { Authorization: `Bearer ${OPENAI_KEY}` },
      body: fd,
    });
    if (r.ok) return ((await r.json()).text ?? "").trim();
    lastErr = `${model}: ${r.status} ${(await r.text()).slice(0, 300)}`;
  }
  throw new Error("음성 변환 실패 - " + lastErr);
}

const SYSTEM = `너는 현대자동차 영업사원의 상담 기록 비서다. 통화 녹취, 카카오톡 대화 캡처, 문자 캡처, 메모를 읽고 영업 관리용으로 정리한다.
반드시 아래 JSON 하나만 출력한다. 설명, 코드블록 금지.
{
 "relevant": true|false,            // 아래 "relevant 판단 기준"대로
 "category": "가망"|"출고고객"|"개인일정"|"기타",
 "customer_name": string|null,      // 고객 이름. 모르면 null
 "phone": string|null,              // 숫자만
 "car": string|null,                // 고객이 사려는 관심 차종/트림 (예: "싼타페 HEV 캘리그래피"). 지금 타는 차는 넣지 말 것
 "payment": string|null,            // 할부/리스/렌트/현금 등 언급된 결제 방식
 "referrer": string|null,            // 소개해 준 사람. 연락처 저장명 "@출고(성훈) …"이면 괄호 안 이름, 대화에 "누구 소개"가 나오면 그 사람. 없으면 null
 "inquiry": string,                 // 고객 문의 핵심 한 줄, 40자 이내 (예: "신형 투싼 가솔린으로 교체 희망, 납기 문의")
 "summary": string,                 // 상담 핵심 2~3줄, 구어체 아닌 간결한 메모체
 "details": string[],               // 견적 조건, 옵션, 색상, 경쟁 견적, 중고차 매입, 출고 희망 시기 등 사실만 짧게
 "dialogue": [{"who":"나"|"고객","t":string}], // 대화 전체를 말한 사람별 순서대로 나눈 것. "나"=영업사원, "고객"=상대. 문맥으로 화자를 판단하고, 원래 표현은 살리되 "네 네 네" 같은 군더더기 맞장구만 줄인다. 카톡·문자 캡처면 말풍선 순서대로(오른쪽/노란 말풍선=나).
 "next_action": string,             // 다음에 할 일 한 줄, 15자 내외 (예: "중고차 시세 확인 후 연락")
 "next_date": "YYYY-MM-DD"|null,    // 다음 연락/약속 날짜. 대화에 근거가 있을 때만
 "next_time": "HH:MM"|null,
 "schedule_title": string|null,     // 일정 제목은 "고객이름 차종" 형식만 (예: "홍길동 싼타페")
 "stage": "신규 문의"|"신차 문의"|"상담 중"|"계약"|"보류"|"해약"|null,
 "temperature": "상"|"중"|"하"|null, // 구매 가능성
 "confidence": number                // 0~1, 고객/내용 파악 확신도
}
연락처 저장명 규칙: 저장명이 ☆, @, ₩ 로 시작하면 무조건 고객이다(relevant true 쪽으로 판단). "☆출고 …" = 예전 BMW 근무 시절 출고 고객(현대차 출고 고객 아님, 재구매 가망 고객으로 category 가망, details에 "BMW 출고 고객" 표기), "@출고 모델 성함" = 현대차에서 이미 출고한 고객(category 출고고객), "@출고(성훈) 모델 성함" = 괄호 안 사람이 소개한 출고 고객, "@날짜 모델 성함" = 그 날짜에 문의한 일반 가망 고객. 저장명 안의 성함을 customer_name, 모델을 car 판단에 참고한다.
relevant 판단 기준: 고객(또는 고객이 될 사람)과 차량 구매·견적·시승·계약·출고·보험·사후관리를 이야기한 경우만 true.
다음은 false: 영업소·지점·회사 동료와의 내부 업무 연락, 캐피탈·보험·탁송·용품·정비 등 협력업체 직원과의 업무 통화, 가족·친구·지인과의 사적 대화(차 이야기가 잠깐 나와도 구매 상담이 아니면), 광고·스팸·택배·기타 생활 연락.
규칙: 추측으로 날짜를 만들지 말 것. "내일", "다음 주 화요일" 같은 표현은 기준 시각으로 실제 날짜를 계산한다. 할인 금액 등 숫자는 원문에 있는 그대로만 적는다. relevant가 false면 dialogue는 빈 배열로 둔다.`;

async function analyze(parts: { text?: string; image?: { b64: string; type: string } }, meta: string) {
  if (!ANTHROPIC_KEY) throw new Error("ANTHROPIC_API_KEY 비밀값이 없어요");
  const content: unknown[] = [];
  if (parts.image) content.push({ type: "image", source: { type: "base64", media_type: parts.image.type, data: parts.image.b64 } });
  content.push({ type: "text", text: `기준 시각(한국): ${kstNow()}\n${meta}\n\n${parts.text ? "내용:\n" + parts.text : "위 캡처 이미지의 대화를 읽고 정리해."}` });
  let lastErr = "";
  for (const model of CLAUDE_MODELS) {
    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": ANTHROPIC_KEY, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({ model, max_tokens: 12000, system: SYSTEM, messages: [{ role: "user", content }] }),
    });
    if (!r.ok) {
      lastErr = `${model}: ${r.status} ${(await r.text()).slice(0, 300)}`;
      if (r.status === 404 || r.status === 400) continue;
      throw new Error("Claude 분석 실패 - " + lastErr);
    }
    const out = await r.json();
    const txt = (out.content ?? []).filter((c: { type: string }) => c.type === "text").map((c: { text: string }) => c.text).join("");
    const s = txt.indexOf("{"), e = txt.lastIndexOf("}");
    if (s < 0 || e < 0) throw new Error("Claude 응답 해석 실패: " + txt.slice(0, 200));
    const parsed = JSON.parse(txt.slice(s, e + 1));
    // 이미지에서 읽은 대화 원문은 transcript로 보관
    return { ...parsed, _model: model };
  }
  throw new Error("Claude 분석 실패 - " + lastErr);
}

// "앞으로 제외" 목록: 번호(뒤 8자리) 또는 이름(띄어쓰기 무시, 포함되면 제외)
async function isBlocked(name: string | null | undefined, phone: string | null | undefined) {
  const { data } = await db.from("inbox_block").select("value");
  if (!data || !data.length) return false;
  const ph = String(phone ?? "").replace(/[^0-9]/g, "");
  const nm = String(name ?? "").replace(/\s/g, "");
  return data.some(({ value }) => {
    const v = String(value);
    const vd = v.replace(/[^0-9]/g, "");
    if (vd.length >= 9 && vd.length === v.replace(/[\s-]/g, "").length) return ph.length >= 8 && ph.slice(-8) === vd.slice(-8);
    const vn = v.replace(/\s/g, "");
    return vn.length >= 2 && nm.length >= 2 && nm.includes(vn);
  });
}

function b64(buf: ArrayBuffer) {
  const bytes = new Uint8Array(buf);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

// 콜백 문자(통화 끝난 뒤): 앱이 통화 종료 때 callback_wait에 "대기"로 올려 둠.
// 이 통화 녹음 분석 결과가 고객 통화면 → sms_queue에 올려서 본폰이 보냄, 아니면 건너뜀
async function resolveCallback(inboxId: string, info: { name: string | null; phone: string | null }, customer: boolean, why: string) {
  try {
    const { data: row } = await db.from("inbox").select("occurred_at").eq("id", inboxId).single();
    if (!row?.occurred_at) return;
    const at = new Date(row.occurred_at).getTime();
    const { data: waits } = await db.from("callback_wait").select("*").eq("status", "waiting")
      // 녹음 파일 시각 = 통화 시작. 앱이 통화 시작 시각을 보내지만, 예전 앱은 통화 끝난 시각이라 넉넉히 90분까지 봄
      .gte("call_at", new Date(at - 5 * 60000).toISOString()).lte("call_at", new Date(at + 90 * 60000).toISOString());
    if (!waits || !waits.length) return;
    const ph = (info.phone ?? "").replace(/\D/g, ""), nm = (info.name ?? "").replace(/\s/g, "");
    const near = (x: { call_at: string }) => Math.abs(new Date(x.call_at).getTime() - at);
    const w = (ph.length >= 8 && waits.find((x) => x.phone.slice(-8) === ph.slice(-8)))
      || (nm && waits.find((x) => (x.name ?? "").replace(/\s/g, "") === nm))
      || waits.sort((a, b) => near(a) - near(b))[0];
    if (!w) return;
    const done = (status: string, note: string) =>
      db.from("callback_wait").update({ status, note, inbox_id: inboxId, decided_at: new Date().toISOString() }).eq("id", w.id);
    if (!customer) { await done("skipped", `고객 통화 아님 (${why})`); return; }
    const { data: st } = await db.from("sms_settings").select("callback").eq("id", 1).single();
    const cb = (st?.callback ?? {}) as Record<string, { on?: boolean; text?: string; image?: string; image2?: string } | boolean>;
    const conf = cb.after as { on?: boolean; text?: string; image?: string; image2?: string } | undefined;
    if (cb.on === false || !conf || conf.on === false || !conf.text) { await done("skipped", "통화 후 콜백 꺼짐"); return; }
    const { data: ins } = await db.from("sms_queue").upsert({
      key: "cb:" + w.phone, name: w.name || null, phone: w.phone, kind: "callback", label: "콜백 · 통화 후",
      body: conf.text, send_at: new Date().toISOString(), status: "scheduled", edited: true,
      media_id: conf.image || conf.image2 || null, media_id2: conf.image && conf.image2 ? conf.image2 : null,
    }, { onConflict: "key", ignoreDuplicates: true }).select("id");
    await done(ins && ins.length ? "queued" : "skipped", ins && ins.length ? "고객 통화 → 발송 목록에 올림" : "이미 보낸 번호");
  } catch (_) { /* 콜백 처리 실패는 상담 분석에 영향 주지 않음 */ }
}

async function handle(id: string, kind: string, file: Blob | null, fileName: string, text: string, info: { name: string | null; phone: string | null }, storagePath: string | null = null) {
  try {
    let transcript = text;
    let result;
    const meta = [
      `종류: ${({ call: "통화 녹취", kakao: "카카오톡 캡처", sms: "문자 캡처", text: "메모/공유 텍스트", image: "이미지" } as Record<string, string>)[kind]}`,
      fileName ? `파일명: ${fileName}` : "",
      info.name ? `상대 이름(연락처 저장명): ${info.name}` : "",
      info.phone ? `상대 번호: ${info.phone}` : "",
    ].filter(Boolean).join("\n");

    if (kind === "call") {
      transcript = await transcribe(file!, fileName);
      await db.from("inbox").update({ transcript }).eq("id", id);
      result = await analyze({ text: transcript || "(녹음 내용이 비어 있음)" }, meta);
    } else if (file && (kind === "kakao" || kind === "sms" || kind === "image")) {
      const type = file.type && file.type.startsWith("image/") ? file.type : (fileName.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg");
      result = await analyze({ image: { b64: b64(await file.arrayBuffer()), type } }, meta);
    } else {
      result = await analyze({ text }, meta);
    }
    // 차량 관련이 아니면: 녹음·캡처 파일과 내용을 바로 지우고 기록만 '정리됨'으로 남김 (사적 대화 보관 안 함)
    if (result && result.relevant !== false && (await isBlocked(info.name, info.phone) || await isBlocked(result.customer_name, result.phone))) result.relevant = false;
    if (kind === "call") await resolveCallback(id, info, !!result && result.relevant !== false && result.category !== "개인일정", result?.category ?? "기타");
    if (result && result.relevant === false) {
      if (storagePath) await db.storage.from("uploads").remove([storagePath]);
      await db.from("inbox").update({
        analysis: { relevant: false, category: result.category ?? "기타", _model: result._model },
        transcript: null, phone: null, contact_name: null, storage_path: null,
        status: "dismissed", processed_at: new Date().toISOString(), handled_at: new Date().toISOString(), error: null,
      }).eq("id", id);
      return;
    }
    await db.from("inbox").update({
      analysis: result,
      transcript,
      contact_name: info.name ?? result.customer_name ?? null,
      phone: info.phone ?? result.phone ?? null,
      status: "pending",
      processed_at: new Date().toISOString(),
      error: null,
    }).eq("id", id);
  } catch (err) {
    await db.from("inbox").update({ status: "error", error: String((err as Error)?.message ?? err) }).eq("id", id);
  }
}

Deno.serve(async (req) => {
  if (req.method === "GET") return json({ ok: true, service: "lsh-crm ingest" });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  if (!APP_TOKEN || req.headers.get("x-app-token") !== APP_TOKEN) return json({ error: "unauthorized" }, 401);

  const url = new URL(req.url);
  // 고객 삭제 시: 그 고객으로 저장된 상담 기록과 녹음·캡처 파일을 모두 지움 (POST ?purge=p:<고객 id>)
  const purge = url.searchParams.get("purge");
  if (purge) {
    const { data: rows } = await db.from("inbox").select("id, storage_path").eq("saved_target", purge);
    const paths = (rows ?? []).map((r) => r.storage_path).filter(Boolean) as string[];
    if (paths.length) await db.storage.from("uploads").remove(paths);
    if (rows && rows.length) await db.from("inbox").delete().in("id", rows.map((r) => r.id));
    return json({ ok: true, purged: rows?.length ?? 0, files: paths.length });
  }
  // 재처리: POST ?retry=<id>
  // 상담 입력함에서 "무시/지우기" 하면 녹음·캡처 파일과 원문은 지우고 기록(중복 방지용)만 남김 (POST ?discard=<id>)
  const discard = url.searchParams.get("discard");
  if (discard) {
    const { data: row } = await db.from("inbox").select("storage_path").eq("id", discard).eq("status", "dismissed").maybeSingle();
    if (row?.storage_path) await db.storage.from("uploads").remove([row.storage_path]);
    await db.from("inbox").update({ storage_path: null, transcript: null }).eq("id", discard).eq("status", "dismissed");
    return json({ ok: true, discarded: discard });
  }
  const retry = url.searchParams.get("retry");
  if (retry) {
    const { data: row } = await db.from("inbox").select("*").eq("id", retry).single();
    if (!row) return json({ error: "not found" }, 404);
    let file: Blob | null = null;
    if (row.storage_path) {
      const { data } = await db.storage.from("uploads").download(row.storage_path);
      file = data;
    }
    await db.from("inbox").update({ status: "processing", error: null }).eq("id", retry);
    EdgeRuntime.waitUntil(handle(retry, row.kind, file, row.file_name ?? "", row.kind === "call" ? "" : (row.transcript ?? ""), { name: row.contact_name, phone: row.phone }, row.storage_path));
    return json({ ok: true, id: retry, retried: true });
  }

  const form = await req.formData();
  const kind = String(form.get("kind") ?? "");
  if (!["call", "kakao", "sms", "text", "image"].includes(kind)) return json({ error: "kind 값이 잘못됐어요" }, 400);
  const file = form.get("file") as File | null;
  const text = String(form.get("text") ?? "");
  const fileName = String(form.get("file_name") ?? file?.name ?? "");
  if (!file && !text) return json({ error: "file 또는 text가 필요해요" }, 400);

  let info = { name: (form.get("contact_name") as string) || null, phone: ((form.get("phone") as string) || "").replace(/[^0-9]/g, "") || null };
  let occurredAt = (form.get("occurred_at") as string) || null;
  if (kind === "call" && fileName) {
    const p = parseCallFileName(fileName);
    info = { name: info.name ?? p.name, phone: info.phone ?? p.phone };
    occurredAt = occurredAt ?? p.at;
  }

  // 같은 파일 중복 전송 방지
  if (fileName) {
    const { data: dup } = await db.from("inbox").select("id, status, storage_path, error").eq("file_name", fileName).limit(1);
    if (dup && dup.length) {
      // 예전에 실패한 건 지우고 새로 받음 (앱 업데이트 후 다시 보내기)
      if (dup[0].status === "error" || dup[0].error) {
        if (dup[0].storage_path) await db.storage.from("uploads").remove([dup[0].storage_path]);
        await db.from("inbox").delete().eq("id", dup[0].id);
      } else return json({ ok: true, id: dup[0].id, duplicate: true });
    }
  }

  // 제외 목록에 있는 사람이면 파일을 저장하지도, 분석하지도 않음 (중복 방지용 기록만)
  if (await isBlocked(info.name, info.phone)) {
    await db.from("inbox").insert({ kind, file_name: fileName || null, occurred_at: occurredAt, status: "dismissed",
      analysis: { relevant: false, category: "제외" }, processed_at: new Date().toISOString(), handled_at: new Date().toISOString() });
    return json({ ok: true, blocked: true });
  }

  let storagePath: string | null = null;
  if (file) {
    const ext = (fileName.match(/\.[a-z0-9]+$/i)?.[0] ?? "").toLowerCase();
    storagePath = `${kind}/${new Date().toISOString().slice(0, 10)}/${crypto.randomUUID()}${ext}`;
    const { error } = await db.storage.from("uploads").upload(storagePath, file, { contentType: file.type || undefined });
    if (error) return json({ error: "파일 저장 실패: " + error.message }, 500);
  }

  const { data: row, error } = await db.from("inbox").insert({
    kind, file_name: fileName || null, storage_path: storagePath, occurred_at: occurredAt,
    phone: info.phone, contact_name: info.name, transcript: text || null, status: "processing",
  }).select("id").single();
  if (error) return json({ error: error.message }, 500);

  EdgeRuntime.waitUntil(handle(row.id, kind, file, fileName, text, info, storagePath));
  return json({ ok: true, id: row.id });
});
