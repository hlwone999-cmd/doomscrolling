package app.tidemark.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackedApp::class, SessionRow::class, ReelEventRow::class, DailyStatRow::class,
        GoalRow::class, GoalEventRow::class,
    ],
    // Still 1: no build of this app has ever been installed, so the schema was
    // extended in place. From the first public release on, every change is a
    // new version with a Migration (and must re-create TRIGGERS).
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackedApps(): TrackedAppDao
    abstract fun sessions(): SessionDao
    abstract fun reelEvents(): ReelEventDao
    abstract fun stats(): StatsDao
    abstract fun goals(): GoalDao
    abstract fun demoPurge(): DemoPurgeDao

    companion object {
        const val NAME = "tidemark.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addCallback(Callback)
                .build()

        /** Tests only: same triggers and seed rows, nothing on disk. */
        fun buildInMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
                .addCallback(Callback)
                .build()

        /**
         * Triggers keep session.reel_count and daily_stat in step with reel_event.
         * Written without UPSERT ("ON CONFLICT DO UPDATE"): Android 8-10 ship
         * SQLite older than 3.24, so we use INSERT OR IGNORE + UPDATE.
         * Every future migration must re-create these if it rebuilds a table.
         */
        val TRIGGERS = listOf(
            """CREATE TRIGGER IF NOT EXISTS reel_event_rollup AFTER INSERT ON reel_event
               BEGIN
                 UPDATE session SET reel_count = reel_count + 1,
                        last_event_at = MAX(last_event_at, NEW.at), updated_at = NEW.at
                  WHERE id = NEW.session_id;
                 INSERT OR IGNORE INTO daily_stat (day_key, package, reels, active_ms, sessions, updated_at)
                   SELECT day_key, package, 0, 0, 0, NEW.at FROM session WHERE id = NEW.session_id;
                 UPDATE daily_stat SET reels = reels + 1, updated_at = NEW.at
                  WHERE day_key = (SELECT day_key FROM session WHERE id = NEW.session_id)
                    AND package = (SELECT package FROM session WHERE id = NEW.session_id);
               END""",
            """CREATE TRIGGER IF NOT EXISTS session_open_rollup AFTER INSERT ON session
               BEGIN
                 INSERT OR IGNORE INTO daily_stat (day_key, package, reels, active_ms, sessions, updated_at)
                   VALUES (NEW.day_key, NEW.package, 0, 0, 0, NEW.started_at);
                 UPDATE daily_stat SET sessions = sessions + 1, updated_at = NEW.started_at
                  WHERE day_key = NEW.day_key AND package = NEW.package;
               END""",
            """CREATE TRIGGER IF NOT EXISTS session_close_rollup AFTER UPDATE OF ended_at ON session
               WHEN OLD.ended_at IS NULL AND NEW.ended_at IS NOT NULL
               BEGIN
                 UPDATE daily_stat SET active_ms = active_ms + NEW.active_ms, updated_at = NEW.ended_at
                  WHERE day_key = NEW.day_key AND package = NEW.package;
               END""",
        )

        /** The apps the bundled rules know. Kept in step with assets/detectors. */
        /** package, name, surface, enabled by default. Facebook and Snapchat are opt-in (could-haves). */
        private val SEED_APPS = listOf(
            listOf("com.instagram.android", "Instagram", "reels", 1),
            listOf("com.google.android.youtube", "YouTube", "shorts", 1),
            listOf("com.zhiliaoapp.musically", "TikTok", "tiktok_feed", 1),
            listOf("com.ss.android.ugc.trill", "TikTok", "tiktok_feed", 1),
            listOf("com.facebook.katana", "Facebook", "fb_reels", 0),
            listOf("com.snapchat.android", "Snapchat", "spotlight", 0),
        )

        private object Callback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                TRIGGERS.forEach { db.execSQL(it) }
                val now = System.currentTimeMillis()
                SEED_APPS.forEach { (pkg, name, surface, enabled) ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO tracked_app (package, display_name, surface_kind, enabled, last_seen_version_code, added_at, updated_at) VALUES (?, ?, ?, ?, NULL, ?, ?)",
                        arrayOf<Any>(pkg, name, surface, enabled, now, now),
                    )
                }
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
                // Defensive: if a future migration dropped them, put them back.
                TRIGGERS.forEach { db.execSQL(it) }
            }
        }
    }
}
