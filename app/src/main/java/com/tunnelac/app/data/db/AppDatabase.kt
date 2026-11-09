package com.tunnelac.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TunnelEntity::class, EventEntity::class, ApproachEntity::class, ApproachDoneEntity::class, AdjacentEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tunnelDao(): TunnelDao
    abstract fun eventDao(): EventDao
    abstract fun approachDao(): ApproachDao
    abstract fun approachDoneDao(): ApproachDoneDao
    abstract fun adjacentDao(): AdjacentDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v2: events 에 공조 부가 설명(hvacNote) 열 추가. 기존 기록은 그대로 보존. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN hvacNote TEXT")
            }
        }

        /** v3: 터널 입구 접근도로 테이블 추가. 기존 터널·이벤트 데이터는 그대로 보존. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `approach_roads` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tunnelId` INTEGER NOT NULL, `endIdx` INTEGER NOT NULL, `pointsJson` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_approach_roads_tunnelId` ON `approach_roads` (`tunnelId`)")
            }
        }

        /** v4: 접근도로 받기 이어받기용 완료 표시 테이블. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `approach_done` (`tunnelId` INTEGER PRIMARY KEY NOT NULL)")
            }
        }

        /** v5: 인접도로(터널·접근도로 곁을 지나는 도로) 테이블 추가. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `adjacent_roads` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tunnelId` INTEGER NOT NULL, `pointsJson` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_adjacent_roads_tunnelId` ON `adjacent_roads` (`tunnelId`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tunnelac.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }
        }
    }
}
