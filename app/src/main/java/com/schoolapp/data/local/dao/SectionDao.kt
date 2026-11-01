package com.schoolapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.schoolapp.data.local.entity.SectionEntity

@Dao
interface SectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(section: SectionEntity)

    @Query("SELECT * FROM sections ORDER BY grade ASC, sectionName ASC")
    suspend fun getAll(): List<SectionEntity>

    @Query("SELECT * FROM sections WHERE sectionId = :sectionId LIMIT 1")
    suspend fun getById(sectionId: String): SectionEntity?

    @Query("DELETE FROM sections WHERE sectionId = :sectionId")
    suspend fun deleteById(sectionId: String)

    // Inbound sync (data/sync/RoomInboundLocal). Ids compare with SQLite's BINARY collation,
    // the same byte order Firestore uses for document ids.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<SectionEntity>)

    @Query("SELECT * FROM sections")
    suspend fun getAllRows(): List<SectionEntity>

    @Query("SELECT * FROM sections WHERE sectionId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<SectionEntity>

    @Query(
        "SELECT * FROM sections WHERE (:afterId IS NULL OR sectionId > :afterId) " +
            "AND (:throughId IS NULL OR sectionId <= :throughId)",
    )
    suspend fun getInIdRange(afterId: String?, throughId: String?): List<SectionEntity>

    @Query("DELETE FROM sections WHERE sectionId IN (:ids)")
    suspend fun deleteAllByIds(ids: List<String>)
}
