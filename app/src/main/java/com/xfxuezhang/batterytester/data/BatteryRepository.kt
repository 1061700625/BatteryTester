package com.xfxuezhang.batterytester.data

import android.content.Context
import com.xfxuezhang.batterytester.battery.BatteryMode
import com.xfxuezhang.batterytester.battery.BatterySnapshot
import com.xfxuezhang.batterytester.battery.StopReason

class BatteryRepository private constructor(context: Context) {
    private val db = BatteryDbHelper(context.applicationContext)

    fun insertSession(session: BatterySession) = synchronized(db) { db.insertSession(session) }
    fun insertSample(sessionId: String, snapshot: BatterySnapshot) = synchronized(db) { db.insertSample(sessionId, snapshot) }
    fun getSession(id: String): BatterySession? = synchronized(db) { db.getSession(id) }
    fun getSessions(limit: Int = 100): List<BatterySession> = synchronized(db) { db.getSessions(limit) }
    fun getSamples(sessionId: String): List<BatterySample> = synchronized(db) { db.getSamples(sessionId) }
    fun getActiveSession(): BatterySession? = synchronized(db) { db.getActiveSession() }
    fun getLatestSample(sessionId: String): BatterySample? = synchronized(db) { db.getLatestSample(sessionId) }

    fun finishSession(sessionId: String, endLevel: Int?, stopReason: StopReason) = synchronized(db) {
        db.finishSession(sessionId, System.currentTimeMillis(), endLevel, stopReason)
    }

    fun closeActiveAsReplaced() = synchronized(db) {
        val active = db.getActiveSession()
        if (active != null) {
            db.finishSession(active.id, System.currentTimeMillis(), null, StopReason.REPLACED_BY_NEW_TEST)
        }
    }

    companion object {
        @Volatile private var instance: BatteryRepository? = null

        fun get(context: Context): BatteryRepository {
            return instance ?: synchronized(this) {
                instance ?: BatteryRepository(context).also { instance = it }
            }
        }
    }
}

fun BatterySnapshot.toSession(id: String, mode: BatteryMode, startSource: String, appVersion: String): BatterySession {
    val profile = deviceProfile
    return BatterySession(
        id = id,
        mode = mode,
        startSource = startSource,
        startTime = timestamp,
        endTime = null,
        stopReason = null,
        deviceBrand = profile.brand,
        deviceModel = profile.model,
        manufacturer = profile.manufacturer,
        androidVersion = "${profile.release} / SDK ${profile.sdkInt}",
        startLevel = levelPercent,
        endLevel = null,
        appVersion = appVersion
    )
}
