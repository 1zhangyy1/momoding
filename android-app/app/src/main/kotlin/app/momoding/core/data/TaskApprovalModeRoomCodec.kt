package app.momoding.core.data

import androidx.room.TypeConverter
import app.momoding.core.policy.TaskApprovalMode

class TaskApprovalModeRoomCodec {
    @TypeConverter
    fun encode(value: TaskApprovalMode): String = value.persistedValue

    @TypeConverter
    fun decode(value: String): TaskApprovalMode = TaskApprovalMode.fromPersistedFailClosed(value)
}
