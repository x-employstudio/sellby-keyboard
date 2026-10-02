// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import helium314.keyboard.sellby.data.entity.AutoText
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoTextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(autoText: AutoText)

    // Defensive re-seed of the 3 factory defaults (see AutoTextSuggestionEngine.startWatching) -
    // IGNORE means an existing row (matched by id) is left untouched, so a user's edit to a
    // default's shortcut/message is never clobbered; this only fills in rows that are missing.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seedIfAbsent(autoTexts: List<AutoText>)

    // Upgrades an already-seeded factory row to newer factory text, but only while its message is
    // still exactly the old factory text - a row the user has edited no longer matches and is left alone.
    @Query("UPDATE auto_text SET message = :newMessage WHERE id = :id AND message = :oldMessage")
    suspend fun upgradeFactoryMessage(id: String, oldMessage: String, newMessage: String)

    @Update
    suspend fun update(autoText: AutoText)

    @Delete
    suspend fun delete(autoText: AutoText)

    @Query("SELECT * FROM auto_text ORDER BY shortcut")
    fun getAll(): Flow<List<AutoText>>

    // Fase 4 toolbar mode-2 (Auto-Text suggestion strip) will use this to match typed text.
    @Query("SELECT * FROM auto_text WHERE shortcut = :shortcut LIMIT 1")
    suspend fun findByShortcut(shortcut: String): AutoText?

    // Reset Semua Auto-Text (auto_text_panel.dart's _handleResetAllAutoTexts): wipes everything,
    // caller re-inserts AutoText.defaultSeedRows() afterwards to restore the 3 factory defaults.
    @Query("DELETE FROM auto_text")
    suspend fun deleteAll()
}
