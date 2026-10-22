package com.example.stockalert

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.UUID

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var listBox: LinearLayout
    private lateinit var msg: TextView
    private lateinit var symInput: EditText
    private lateinit var targetInput: EditText
    private lateinit var rbAbove: RadioButton
    private lateinit var addBtn: Button

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(label: String, sp: Float = 14f, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = sp
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        return b
    }

    private fun showMsg(text: String, good: Boolean) {
        msg.text = text
        msg.setTextColor(if (good) Color.rgb(74, 222, 128) else Color.rgb(248, 113, 113))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Checker.ensureChannel(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(28), dp(16), dp(28))
        scroll.addView(root)

        root.addView(tv("📈 주식 가격 알림", 22f, true))

        val sw = Switch(this)
        sw.text = "가격 감시 켜기 (5분마다 확인)"
        sw.isChecked = Store.isMonitoring(this)
        sw.setOnCheckedChangeListener { _, on ->
            Store.setMonitoring(this, on)
            if (on) Checker.ensureScheduled(this) else Checker.cancel(this)
        }
        root.addView(sw)

        root.addView(tv("종목 (미국: TSLA / 국내: 005930)", 12f))
        symInput = EditText(this)
        symInput.hint = "티커 또는 종목코드"
        symInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        symInput.setSingleLine(true)
        root.addView(symInput)

        val rg = RadioGroup(this)
        rg.orientation = RadioGroup.HORIZONTAL
        rbAbove = RadioButton(this)
        rbAbove.text = "이상이 되면"
        rbAbove.id = 1001
        val rbBelow = RadioButton(this)
        rbBelow.text = "이하가 되면"
        rbBelow.id = 1002
        rg.addView(rbAbove)
        rg.addView(rbBelow)
        rg.check(1001)
        root.addView(rg)

        root.addView(tv("목표가", 12f))
        targetInput = EditText(this)
        targetInput.hint = "예: 160"
        targetInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        targetInput.setSingleLine(true)
        root.addView(targetInput)

        addBtn = btn("알림 추가") { addAlert() }
        root.addView(addBtn)

        msg = tv("", 13f)
        root.addView(msg)

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        root.addView(listBox)

        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.VERTICAL
        tools.setPadding(0, dp(16), 0, 0)
        tools.addView(btn("지금 확인하기") { checkNow() })
        tools.addView(btn("테스트 알림 보내기") {
            if (Checker.sendTest(this)) {
                showMsg("테스트 알림을 보냈어요. 상단 알림을 확인해 보세요", true)
            } else {
                showMsg("알림이 꺼져 있어요. 폰 설정에서 이 앱의 알림을 허용해 주세요", false)
            }
        })
        tools.addView(btn("배터리 최적화에서 제외하기") { requestBatteryExemption() })
        root.addView(tools)

        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Store.isMonitoring(this)) Checker.ensureScheduled(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (Store.isMonitoring(this)) Checker.ensureScheduled(this)
        refresh()
    }

    private fun addAlert() {
        val sym = symInput.text.toString()
        val t = targetInput.text.toString().toDoubleOrNull()
        if (t == null || t <= 0.0) {
            showMsg("목표가를 확인해 주세요", false)
            return
        }
        val above = rbAbove.isChecked
        addBtn.isEnabled = false
        showMsg("종목 확인 중...", true)
        Thread {
            val q = Quotes.resolve(sym)
            ui.post {
                addBtn.isEnabled = true
                if (q == null) {
                    showMsg("종목을 찾지 못했어요. 티커를 확인해 주세요 (예: TSLA, 005930)", false)
                } else {
                    val already = if (above) q.price >= t else q.price <= t
                    if (already) {
                        showMsg(
                            "현재가(" + Checker.fmt(q.price, q.currency) +
                                ")가 이미 이 조건을 만족해요. 목표가를 확인해 주세요", false
                        )
                    } else {
                        val item = Alert(
                            UUID.randomUUID().toString(), q.symbol, q.name, q.currency,
                            t, above, q.price, null
                        )
                        Store.update(this) { list -> list.add(item) }
                        symInput.setText("")
                        targetInput.setText("")
                        showMsg("추가했어요", true)
                        if (Store.isMonitoring(this)) Checker.ensureScheduled(this)
                        refresh()
                    }
                }
            }
        }.start()
    }

    private fun checkNow() {
        showMsg("시세 확인 중...", true)
        Thread {
            var n = 0
            var err = false
            try {
                n = Checker.runCheck(this)
            } catch (e: Exception) {
                err = true
            }
            ui.post {
                refresh()
                if (err) {
                    showMsg("확인 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요", false)
                } else if (n > 0) {
                    showMsg("조건에 도달한 알림 " + n + "건을 보냈어요", true)
                } else {
                    showMsg("확인 완료: 도달한 조건이 없어요", true)
                }
            }
        }.start()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            showMsg("이미 배터리 최적화에서 제외되어 있어요", true)
        } else {
            try {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = Uri.parse("package:" + packageName)
                startActivity(i)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun refresh() {
        listBox.removeAllViews()
        val list = Store.load(this)
        if (list.isEmpty()) {
            val e = tv("등록된 알림이 없어요", 14f)
            e.setPadding(0, dp(24), 0, dp(24))
            listBox.addView(e)
            return
        }
        for (a in list) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(0, dp(14), 0, dp(6))

            val state = if (a.firedAt != null) "  [발송됨]" else "  [감시 중]"
            card.addView(tv(a.name + state, 16f, true))

            val lp = a.lastPrice
            val cur = if (lp != null) Checker.fmt(lp, a.currency) else "-"
            card.addView(tv(a.symbol + " · 현재가 " + cur, 12f))

            val cond = tv(
                Checker.fmt(a.target, a.currency) + (if (a.above) " 이상" else " 이하"),
                15f
            )
            cond.setTextColor(
                if (a.above) Color.rgb(248, 113, 113) else Color.rgb(96, 165, 250)
            )
            card.addView(cond)

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            if (a.firedAt != null) {
                row.addView(btn("다시 켜기") {
                    Store.update(this) { l ->
                        val x = l.find { it.id == a.id }
                        if (x != null) x.firedAt = null
                    }
                    refresh()
                })
            }
            row.addView(btn("삭제") {
                Store.update(this) { l -> l.removeAll { it.id == a.id } }
                refresh()
            })
            card.addView(row)
            listBox.addView(card)
        }
    }
}
