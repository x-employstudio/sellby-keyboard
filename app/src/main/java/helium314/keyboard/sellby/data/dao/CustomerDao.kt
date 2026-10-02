// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import helium314.keyboard.sellby.data.entity.Customer
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(customer: Customer)

    @Update
    suspend fun update(customer: Customer)

    @Delete
    suspend fun delete(customer: Customer)

    @Query("SELECT * FROM customer ORDER BY name")
    fun getAll(): Flow<List<Customer>>

    @Query("SELECT * FROM customer WHERE id = :id")
    suspend fun getById(id: String): Customer?
}
