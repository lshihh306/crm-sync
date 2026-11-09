# 관리 시스템 상담 자동전송 (개인용)

갤럭시 폰의 통화녹음, 카톡·문자 캡처를 서버로 보내고, Claude가 분석해서 관리 시스템에 "확인 대기"로 올려주는 구성.

- `android/` — 갤럭시 앱 "상담 자동전송" (Kotlin)
- `server/ingest/` — Supabase Edge Function (받기 → 음성 변환 → Claude 분석 → inbox 저장)
- `server/sms/` — 자동 문자 Edge Function. 관리 시스템이 `sms_queue`에 올린 안부·감사·보험 문자를 앱이 가져가(due) 본폰 유심으로 보내고 결과를 알려줌(report). 켜기/끄기는 `sms_settings.enabled`
- `android/cal/` — 갤럭시 앱 "관리 캘린더" (Kotlin). 관리 시스템 달력 + 홈 화면 한 달 위젯, 일정 추가·수정·완료
- `server/cal/` — 관리 캘린더용 Edge Function. 관리 시스템이 올린 `cal_items`를 앱에 주고, 앱에서 바꾼 내용은 `cal_ops`에 쌓아 관리 시스템이 원본에 반영. 연결 코드는 `cal_settings.token`
- `.github/workflows/build-apk.yml` — push 하면 APK 자동 빌드 (Actions 탭 → 최신 실행 → crmsync-apk, apk 브랜치에 crmsync.apk·kcal.apk)

서버 비밀값: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `APP_TOKEN` (Supabase 대시보드 → Edge Functions → Secrets)
