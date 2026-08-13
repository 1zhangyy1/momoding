package app.momoding.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(
    tableName = "extension_package_files",
    primaryKeys = ["packageId", "relativePath"],
    foreignKeys = [
        ForeignKey(
            entity = ExtensionPackageEntity::class,
            parentColumns = ["packageId"],
            childColumns = ["packageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("packageId")],
)
data class ExtensionPackageFileEntity(
    val packageId: String,
    val relativePath: String,
    val mimeType: String,
    val byteSize: Long,
    val contentSha256: String,
    val content: ByteArray,
)

@Dao
interface ExtensionPackageFileDao {
    @Query(
        "SELECT * FROM extension_package_files WHERE packageId = :packageId ORDER BY relativePath",
    )
    fun files(packageId: String): List<ExtensionPackageFileEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(files: List<ExtensionPackageFileEntity>)

    @Query("DELETE FROM extension_package_files WHERE packageId = :packageId")
    fun deleteForPackage(packageId: String): Int
}
