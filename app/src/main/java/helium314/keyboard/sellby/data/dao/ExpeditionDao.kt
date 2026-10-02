// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import helium314.keyboard.sellby.data.entity.Expedition
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpeditionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seedIfAbsent(expeditions: List<Expedition>)

    @Query("SELECT * FROM expedition")
    fun getAll(): Flow<List<Expedition>>

    @Query("SELECT id FROM expedition WHERE isEnabled = 1")
    fun getEnabledIds(): Flow<List<String>>

    @Query("UPDATE expedition SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** Cleans up rows for couriers removed from ExpeditionCatalog (e.g. Rara/JDL) - a row that was
     *  seeded before the catalog entry existed would otherwise sit orphaned in the table forever
     *  (harmless since the UI only ever iterates ExpeditionCatalog.all filtered by this table, but
     *  not truly "removed from the database" as requested). */
    @Query("DELETE FROM expedition WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
