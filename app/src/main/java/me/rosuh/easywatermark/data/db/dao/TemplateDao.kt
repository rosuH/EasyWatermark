package me.rosuh.easywatermark.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.rosuh.easywatermark.data.model.entity.Template

@Dao
interface TemplateDao {

    @Query("SELECT * FROM template ORDER BY creation_date DESC")
    fun getAllTemplate(): Flow<List<Template>>

    @Insert
    suspend fun insertTemplate(template: Template)

    @Delete
    suspend fun deleteTemplate(template: Template)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateTemplate(template: Template)
}