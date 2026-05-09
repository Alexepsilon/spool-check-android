package com.spoolcheck.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryDao {
    @Query("SELECT * FROM deliveries ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<Delivery>>

    @Query("SELECT * FROM deliveries WHERE id = :id")
    suspend fun get(id: String): Delivery?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: Delivery)

    @Query("DELETE FROM deliveries WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MasterItemDao {
    @Query("SELECT * FROM master_items WHERE deliveryId = :deliveryId ORDER BY drawing, spool")
    fun observeForDelivery(deliveryId: String): Flow<List<MasterItem>>

    @Query("SELECT * FROM master_items WHERE deliveryId = :deliveryId ORDER BY drawing, spool")
    suspend fun listForDelivery(deliveryId: String): List<MasterItem>

    @Query("SELECT * FROM master_items WHERE deliveryId = :deliveryId AND drawing = :drawing AND spool = :spool LIMIT 1")
    suspend fun findByKey(deliveryId: String, drawing: String, spool: String): MasterItem?

    @Query("SELECT * FROM master_items WHERE deliveryId = :deliveryId AND drawing = :drawing")
    suspend fun findByDrawing(deliveryId: String, drawing: String): List<MasterItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MasterItem>)

    @Update
    suspend fun update(item: MasterItem)

    @Query("DELETE FROM master_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM master_items WHERE deliveryId = :deliveryId")
    suspend fun deleteForDelivery(deliveryId: String)
}

@Dao
interface ScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(scan: Scan)

    @Query("SELECT * FROM scans WHERE deliveryId = :deliveryId ORDER BY timestamp DESC")
    suspend fun listForDelivery(deliveryId: String): List<Scan>

    @Query("DELETE FROM scans WHERE deliveryId = :deliveryId")
    suspend fun deleteForDelivery(deliveryId: String)
}

@Dao
interface UnchartedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: UnchartedItem)

    @Query("SELECT * FROM uncharted WHERE deliveryId = :deliveryId ORDER BY timestamp DESC")
    fun observeForDelivery(deliveryId: String): Flow<List<UnchartedItem>>

    @Query("SELECT * FROM uncharted WHERE deliveryId = :deliveryId ORDER BY timestamp DESC")
    suspend fun listForDelivery(deliveryId: String): List<UnchartedItem>

    @Query("SELECT COUNT(*) FROM uncharted WHERE deliveryId = :deliveryId")
    fun observeCountForDelivery(deliveryId: String): Flow<Int>

    @Update
    suspend fun update(item: UnchartedItem)

    @Query("DELETE FROM uncharted WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM uncharted WHERE deliveryId = :deliveryId")
    suspend fun deleteForDelivery(deliveryId: String)
}
