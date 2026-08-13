package app.momoding.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(
    tableName = "skill_package_files",
    primaryKeys = ["skillId", "relativePath"],
    foreignKeys = [
        ForeignKey(
            entity = SkillEntity::class,
            parentColumns = ["skillId"],
            childColumns = ["skillId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("skillId")],
)
data class SkillPackageFileEntity(
    val skillId: String,
    val relativePath: String,
    val mimeType: String,
    val byteSize: Long,
    val contentSha256: String,
    val content: ByteArray,
)

data class SkillPackageFileMetadata(
    val relativePath: String,
    val mimeType: String,
    val byteSize: Long,
    val contentSha256: String,
)

@Dao
interface SkillPackageFileDao {
    @Query(
        """
        SELECT relativePath, mimeType, byteSize, contentSha256
        FROM skill_package_files
        WHERE skillId = :skillId
        ORDER BY relativePath
        """,
    )
    fun metadataForSkill(skillId: String): List<SkillPackageFileMetadata>

    @Query(
        """
        SELECT content
        FROM skill_package_files
        WHERE skillId = :skillId AND relativePath = :relativePath
        """,
    )
    fun content(skillId: String, relativePath: String): ByteArray?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(files: List<SkillPackageFileEntity>)

    @Query("DELETE FROM skill_package_files WHERE skillId = :skillId")
    fun deleteForSkill(skillId: String): Int
}
