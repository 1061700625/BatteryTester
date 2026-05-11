package com.xfxuezhang.batterytester.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.xfxuezhang.batterytester.battery.BatteryMode
import com.xfxuezhang.batterytester.battery.BatterySnapshot
import com.xfxuezhang.batterytester.battery.StopReason

class BatteryDbHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE battery_session (
                id TEXT PRIMARY KEY NOT NULL,
                mode TEXT NOT NULL,
                start_source TEXT NOT NULL,
                start_time INTEGER NOT NULL,
                end_time INTEGER,
                stop_reason TEXT,
                device_brand TEXT NOT NULL,
                device_model TEXT NOT NULL,
                manufacturer TEXT NOT NULL,
                android_version TEXT NOT NULL,
                start_level INTEGER,
                end_level INTEGER,
                app_version TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE battery_sample (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                session_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                level_percent INTEGER,
                current_now_ma REAL,
                current_average_ma REAL,
                charge_counter_mah REAL,
                energy_counter_nwh INTEGER,
                voltage_v REAL,
                temperature_c REAL,
                health INTEGER,
                status INTEGER,
                plugged INTEGER,
                thermal_status INTEGER,
                is_power_save_mode INTEGER,
                cpu_usage_percent REAL,
                cpu_load_target_percent REAL,
                network_downloaded_bytes INTEGER,
                network_limit_bytes INTEGER,
                FOREIGN KEY(session_id) REFERENCES battery_session(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_sample_session_time ON battery_sample(session_id, timestamp)")
        db.execSQL("CREATE INDEX idx_session_start_time ON battery_session(start_time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS battery_sample")
        db.execSQL("DROP TABLE IF EXISTS battery_session")
        onCreate(db)
    }

    fun insertSession(session: BatterySession) {
        writableDatabase.insertWithOnConflict(
            "battery_session",
            null,
            session.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun insertSample(sample: BatterySample): Long {
        return writableDatabase.insert("battery_sample", null, sample.toValues())
    }

    fun insertSample(sessionId: String, snapshot: BatterySnapshot): Long {
        return insertSample(snapshot.toSample(sessionId))
    }

    fun finishSession(sessionId: String, endTime: Long, endLevel: Int?, stopReason: StopReason) {
        val values = ContentValues().apply {
            put("end_time", endTime)
            putNullable("end_level", endLevel)
            put("stop_reason", stopReason.name)
        }
        writableDatabase.update("battery_session", values, "id = ?", arrayOf(sessionId))
    }

    fun getActiveSession(): BatterySession? {
        readableDatabase.rawQuery(
            "SELECT * FROM battery_session WHERE end_time IS NULL ORDER BY start_time DESC LIMIT 1",
            emptyArray()
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toSession() else null
        }
    }

    fun getSession(sessionId: String): BatterySession? {
        readableDatabase.query(
            "battery_session",
            null,
            "id = ?",
            arrayOf(sessionId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toSession() else null
        }
    }

    fun getSessions(limit: Int = 100): List<BatterySession> {
        val result = mutableListOf<BatterySession>()
        readableDatabase.rawQuery(
            "SELECT * FROM battery_session ORDER BY start_time DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toSession()
        }
        return result
    }

    fun getSamples(sessionId: String): List<BatterySample> {
        val result = mutableListOf<BatterySample>()
        readableDatabase.query(
            "battery_sample",
            null,
            "session_id = ?",
            arrayOf(sessionId),
            null,
            null,
            "timestamp ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toSample()
        }
        return result
    }

    fun getLatestSample(sessionId: String): BatterySample? {
        readableDatabase.rawQuery(
            "SELECT * FROM battery_sample WHERE session_id = ? ORDER BY timestamp DESC LIMIT 1",
            arrayOf(sessionId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toSample() else null
        }
    }

    fun deleteSession(sessionId: String): Int {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete("battery_sample", "session_id = ?", arrayOf(sessionId))
            val deleted = db.delete("battery_session", "id = ?", arrayOf(sessionId))
            db.setTransactionSuccessful()
            deleted
        } finally {
            db.endTransaction()
        }
    }

    fun closeOpenSessionsAsDestroyed() {
        val now = System.currentTimeMillis()
        val active = getActiveSession() ?: return
        finishSession(active.id, now, null, StopReason.SERVICE_DESTROYED)
    }

    private fun BatterySession.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("mode", mode.name)
        put("start_source", startSource)
        put("start_time", startTime)
        putNullable("end_time", endTime)
        putNullable("stop_reason", stopReason?.name)
        put("device_brand", deviceBrand)
        put("device_model", deviceModel)
        put("manufacturer", manufacturer)
        put("android_version", androidVersion)
        putNullable("start_level", startLevel)
        putNullable("end_level", endLevel)
        put("app_version", appVersion)
    }

    private fun BatterySample.toValues(): ContentValues = ContentValues().apply {
        put("session_id", sessionId)
        put("timestamp", timestamp)
        putNullable("level_percent", levelPercent)
        putNullable("current_now_ma", currentNowMa)
        putNullable("current_average_ma", currentAverageMa)
        putNullable("charge_counter_mah", chargeCounterMah)
        putNullable("energy_counter_nwh", energyCounterNWh)
        putNullable("voltage_v", voltageV)
        putNullable("temperature_c", temperatureC)
        putNullable("health", health)
        putNullable("status", status)
        putNullable("plugged", plugged)
        putNullable("thermal_status", thermalStatus)
        putNullable("is_power_save_mode", isPowerSaveMode?.let { if (it) 1 else 0 })
        putNullable("cpu_usage_percent", cpuUsagePercent)
        putNullable("cpu_load_target_percent", cpuLoadTargetPercent)
        putNullable("network_downloaded_bytes", networkDownloadedBytes)
        putNullable("network_limit_bytes", networkLimitBytes)
    }

    private fun BatterySnapshot.toSample(sessionId: String): BatterySample = BatterySample(
        sessionId = sessionId,
        timestamp = timestamp,
        levelPercent = levelPercent,
        currentNowMa = currentNowMa,
        currentAverageMa = currentAverageMa,
        chargeCounterMah = chargeCounterMah,
        energyCounterNWh = energyCounterNWh,
        voltageV = voltageV,
        temperatureC = temperatureC,
        health = health,
        status = status,
        plugged = plugged,
        thermalStatus = thermalStatus,
        isPowerSaveMode = isPowerSaveMode,
        cpuUsagePercent = cpuUsagePercent,
        cpuLoadTargetPercent = cpuLoadTargetPercent,
        networkDownloadedBytes = networkDownloadedBytes,
        networkLimitBytes = networkLimitBytes
    )

    private fun Cursor.toSession(): BatterySession {
        return BatterySession(
            id = getString(column("id")),
            mode = BatteryMode.valueOf(getString(column("mode"))),
            startSource = getString(column("start_source")),
            startTime = getLong(column("start_time")),
            endTime = nullableLong("end_time"),
            stopReason = nullableString("stop_reason")?.let { StopReason.valueOf(it) },
            deviceBrand = getString(column("device_brand")),
            deviceModel = getString(column("device_model")),
            manufacturer = getString(column("manufacturer")),
            androidVersion = getString(column("android_version")),
            startLevel = nullableInt("start_level"),
            endLevel = nullableInt("end_level"),
            appVersion = getString(column("app_version"))
        )
    }

    private fun Cursor.toSample(): BatterySample {
        return BatterySample(
            id = getLong(column("id")),
            sessionId = getString(column("session_id")),
            timestamp = getLong(column("timestamp")),
            levelPercent = nullableInt("level_percent"),
            currentNowMa = nullableDouble("current_now_ma"),
            currentAverageMa = nullableDouble("current_average_ma"),
            chargeCounterMah = nullableDouble("charge_counter_mah"),
            energyCounterNWh = nullableLong("energy_counter_nwh"),
            voltageV = nullableDouble("voltage_v"),
            temperatureC = nullableDouble("temperature_c"),
            health = nullableInt("health"),
            status = nullableInt("status"),
            plugged = nullableInt("plugged"),
            thermalStatus = nullableInt("thermal_status"),
            isPowerSaveMode = nullableInt("is_power_save_mode")?.let { it == 1 },
            cpuUsagePercent = nullableDouble("cpu_usage_percent"),
            cpuLoadTargetPercent = nullableDouble("cpu_load_target_percent"),
            networkDownloadedBytes = nullableLong("network_downloaded_bytes"),
            networkLimitBytes = nullableLong("network_limit_bytes")
        )
    }

    private fun Cursor.column(name: String): Int = getColumnIndexOrThrow(name)
    private fun Cursor.nullableString(name: String): String? = column(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.nullableInt(name: String): Int? = column(name).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.nullableLong(name: String): Long? = column(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.nullableDouble(name: String): Double? = column(name).let { if (isNull(it)) null else getDouble(it) }

    private fun ContentValues.putNullable(key: String, value: String?) { if (value == null) putNull(key) else put(key, value) }
    private fun ContentValues.putNullable(key: String, value: Int?) { if (value == null) putNull(key) else put(key, value) }
    private fun ContentValues.putNullable(key: String, value: Long?) { if (value == null) putNull(key) else put(key, value) }
    private fun ContentValues.putNullable(key: String, value: Double?) { if (value == null) putNull(key) else put(key, value) }

    companion object {
        private const val DATABASE_NAME = "battery_tester.db"
        private const val DATABASE_VERSION = 3
    }
}
