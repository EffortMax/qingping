package com.qingping.app.data

import kotlinx.coroutines.flow.Flow

class StatRepository(private val dao: StatDao) {

    fun observeAll(): Flow<List<StatEntity>> = dao.observeAll()
    fun observeTotalSkip(): Flow<Int> = dao.observeTotalSkip()
    fun observeTotalBack(): Flow<Int> = dao.observeTotalBack()

    /** 记录一次“跳过开屏”，按包名累加 */
    suspend fun recordSkip(packageName: String) {
        val old = dao.getByPackage(packageName)
        if (old == null) {
            dao.upsert(StatEntity(packageName, skipCount = 1, lastTime = System.currentTimeMillis()))
        } else {
            dao.upsert(old.copy(skipCount = old.skipCount + 1, lastTime = System.currentTimeMillis()))
        }
    }

    /** 记录一次“拦截误触跳转”，记在跳转来源 APP 名下 */
    suspend fun recordBack(fromPackage: String) {
        val old = dao.getByPackage(fromPackage)
        if (old == null) {
            dao.upsert(StatEntity(fromPackage, backCount = 1, lastTime = System.currentTimeMillis()))
        } else {
            dao.upsert(old.copy(backCount = old.backCount + 1, lastTime = System.currentTimeMillis()))
        }
    }

    suspend fun clearAll() = dao.clearAll()
}
