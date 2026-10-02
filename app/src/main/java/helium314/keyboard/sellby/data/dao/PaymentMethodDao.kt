// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import helium314.keyboard.sellby.data.entity.PaymentMethod
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentMethodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(paymentMethod: PaymentMethod)

    @Update
    suspend fun update(paymentMethod: PaymentMethod)

    @Delete
    suspend fun delete(paymentMethod: PaymentMethod)

    @Query("SELECT * FROM payment_method ORDER BY name")
    fun getAll(): Flow<List<PaymentMethod>>

    @Query("SELECT * FROM payment_method WHERE isActive = 1 ORDER BY name")
    fun getActive(): Flow<List<PaymentMethod>>

    @Query("SELECT * FROM payment_method WHERE id = :id")
    suspend fun getById(id: String): PaymentMethod?

    // Defensive first-run seed (Tunai + QRIS, non-deletable defaults) - same IGNORE-conflict pattern
    // as AutoTextDao/ExpeditionDao's seedIfAbsent, so re-running it (e.g. after Settings' "Hapus
    // Semua Data") restores the 2 defaults without touching any row a user already edited.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seedIfAbsent(paymentMethods: List<PaymentMethod>)
}
