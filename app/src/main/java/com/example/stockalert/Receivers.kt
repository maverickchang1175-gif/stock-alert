package com.example.stockalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!Store.isMonitoring(app)) return

        // 다음 알람을 먼저 예약해서 어떤 경우에도 주기가 끊기지 않게 함
        Checker.schedule(app)

        val pending = goAsync()
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stockalert:check")
        wl.acquire(20000L)
        Thread {
            try {
                Checker.runCheck(app)
            } catch (e: Exception) {
                // 다음 주기에 다시 시도
            } finally {
                if (wl.isHeld) wl.release()
                pending.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (Store.isMonitoring(app)) Checker.schedule(app, 60 * 1000L)
    }
}
