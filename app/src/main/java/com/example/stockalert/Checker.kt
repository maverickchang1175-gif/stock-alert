package com.example.stockalert

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.text.DecimalFormat

object Checker {
    const val CHANNEL = "price_alert"
    const val INTERVAL_MS = 5 * 60 * 1000L

    fun fmt(v: Double, cur: String): String {
        val s = DecimalFormat("#,##0.##").format(v)
        return when (cur) {
            "KRW" -> s + "원"
            "USD" -> "\$" + s
            "" -> s
            else -> s + " " + cur
        }
    }

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "가격 알림", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
    }

    // ---------- 알람 예약 (5분 주기) ----------
    private fun alarmIntent(c: Context, create: Boolean): PendingIntent? {
        val i = Intent(c, AlarmReceiver::class.java)
        val flags = if (create) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(c, 1, i, flags)
    }

    fun schedule(c: Context, delayMs: Long = INTERVAL_MS) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(c, true) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
    }

    // 이미 예약돼 있으면 그대로 두고, 없을 때만 예약
    fun ensureScheduled(c: Context) {
        if (alarmIntent(c, false) == null) schedule(c, 30 * 1000L)
    }

    fun cancel(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(c, false)
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    // ---------- 시세 확인 + 조건 판정 ----------
    fun runCheck(c: Context): Int {
        val alerts = Store.load(c)
        if (alerts.isEmpty()) return 0
        val symbols = alerts.map { it.symbol }.distinct()
        val quotes = Quotes.fetchAll(symbols)

        val prices = HashMap<String, Double>()
        val fired = HashMap<String, Long>()
        for (a in alerts) {
            val q = quotes[a.symbol] ?: continue
            prices[a.id] = q.price
            if (a.firedAt == null) {
                val hit = if (a.above) q.price >= a.target else q.price <= a.target
                if (hit && notifyHit(c, a, q)) {
                    fired[a.id] = System.currentTimeMillis()
                }
            }
        }

        // 그 사이 사용자가 수정했을 수 있으니 다시 읽어서 병합
        Store.update(c) { list ->
            for (a in list) {
                val p = prices[a.id]
                if (p != null) a.lastPrice = p
                val f = fired[a.id]
                if (f != null) a.firedAt = f
            }
        }
        return fired.size
    }

    private fun notifyHit(c: Context, a: Alert, q: Quotes.Quote): Boolean {
        ensureChannel(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        val word = if (a.above) "이상" else "이하"
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = "현재가 " + fmt(q.price, q.currency) +
            " (설정가 " + fmt(a.target, q.currency) + " " + word + ")"
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(a.name + " 알림")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(a.id.hashCode(), n)
        return true
    }

    fun sendTest(c: Context): Boolean {
        ensureChannel(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("주식 알림 테스트")
            .setContentText("이 알림이 보이면 알림 설정은 정상이에요.")
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
        return true
    }
}
