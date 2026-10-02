// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import helium314.keyboard.sellby.data.entity.Product
import helium314.keyboard.sellby.data.entity.ProductVariation
import kotlinx.coroutines.flow.Flow

data class ProductWithVariations(
    @androidx.room.Embedded val product: Product,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "productId")
    val variations: List<ProductVariation>,
)

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(product: Product)

    @Update
    suspend fun update(product: Product)

    @Delete
    suspend fun delete(product: Product)

    @Query("SELECT * FROM product ORDER BY name")
    fun getAll(): Flow<List<Product>>

    @Query("SELECT * FROM product WHERE id = :id")
    suspend fun getById(id: String): Product?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVariations(variations: List<ProductVariation>)

    @Query("DELETE FROM product_variation WHERE productId = :productId")
    suspend fun deleteVariationsOf(productId: String)

    @Transaction
    @Query("SELECT * FROM product ORDER BY name")
    fun getAllWithVariations(): Flow<List<ProductWithVariations>>

    // Reset Semua Produk (produk_panel.dart's _handleResetAllProducts): deletes every row.
    // product_variation cascades via the existing FK (practically always empty for this panel's
    // flow - variations are flattened into separate flat Product rows on save, never actually
    // stored relationally here).
    @Query("DELETE FROM product")
    suspend fun deleteAll()
}
