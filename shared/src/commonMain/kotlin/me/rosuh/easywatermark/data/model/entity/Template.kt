package me.rosuh.easywatermark.data.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Instant

@Entity
data class Template(
    @PrimaryKey(autoGenerate = true) var id: Int,
    @ColumnInfo(name = "content") val content: String?,
    @ColumnInfo(name = "creation_date") var creationDate: Instant?,
    @ColumnInfo(name = "last_modified_date") var lastModifiedDate: Instant?,
)
