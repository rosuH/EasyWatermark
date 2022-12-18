package me.rosuh.easywatermark.data.model.entity

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import java.util.Date

@Entity
@Parcelize
data class Template(
    @PrimaryKey(autoGenerate = true) var id: Int,
    @ColumnInfo(name = "content") val content: String?,
    @ColumnInfo(name = "creation_date") var creationDate: Date?,
    @ColumnInfo(name = "last_modified_date") var lastModifiedDate: Date?,
) : Parcelable {
//    @PrimaryKey(autoGenerate = true)
//    var id: Int = 0
//    private companion object : Parceler<Template> {
//        override fun Template.write(parcel: Parcel, flags: Int) {
//            parcel.writeInt(id)
//            parcel.writeString(content)
//            parcel.writeSerializable(creationDate)
//            parcel.writeSerializable(lastModifiedDate)
//        }
//
//        override fun create(parcel: Parcel): Template {
//            // Custom read implementation
//            val id = parcel.readInt()
//            return Template(
//                parcel.readString(),
//                parcel.readSerializable() as Date,
//                parcel.readSerializable() as Date
//            ).apply {
//                this.id = id
//            }
//        }
//    }
}
