package com.lsh.crmsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 폰을 껐다 켜거나 앱이 업데이트되면 자동전송을 다시 걸어둠 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).token.isNotBlank()) Sched.start(context)
    }
}
