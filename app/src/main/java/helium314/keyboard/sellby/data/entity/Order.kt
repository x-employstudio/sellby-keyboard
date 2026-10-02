// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import helium314.keyboard.sellby.util.Channel

// Matches sellby_keyboard.dart's OrderStatus enum exactly (status_panel.dart) - acuan tunggal.
enum class OrderStatus {
    PENDING, LUNAS, PROSES, SELESAI, ARSIP
}

// Fields match sellby_keyboard.dart's StatusOrderItem exactly (status_panel.dart), except dateStr
// (a formatted String in Dart) is stored as a Long epoch-millis timestamp here - a plain DB
// primitive concern, not a design choice the Flutter reference governs; format for display when
// the Status panel is built.
@Entity(tableName = "order_record")
data class Order(
    @PrimaryKey val id: String,
    val customerName: String,
    val customerPhone: String,
    val dateMillis: Long,
    val productCount: Int,
    val productsSummary: String,
    val totalAmount: Double,
    val status: OrderStatus,
    val address: String = "-",
    val expedition: String = "-",
    val notes: String = "-",
    val paymentMethod: String = "-",
    // Sellby-only addition (no Flutter equivalent) - denormalized like customerName/customerPhone
    // above, snapshotted at invoice-creation time so Status panel actions (which only read Order,
    // not Customer) know which channel to redirect messages to.
    val channel: Channel = Channel.WHATSAPP,
)

// Matches OrderProductSummary in status_panel.dart.
@Entity(
    tableName = "order_item",
    foreignKeys = [ForeignKey(
        entity = Order::class,
        parentColumns = ["id"],
        childColumns = ["orderId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("orderId")],
)
data class OrderItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: String,
    val name: String,
    val quantity: Int,
)
