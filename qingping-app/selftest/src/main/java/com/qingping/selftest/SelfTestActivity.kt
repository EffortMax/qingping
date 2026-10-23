package com.qingping.selftest

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 自检用假界面，独立包名 com.qingping.selftest，用于验证清屏的跳过逻辑。
 * mode=ad     : 模拟开屏广告（右上角“跳过 5”倒计时按钮）
 * mode=normal : 普通界面（“关闭”“知道了”“取消”等常见按钮，清屏绝不应点击）
 * 任何点击都会打印 QPSELFTEST 日志。
 */
class SelfTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "ad"
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0E0E12")) }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 160, 60, 60)
        }

        fun label(text: String, size: Float, color: String = "#FFFFFF") = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(Color.parseColor(color))
        }

        if (mode == "ad") {
            col.addView(label("模拟开屏广告页面", 30f))
            col.addView(label("（这是自检用假广告，清屏应当自动点掉右上角“跳过”）", 14f, "#8888AA"))

            // 右上角小按钮：真实开屏广告的跳过按钮形态
            val skip = Button(this).apply {
                text = "跳过 5"
                setOnClickListener {
                    Log.i("QPSELFTEST", "CLICKED:跳过")
                    // 模拟广告关闭后的主页
                    setContentView(FrameLayout(this@SelfTestActivity).apply {
                        setBackgroundColor(Color.parseColor("#0E0E12"))
                        addView(TextView(this@SelfTestActivity).apply {
                            text = "广告已跳过 · 进入正常页面"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                            setTextColor(Color.parseColor("#44DD88"))
                            setPadding(60, 400, 60, 60)
                        })
                    })
                }
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 1560; topMargin = 60 }
            root.addView(col)
            root.addView(skip, lp)
        } else {
            col.addView(label("普通界面（模拟日常 APP）", 30f))
            col.addView(label("下面这些按钮清屏一个都不许点", 14f, "#8888AA"))

            val names = listOf("关闭", "知道了", "取消", "确定", "关闭广告位说明", "跳过片头设置")
            for (n in names) {
                val b = Button(this).apply {
                    text = n
                    setOnClickListener { Log.w("QPSELFTEST", "CLICKED:$n") }
                }
                col.addView(b)
            }
            root.addView(col)
        }

        setContentView(root)
        Log.i("QPSELFTEST", "OPENED:$mode")
    }
}
