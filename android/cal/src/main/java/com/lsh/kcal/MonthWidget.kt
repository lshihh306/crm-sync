package com.lsh.kcal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/** 홈 화면 위젯: 한 달 달력 (위쪽 ‹ › 로 달 이동, 나머지 누르면 앱 열림) */
class MonthWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { draw(ctx, mgr, it) }
        Sync.schedule(ctx)
        Sync.now(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        draw(ctx, mgr, id)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACT_PREV, ACT_NEXT -> {
                val st = Store(ctx)
                st.widgetOffset = st.widgetOffset + if (intent.action == ACT_NEXT) 1 else -1
                st.widgetOffsetDay = LocalDate.now().toString()
                updateAll(ctx)
            }
        }
    }

    companion object {
        const val ACT_PREV = "com.lsh.kcal.WIDGET_PREV"
        const val ACT_NEXT = "com.lsh.kcal.WIDGET_NEXT"

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.getAppWidgetIds(ComponentName(ctx, MonthWidget::class.java)).forEach { draw(ctx, mgr, it) }
        }

        private fun draw(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val st = Store(ctx)
            // 다음 날이 되면 이번 달로 돌아옴
            if (st.widgetOffsetDay != LocalDate.now().toString() && st.widgetOffset != 0) st.widgetOffset = 0
            val ym = YearMonth.now().plusMonths(st.widgetOffset.toLong())

            val opts = mgr.getAppWidgetOptions(id)
            val dm = ctx.resources.displayMetrics
            val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val wDp = (if (portrait) opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) else opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)).takeIf { it > 0 } ?: 300
            val hDp = (if (portrait) opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) else opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)).takeIf { it > 0 } ?: 300
            // 위젯 그림 크기 제한 (너무 크면 홈 화면이 그림을 거부함)
            var scale = dm.density
            val maxPx = 1_400_000f
            if (wDp * hDp * scale * scale > maxPx) scale = kotlin.math.sqrt(maxPx / (wDp * hDp))
            val wPx = (wDp * scale).toInt().coerceAtLeast(100)
            val hPx = (hDp * scale).toInt().coerceAtLeast(100)

            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
            MonthRenderer(scale, if (night) Palette.DARK else Palette.LIGHT)
                .draw(Canvas(bmp), wPx.toFloat(), hPx.toFloat(), ym, st.byDate(), null, showTitle = true, compact = true)

            val rv = RemoteViews(ctx.packageName, R.layout.month_widget)
            rv.setImageViewBitmap(R.id.w_img, bmp)
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).putExtra("month", ym.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(R.id.w_img, open)
            rv.setOnClickPendingIntent(R.id.w_prev, broadcast(ctx, ACT_PREV, 1))
            rv.setOnClickPendingIntent(R.id.w_next, broadcast(ctx, ACT_NEXT, 2))
            mgr.updateAppWidget(id, rv)
        }

        private fun broadcast(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(ctx, code,
            Intent(ctx, MonthWidget::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** 15분마다 서버에서 새로 받아서 위젯 다시 그리기 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Api.refresh(applicationContext)
            MonthWidget.updateAll(applicationContext)
            Result.success()
        } catch (e: Api.ApiError) {
            if (e.code == 401) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object Sync {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("kcal-sync", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
    }
    fun now(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("kcal-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).build())
    }
}
