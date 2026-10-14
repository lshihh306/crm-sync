# 관리 시스템 상담 자동전송 (개인용)

갤럭시 폰의 통화녹음, 카톡·문자 캡처를 서버로 보내고, Claude가 분석해서 관리 시스템에 "확인 대기"로 올려주는 구성.

- `android/` — 갤럭시 앱 "상담 자동전송" (Kotlin)
- `server/ingest/` — Supabase Edge Function (받기 → 음성 변환 → Claude 분석 → inbox 저장)
- `.github/workflows/build-apk.yml` — push 하면 APK 자동 빌드 (Actions 탭 → 최신 실행 → crmsync-apk)

서버 비밀값: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `APP_TOKEN` (Supabase 대시보드 → Edge Functions → Secrets)
