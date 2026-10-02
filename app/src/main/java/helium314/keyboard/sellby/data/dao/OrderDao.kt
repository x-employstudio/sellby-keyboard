// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import helium314.keyboard.sellby.data.entity.Order
import helium314.keyboard.sellby.data.entity.OrderItem
import helium314.keyboard.sellby.data.entity.OrderStatus
import kotlinx.coroutines.flow.Flow

data class OrderWithItems(
    @androidx.room.Embedded val order: Order,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "orderId")
    val items: List<OrderItem>,
)

// Sellby companion app Dashboard (Fase 5): aggregate counts/revenue for a date range. Only
// LUNAS/PROSES/SELESAI count toward totalSales/totalRevenue (mirrors dashboard_page.dart's
// growth/omzet calculation, which excludes PENDING orders from revenue).
data class PeriodStats(
    val pendingCount: Int,
    val lunasCount: Int,
    val prosesCount: Int,
    val selesaiCount: Int,
    val totalSales: Int,
    val totalRevenue: Double,
)

// Sellby companion app Dashboard (Data Pelanggan popup): per-customer lifetime order count/spend,
// joined in Kotlin against CustomerDao.getAll() by matching customerPhone == Customer.phone (no FK
// exists between order_record and customer). PENDING is excluded (not yet paid), but ARSIP IS
// included - unlike getPeriodStats()'s LUNAS/PROSES/SELESAI-only convention - since the only way an
// order becomes ARSIP is by archiving an already-completed SELESAI order, and a lifetime "total
// spent" figure shouldn't drop just because the shop tidied up its Status list.
data class CustomerOrderAggregate(
    val customerPhone: String,
    val orderCount: Int,
    val totalSpent: Double,
)

@Dao
interface OrderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(order: Order)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<OrderItem>)

    @Update
    suspend fun update(order: Order)

    @Delete
    suspend fun delete(order: Order)

    @Query("UPDATE order_record SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: OrderStatus)

    @Transaction
    @Query("SELECT * FROM order_record ORDER BY dateMillis DESC")
    fun getAllWithItems(): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM order_record WHERE status = :status ORDER BY dateMillis DESC")
    fun getByStatus(status: OrderStatus): Flow<List<OrderWithItems>>

    @Query("SELECT COUNT(*) FROM order_record WHERE status = :status")
    fun countByStatus(status: OrderStatus): Flow<Int>

    // Reset Semua Transaksi (status_panel.dart's _handleResetAllOrders): deletes every row
    // regardless of status, including ARSIP - matches Flutter's prefs.remove(prefKeyOrders), which
    // clears the single flat list wholesale. order_item cascades via the existing FK.
    @Query("DELETE FROM order_record")
    suspend fun deleteAll()

    // Sellby companion app Dashboard (Fase 5): 1 aggregate query for a [startMillis, endMillis]
    // range, called twice (selected range + equally-long preceding range) to compute growth%,
    // mirroring dashboard_page.dart's _loadDashboardData.
    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pendingCount,
            COALESCE(SUM(CASE WHEN status = 'LUNAS' THEN 1 ELSE 0 END), 0) AS lunasCount,
            COALESCE(SUM(CASE WHEN status = 'PROSES' THEN 1 ELSE 0 END), 0) AS prosesCount,
            COALESCE(SUM(CASE WHEN status = 'SELESAI' THEN 1 ELSE 0 END), 0) AS selesaiCount,
            COALESCE(SUM(CASE WHEN status IN ('LUNAS', 'PROSES', 'SELESAI') THEN 1 ELSE 0 END), 0) AS totalSales,
            COALESCE(SUM(CASE WHEN status IN ('LUNAS', 'PROSES', 'SELESAI') THEN totalAmount ELSE 0 END), 0.0) AS totalRevenue
        FROM order_record
        WHERE dateMillis BETWEEN :startMillis AND :endMillis
    """)
    fun getPeriodStats(startMillis: Long, endMillis: Long): Flow<PeriodStats>

    @Query("""
        SELECT customerPhone,
               COUNT(*) AS orderCount,
               COALESCE(SUM(totalAmount), 0.0) AS totalSpent
        FROM order_record
        WHERE status != 'PENDING'
        GROUP BY customerPhone
    """)
    fun getCustomerAggregates(): Flow<List<CustomerOrderAggregate>>
}
