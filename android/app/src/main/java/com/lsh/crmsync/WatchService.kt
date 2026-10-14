package com.lsh.crmsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore

/**
 * 폰이 앱을 재우지 않도록 상단 알림 하나를 띄워 두고,
 * 녹음 폴더·사진첩에 새 파일이 생기는 순간 바로 확인하게 하는 감시 서비스.
 */
class WatchService : Service() {
    private var observer: ContentObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // 파일이 쓰이는 동안 여러 번 불려도 한 번만 확인하도록 20초 뒤로 미룸
                Sched.soon(applicationContext, 20)
            }
        }
        contentResolver.registerContentObserver(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, obs)
        contentResolver.registerContentObserver(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, obs)
        observer = obs
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Prefs(this).token.isBlank()) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "자동전송 대기", NotificationManager.IMPORTANCE_MIN).apply {
            description = "통화 녹음·카톡·문자 캡처를 바로 보내기 위해 켜져 있어요"
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("상담 자동전송 켜짐")
            .setContentText("새 녹음·캡처가 생기면 바로 관리 시스템으로 보내요")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
    }

    companion object {
        private const val CH = "watch"
        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, WatchService::class.java))
            } catch (e: Exception) {
                // 백그라운드에서 시작이 막히면 다음에 앱을 열 때 다시 켜짐 (그동안은 기존 방식으로 동작)
            }
        }
    }
}
