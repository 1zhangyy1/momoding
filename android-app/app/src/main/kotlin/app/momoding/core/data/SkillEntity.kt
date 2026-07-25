package app.momoding.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "skills",
    indices = [Index(value = ["name"], unique = true)],
)
data class SkillEntity(
    @PrimaryKey val skillId: String,
    val source: String,
    val name: String,
    val description: String,
    val content: String,
    val contentSha256: String,
    val disableModelInvocation: Boolean,
    val enabled: Boolean,
    val availability: String,
    val diagnosticCode: String?,
    val diagnosticMessage: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY name")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills ORDER BY name")
    fun all(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE name = :name")
    fun byName(name: String): SkillEntity?

    @Query("SELECT COUNT(*) FROM skills")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: SkillEntity)

    @Update
    fun update(entity: SkillEntity): Int

    @Query("DELETE FROM skills WHERE skillId = :skillId")
    fun delete(skillId: String): Int
}
