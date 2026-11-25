package com.openfolio.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

data class AssetClassAllocationRow(
    val assetClass: AssetClass,
    val totalAmount: Double,
)

data class DailyNetWorthDelta(
    val tradeDate: Long,
    val delta: Double,
)

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("DELETE FROM txn WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM txn WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): TransactionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM txn WHERE fingerprint = :fingerprint)")
    suspend fun containsFingerprint(fingerprint: String): Boolean

    @Query("SELECT fingerprint FROM txn WHERE fingerprint IN (:fingerprints)")
    suspend fun getExistingFingerprints(fingerprints: List<String>): List<String>

    @Query("SELECT * FROM txn WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM txn ORDER BY tradeDate DESC, createdAt DESC")
    fun getAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM txn")
    suspend fun getAllOnce(): List<TransactionEntity>

    @Query("SELECT * FROM txn WHERE assetId = :assetId")
    suspend fun getByAssetIdOnce(assetId: String): List<TransactionEntity>

    @Query("SELECT * FROM txn WHERE assetId = :assetId ORDER BY tradeDate DESC, createdAt DESC")
    fun getByAssetId(assetId: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM txn WHERE accountId = :accountId ORDER BY tradeDate DESC, createdAt DESC")
    fun getByAccountId(accountId: String): Flow<List<TransactionEntity>>

    @Query("SELECT SUM(CAST(grossAmount AS REAL)) FROM txn WHERE type IN ('BUY', 'TRANSFER_IN', 'INTEREST', 'DIVIDEND')")
    fun getTotalAssetsFlow(): Flow<Double?>

    @Query("SELECT SUM(CAST(grossAmount AS REAL)) FROM txn WHERE type IN ('FEE', 'TAX')")
    fun getTotalLiabilitiesFlow(): Flow<Double?>

    @Query("SELECT COUNT(*) FROM txn")
    fun getTotalTransactionCount(): Flow<Int>

    @Query("SELECT * FROM txn ORDER BY createdAt DESC, tradeDate DESC LIMIT :limit")
    fun getRecentTransactions(limit: Int = 5): Flow<List<TransactionEntity>>

    @Query("""
        SELECT COALESCE(a.assetClass, 'STOCK') AS assetClass, 
               SUM(CAST(t.grossAmount AS REAL)) AS totalAmount 
        FROM txn t 
        LEFT JOIN asset a ON t.assetId = a.id 
        WHERE t.type IN ('BUY', 'DIVIDEND', 'INTEREST') 
        GROUP BY COALESCE(a.assetClass, 'STOCK')
        HAVING SUM(CAST(t.grossAmount AS REAL)) > 0
        ORDER BY totalAmount DESC
    """)
    fun getAssetClassAllocations(): Flow<List<AssetClassAllocationRow>>

    @Query("""
        SELECT tradeDate,
               SUM(CASE 
                   WHEN type IN ('BUY', 'TRANSFER_IN', 'INTEREST', 'DIVIDEND', 'BONUS') THEN CAST(grossAmount AS REAL)
                   WHEN type IN ('SELL', 'TRANSFER_OUT', 'FEE', 'TAX', 'CHARGE') THEN -CAST(grossAmount AS REAL)
                   ELSE 0 
               END) AS delta
        FROM txn
        GROUP BY tradeDate
        ORDER BY tradeDate ASC
    """)
    fun getDailyNetWorthDeltas(): Flow<List<DailyNetWorthDelta>>
}
