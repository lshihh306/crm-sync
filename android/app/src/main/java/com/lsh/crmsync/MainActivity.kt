package com.lsh.crmsync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var log: TextView

    private val perms: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "상담 자동전송"
        setContentView(buildUi())
        // 처음 실행이면 "지금 이후"에 생기는 파일만 보내도록
        if (prefs.sinceAudio == 0L) {
            val now = System.currentTimeMillis() / 1000
            prefs.sinceAudio = now
            prefs.sinceImage = now
        }
        if (prefs.token.isNotBlank()) Sched.start(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun section(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
        })
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(40))
        }

        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(4), 0, dp(4)) }
        root.addView(status)

        section(root, "① 권한")
        root.addView(Button(this).apply {
            text = "사진·오디오·문자 보내기 권한 허용"
            setOnClickListener { requestPermissions(perms, 1) }
        })
        root.addView(Button(this).apply {
            text = "배터리 최적화 제외 (꼭 해주세요)"
            setOnClickListener { askBattery() }
        })

        section(root, "② 서버 연결")
        val token = EditText(this).apply {
            hint = "앱 출입증 (APP_TOKEN)"
            setText(prefs.token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(token)
        val url = EditText(this).apply {
            hint = "서버 주소"
            setText(prefs.serverUrl)
            textSize = 12f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(url)
        root.addView(Button(this).apply {
            text = "저장하고 자동전송 시작"
            setOnClickListener {
                prefs.token = token.text.toString()
                prefs.serverUrl = url.text.toString().ifBlank { Prefs.DEFAULT_URL }
                Sched.start(this@MainActivity)
                Toast.makeText(this@MainActivity, "저장했어요. 이제 새 녹음·캡처가 자동으로 전송돼요", Toast.LENGTH_LONG).show()
                refresh()
            }
        })

        section(root, "③ 보낼 것")
        fun sw(label: String, get: () -> Boolean, set: (Boolean) -> Unit) = Switch(this).apply {
            text = label
            isChecked = get()
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, b -> set(b); refresh() }
        }
        root.addView(sw("📞 통화녹음 (자동)", { prefs.callOn }) { prefs.callOn = it })
        root.addView(sw("💛 카톡 캡처 (자동)", { prefs.kakaoOn }) { prefs.kakaoOn = it })
        root.addView(sw("💬 문자 캡처 (자동)", { prefs.smsOn }) { prefs.smsOn = it })
        root.addView(sw("와이파이에서만 보내기", { prefs.wifiOnly }) { prefs.wifiOnly = it; Sched.armTrigger(this, androidx.work.ExistingWorkPolicy.REPLACE) })

        root.addView(TextView(this).apply {
            text = "캡처 파일 이름에 아래 단어가 있으면 보내요 (쉼표로 구분)"
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })
        val kk = EditText(this).apply { setText(prefs.kakaoKeys); hint = "카톡 캡처 이름 단어" }
        val sk = EditText(this).apply { setText(prefs.smsKeys); hint = "문자 캡처 이름 단어" }
        root.addView(kk); root.addView(sk)
        root.addView(Button(this).apply {
            text = "단어 저장"
            setOnClickListener {
                prefs.kakaoKeys = kk.text.toString(); prefs.smsKeys = sk.text.toString()
                Toast.makeText(this@MainActivity, "저장했어요", Toast.LENGTH_SHORT).show()
            }
        })

        section(root, "④ 자동 문자 (안부·감사·보험 만기)")
        root.addView(TextView(this).apply {
            text = "관리 시스템 '자동 문자' 탭에 예약된 문자를 이 폰 번호로 보내요. 매일 오후 2시 무렵(5~15분 안) 나가고, 보낸 문자는 메시지 앱에도 남아요. 켜고 끄기는 관리 시스템에서 해요."
            textSize = 12.5f
        })
        root.addView(sw("✉️ 이 폰에서 자동 문자 보내기", { prefs.autoSmsOn }) { prefs.autoSmsOn = it })
        root.addView(Button(this).apply {
            text = "지금 보낼 문자 확인"
            setOnClickListener {
                if (!SmsSender.hasPermission(this@MainActivity)) { requestPermissions(SmsSender.PERMS, 2); return@setOnClickListener }
                SmsSender.runNow(this@MainActivity)
                Toast.makeText(this@MainActivity, "확인 중… 보낼 문자가 있으면 바로 나가요", Toast.LENGTH_SHORT).show()
                log.postDelayed({ refresh() }, 12000)
            }
        })

        section(root, "⑤ 수동 실행")
        root.addView(Button(this).apply {
            text = "지금 바로 확인해서 보내기"
            setOnClickListener {
                Sched.runNow(this@MainActivity)
                Toast.makeText(this@MainActivity, "확인 중… 잠시 뒤 아래 기록을 보세요", Toast.LENGTH_SHORT).show()
                log.postDelayed({ refresh() }, 8000)
            }
        })
        root.addView(Button(this).apply {
            text = "최근 1일 녹음·캡처 다시 보내기"
            setOnClickListener {
                Scanner.rewind(this@MainActivity, 1)
                Sched.runNow(this@MainActivity)
                Toast.makeText(this@MainActivity, "어제부터 생긴 파일을 보내요 (이미 보낸 건 서버가 걸러요)", Toast.LENGTH_LONG).show()
                log.postDelayed({ refresh() }, 10000)
            }
        })

        section(root, "최근 전송 기록")
        log = TextView(this).apply { textSize = 12.5f; setTextIsSelectable(true) }
        root.addView(log)
        root.addView(Button(this).apply { text = "기록 새로고침"; setOnClickListener { refresh() } })

        return ScrollView(this).apply { addView(root) }
    }

    private fun hasPerms() = perms.filter { it != Manifest.permission.POST_NOTIFICATIONS && it != Manifest.permission.SEND_SMS && it != Manifest.permission.READ_PHONE_STATE }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun batteryOk(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val lines = mutableListOf<String>()
        lines += if (hasPerms()) "✅ 사진·오디오 접근 허용됨" else "⚠️ 사진·오디오 접근 권한이 필요해요"
        lines += if (SmsSender.hasPermission(this)) "✅ 문자·전화 권한 허용됨 (자동 문자)" else "⚠️ 자동 문자를 쓰려면 '문자'와 '전화' 권한을 허용해 주세요 (권한 허용 버튼)"
        lines += if (batteryOk()) "✅ 배터리 최적화 제외됨" else "⚠️ 배터리 최적화 제외를 해주세요 (안 하면 전송이 늦어져요)"
        lines += if (prefs.token.isNotBlank()) "✅ 서버 연결 정보 저장됨" else "⚠️ 앱 출입증을 넣고 저장해 주세요"
        status.text = lines.joinToString("\n")
        log.text = prefs.logText.ifBlank { "아직 보낸 게 없어요" }
    }
}
