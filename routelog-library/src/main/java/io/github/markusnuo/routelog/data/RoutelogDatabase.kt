package io.github.markusnuo.routelog.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecordingEntity::class, TrackPointEntity::class, UploadEntity::class, GrantEntity::class],
    version = 3,
    exportSchema = true,
    // 2: uploads and used grants for recordings started with an upload target.
    // 3: recordings.alive_at_millis, the latest sign of life the resume window counts from.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class RoutelogDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    abstract fun uploadDao(): UploadDao

    abstract fun grantDao(): GrantDao

    /** Moves everything from the write-ahead log into the database file, e.g. before a backup. */
    fun checkpoint() {
        openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    }

    companion object {
        const val FILE_NAME = "routelog.db"

        @Volatile
        private var instance: RoutelogDatabase? = null

        fun get(context: Context): RoutelogDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext, FILE_NAME).also { instance = it }
            }

        /** [name] null builds an in-memory database (tests). */
        fun build(context: Context, name: String?): RoutelogDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context, RoutelogDatabase::class.java)
            } else {
                Room.databaseBuilder(context, RoutelogDatabase::class.java, name)
            }
            return builder
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(DurabilityCallback)
                .build()
        }
    }

    private object DurabilityCallback : Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            // A fix counts as saved once its transaction commits. FULL syncs the log to storage on
            // every commit, so not even a power loss right afterwards loses it.
            db.query("PRAGMA synchronous = FULL").use { it.moveToFirst() }
            // Overwrite deleted recordings on disk instead of leaving them in free pages.
            db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
        }
    }
}
