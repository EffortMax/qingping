package com.qingping.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 按 APP 统计拦截次数。
 * packageName 为主键，一个 APP 一行。
 */
@Entity(tableName = "stats")
data class StatEntity(
    @PrimaryKey val packageName: String,
    val skipCount: Int = 0,       // 跳过开屏次数
    val backCount: Int = 0,       // 拦截误触跳转次数
    val lastTime: Long = 0L       // 最近一次拦截时间戳
)
