// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import helium314.keyboard.sellby.data.dao.AutoTextDao
import helium314.keyboard.sellby.data.dao.CustomerDao
import helium314.keyboard.sellby.data.dao.ExpeditionDao
import helium314.keyboard.sellby.data.dao.OrderDao
import helium314.keyboard.sellby.data.dao.PaymentMethodDao
import helium314.keyboard.sellby.data.dao.ProductDao
import helium314.keyboard.sellby.data.entity.AutoText
import helium314.keyboard.sellby.data.entity.Customer
import helium314.keyboard.sellby.data.entity.Expedition
import helium314.keyboard.sellby.data.entity.Order
import helium314.keyboard.sellby.data.entity.OrderItem
import helium314.keyboard.sellby.data.entity.OrderStatus
import helium314.keyboard.sellby.data.entity.PaymentMethod
import helium314.keyboard.sellby.data.entity.Product
import helium314.keyboard.sellby.data.entity.ProductVariation
import helium314.keyboard.sellby.util.ChannelConverters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OrderStatusConverters {
    @TypeConverter
    fun fromStatus(value: OrderStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): OrderStatus = OrderStatus.valueOf(value)
}

// Sellby: adds Customer.channel/Order.channel (WhatsApp/WhatsApp Business/Telegram/Instagram
// picker feature) as literal ADD COLUMN statements with a default - both existing tables gain
// the column, no data loss for already-installed test devices (every pre-existing row reads as
// channel=WHATSAPP, matching the app's exact pre-existing WhatsApp-only behavior).
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE customer ADD COLUMN channel TEXT NOT NULL DEFAULT 'WHATSAPP'")
        db.execSQL("ALTER TABLE order_record ADD COLUMN channel TEXT NOT NULL DEFAULT 'WHATSAPP'")
    }
}

@Database(
    entities = [Product::class, ProductVariation::class, Customer::class, PaymentMethod::class,
        Expedition::class, AutoText::class, Order::class, OrderItem::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(OrderStatusConverters::class, ChannelConverters::class)
abstract class SellbyDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun customerDao(): CustomerDao
    abstract fun paymentMethodDao(): PaymentMethodDao
    abstract fun expeditionDao(): ExpeditionDao
    abstract fun autoTextDao(): AutoTextDao
    abstract fun orderDao(): OrderDao

    companion object {
        @Volatile private var instance: SellbyDatabase? = null

        fun getInstance(context: Context): SellbyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, SellbyDatabase::class.java, "sellby.db"
                ).addMigrations(MIGRATION_1_2).addCallback(seedCallback).build().also { instance = it }
            }

        // Seed data ported verbatim from sellby_keyboard.dart project (acuan tunggal):
        // OngkirData.expeditions (all enabled by default) and auto_text_panel.dart's 3 default
        // entries (#nama-toko replaces Dart's $activeStore interpolation - Sellby's own
        // placeholder syntax from the PRD, resolved later by the Template Pesan feature).
        private val seedCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    val database = instance ?: return@launch
                    database.expeditionDao().seedIfAbsent(
                        ExpeditionCatalog.all.map { Expedition(id = it.id, isEnabled = true) }
                    )
                    AutoText.defaultSeedRows().forEach { database.autoTextDao().upsert(it) }
                }
            }
        }
    }
}
