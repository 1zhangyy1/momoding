package app.momoding.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "extension_packages")
data class ExtensionPackageEntity(
    @PrimaryKey val packageId: String,
    val name: String,
    val version: String,
    val description: String,
    val manifestCanonicalJson: String,
    val packageDigest: String,
    val enabled: Boolean,
    val fileCount: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Dao
interface ExtensionPackageDao {
    @Query("SELECT * FROM extension_packages ORDER BY name, packageId")
    fun observeAll(): Flow<List<ExtensionPackageEntity>>

    @Query("SELECT * FROM extension_packages ORDER BY packageId")
    fun all(): List<ExtensionPackageEntity>

    @Query("SELECT * FROM extension_packages WHERE packageId = :packageId")
    fun byId(packageId: String): ExtensionPackageEntity?

    @Query("SELECT COUNT(*) FROM extension_packages")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: ExtensionPackageEntity)

    @Update
    fun update(entity: ExtensionPackageEntity): Int

    @Query("DELETE FROM extension_packages WHERE packageId = :packageId")
    fun delete(packageId: String): Int
}
