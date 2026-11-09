// cal: 갤럭시 '관리 캘린더' 앱이 쓰는 서버 함수
// - 일정 원본은 관리 시스템(아티팩트 저장소). 관리 시스템이 열릴 때마다 cal_items 에 달력용 복사본을 올려 둠
// - 앱에서 추가·수정·삭제·완료하면: cal_items 를 바로 고치고(앱·위젯에 즉시 반영) cal_ops 에 기록
//   → 관리 시스템이 다음에 열릴 때 cal_ops 를 원본에 반영
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json; charset=utf-8" } });

// 관리 시스템 달력과 같은 색 (bg, fg)
const COLORS: Record<string, [string, string]> = {
  normal: ["#4a6cd4", "#ffffff"],
  meet: ["#d63a3a", "#ffffff"],
  call: ["#cfe6fb", "#0b4a7a"],
  duty: ["#6a3fd1", "#ffffff"],
  rent: ["#b7791f", "#ffffff"],
};
function evCat(cat: string, title: string) {
  if (cat && cat !== "auto") return cat;
  if (/월세/.test(title)) return "rent";
  if (/당직/.test(title)) return "duty";
  if (/출고|미팅/.test(title)) return "meet";
  if (/연락|전화|통화|문의|견적|상담|카톡|문자|콜/.test(title)) return "call";
  return "normal";
}
const isDate = (s: unknown) => typeof s === "string" && /^\d{4}-\d{2}-\d{2}$/.test(s);
const isTime = (s: unknown) => typeof s === "string" && (s === "" || /^\d{2}:\d{2}$/.test(s));
const str = (v: unknown, n = 300) => String(v ?? "").slice(0, n);
const rid = () => "e" + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);

function eventRow(id: string, p: Record<string, unknown>, done = false) {
  const cat = evCat(str(p.cat, 20), str(p.title));
  const [bg, fg] = COLORS[cat] ?? COLORS.normal;
  return {
    id: `e:${id}:${p.date}`, date: p.date as string, time: str(p.time, 5), title: str(p.title), memo: str(p.memo, 2000),
    kind: "event", cat, bg, fg, done, late: false, phone: str(p.phone, 30), ref: `e:${id}`, repeat: str(p.repeat, 10),
    sort: 0, updated_at: new Date().toISOString(),
  };
}

Deno.serve(async (req) => {
  const { data: st } = await db.from("cal_settings").select("token, synced_at").eq("id", 1).single();
  const tok = req.headers.get("x-cal-token") ?? "";
  if (!st?.token || tok !== st.token) return json({ error: "unauthorized" }, 401);
  const body = await req.json().catch(() => ({} as Record<string, unknown>));
  const action = str(body.action ?? "list", 20);
  await db.from("cal_settings").update({ app_seen_at: new Date().toISOString(), app_info: str(body.app, 80) || null }).eq("id", 1);

  const op = async (name: string, ref: string, payload: Record<string, unknown>) => {
    const { error } = await db.from("cal_ops").insert({ op: name, ref, payload });
    if (error) throw error;
  };

  try {
    if (action === "list") {
      const from = isDate(body.from) ? body.from as string : null, to = isDate(body.to) ? body.to as string : null;
      let q = db.from("cal_items").select("id,date,time,title,memo,kind,cat,bg,fg,done,late,phone,ref,repeat,sort").order("date").order("sort");
      if (from) q = q.gte("date", from);
      if (to) q = q.lte("date", to);
      const { data, error } = await q.limit(5000);
      if (error) throw error;
      const { count } = await db.from("cal_ops").select("id", { count: "exact", head: true }).eq("status", "pending");
      return json({ items: data ?? [], synced_at: st.synced_at, pending: count ?? 0 });
    }

    // 개인 일정 추가
    if (action === "add") {
      if (!isDate(body.date) || !str(body.title).trim() || !isTime(body.time ?? "")) return json({ error: "날짜와 내용을 확인해 주세요" }, 400);
      const id = rid();
      const p = { id, date: body.date, time: str(body.time ?? "", 5), title: str(body.title).trim(), memo: str(body.memo, 2000), cat: str(body.cat || "auto", 20), repeat: body.repeat === "monthly" ? "monthly" : "" };
      await op("add", `e:${id}`, p);
      await db.from("cal_items").upsert(eventRow(id, p));
      return json({ ok: true, ref: `e:${id}` });
    }

    const ref = str(body.ref, 80);
    const [kind, id] = [ref.slice(0, 1), ref.slice(2)];
    if (!id) return json({ error: "ref 없음" }, 400);

    if (action === "update" && kind === "e") {
      if (!isDate(body.date) || !str(body.title).trim() || !isTime(body.time ?? "")) return json({ error: "날짜와 내용을 확인해 주세요" }, 400);
      const p = { id, date: body.date, time: str(body.time ?? "", 5), title: str(body.title).trim(), memo: str(body.memo, 2000), cat: str(body.cat || "auto", 20), repeat: body.repeat === "monthly" ? "monthly" : "" };
      await op("update", ref, p);
      const { data: old } = await db.from("cal_items").select("done, phone").eq("ref", ref).limit(1);
      await db.from("cal_items").delete().eq("ref", ref);
      await db.from("cal_items").upsert({ ...eventRow(id, p, !!old?.[0]?.done), phone: old?.[0]?.phone ?? "" });
      return json({ ok: true });
    }

    if (action === "delete" && kind === "e") {
      await op("delete", ref, { id });
      await db.from("cal_items").delete().eq("ref", ref);
      return json({ ok: true });
    }

    if (action === "done" && kind === "e") {
      if (!isDate(body.date)) return json({ error: "날짜 없음" }, 400);
      const done = !!body.done;
      await op("done", ref, { id, date: body.date, done });
      // 매월 반복이 아니면 모든 날짜 줄이 같이 완료
      const { data: rows } = await db.from("cal_items").select("id, repeat").eq("ref", ref);
      const monthly = rows?.some((r) => r.repeat === "monthly");
      if (monthly) await db.from("cal_items").update({ done }).eq("id", `e:${id}:${body.date}`);
      else await db.from("cal_items").update({ done }).eq("ref", ref);
      return json({ ok: true });
    }

    // 가망 고객 연락 완료 (+ 다음 연락일)
    if (action === "pdone" && kind === "p") {
      if (!isDate(body.date)) return json({ error: "날짜 없음" }, 400);
      const next = isDate(body.next) ? body.next as string : "";
      const p = { id, date: body.date, text: str(body.text, 500).trim(), next, action: str(body.nextAction, 200).trim() };
      await op("pdone", ref, p);
      const { data: rows } = await db.from("cal_items").select("*").eq("ref", ref).eq("kind", "todo");
      const cur = rows?.[0];
      if (cur) {
        await db.from("cal_items").delete().eq("ref", ref).eq("kind", "todo");
        const today = new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10);
        await db.from("cal_items").upsert({ ...cur, id: `p:${id}:${today}`, date: today, kind: "done", done: true, late: false, bg: "#e7e9ee", fg: "#6b7280", memo: p.text || "연락함", updated_at: new Date().toISOString() });
        if (next) await db.from("cal_items").upsert({ ...cur, id: `p:${id}:${next}`, date: next, kind: "todo", done: false, late: false, memo: p.action || cur.memo, updated_at: new Date().toISOString() });
      }
      return json({ ok: true });
    }

    // 가망 고객 연락일 옮기기
    if (action === "pmove" && kind === "p") {
      if (!isDate(body.date)) return json({ error: "날짜 없음" }, 400);
      await op("pmove", ref, { id, date: body.date });
      const { data: rows } = await db.from("cal_items").select("*").eq("ref", ref).eq("kind", "todo");
      const cur = rows?.[0];
      if (cur) {
        await db.from("cal_items").delete().eq("id", cur.id);
        await db.from("cal_items").upsert({ ...cur, id: `p:${id}:${body.date}`, date: body.date, late: false, updated_at: new Date().toISOString() });
      }
      return json({ ok: true });
    }

    return json({ error: "모르는 요청" }, 400);
  } catch (e) {
    return json({ error: String((e as { message?: string })?.message ?? e) }, 500);
  }
});
