package com.qingping.app

import android.app.Application
import com.qingping.app.data.AppDatabase
import com.qingping.app.data.StatRepository

class QingPingApp : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val repository by lazy { StatRepository(database.statDao()) }

    companion object {
        const val PREFS = "qingping_prefs"
        const val KEY_AUTO_BACK = "auto_back"
        const val KEY_SHOW_TOAST = "show_toast"
        const val KEY_KEYWORDS = "keywords"
        /** 严格模式：白名单完全匹配 + 形态校验（默认开启，防误点） */
        const val KEY_STRICT_MODE = "strict_mode"
        /**
         * 宽松模式调试用关键词。注意：严格模式下该值不生效；
         * 且 v1.0 的“关闭/知道了”是误点根源，已彻底移除。
         */
        const val DEFAULT_KEYWORDS = "跳过,Skip"
    }
}
