package app.momoding.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(
    tableName = "extension_package_state",
    primaryKeys = ["packageId", "stateKey"],
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
data class ExtensionPackageStateEntity(
    val packageId: String,
    val stateKey: String,
    val valueJson: String,
    val updatedAtMillis: Long,
)

@Dao
interface ExtensionPackageStateDao {
    @Query(
        "SELECT * FROM extension_package_state WHERE packageId = :packageId ORDER BY stateKey",
    )
    fun entries(packageId: String): List<ExtensionPackageStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entries: List<ExtensionPackageStateEntity>)

    @Query("DELETE FROM extension_package_state WHERE packageId = :packageId")
    fun deleteForPackage(packageId: String): Int
}
