// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Fields match sellby_keyboard.dart's ProductModel exactly (produk_panel.dart) - acuan tunggal.
@Entity(tableName = "product")
data class Product(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val originalPrice: Double,
    val discountAmount: Double = 0.0,
    val hasVariations: Boolean = false,
    val stock: Int = 0,
)

// Fields match ProductVariation in produk_panel.dart.
@Entity(
    tableName = "product_variation",
    foreignKeys = [ForeignKey(
        entity = Product::class,
        parentColumns = ["id"],
        childColumns = ["productId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("productId")],
)
data class ProductVariation(
    @PrimaryKey val id: String,
    val productId: String,
    val name: String,
    val stock: Int,
)
