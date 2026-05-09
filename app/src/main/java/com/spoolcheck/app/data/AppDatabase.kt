package com.spoolcheck.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Delivery::class,
        MasterItem::class,
        Scan::class,
        UnchartedItem::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deliveries(): DeliveryDao
    abstract fun items(): MasterItemDao
    abstract fun scans(): ScanDao
    abstract fun uncharted(): UnchartedDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "spool-check.db",
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
