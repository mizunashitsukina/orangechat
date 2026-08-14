/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v25 -> v26: 新增会话文件夹分组（助手内分组）。
 *
 * - 新建 conversation_folder 表（文件夹元数据，按助手分组）
 * - conversationentity 增加 folder_id 列（默认空串，表示未归类）
 *
 * 不使用外键：删除文件夹时由 Repository 层手动清空归属会话的 folder_id，
 * 避免级联删除误删会话。
 */
object Migration_25_26 : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val folderIdColumn = db.query("PRAGMA table_info(`conversationentity`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val defaultValueIndex = cursor.getColumnIndexOrThrow("dflt_value")
            val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")

            var existingColumn: ExistingColumn? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex).equals(FOLDER_ID_COLUMN, ignoreCase = true)) {
                    existingColumn = ExistingColumn(
                        declaredType = cursor.getString(typeIndex),
                        notNull = cursor.getInt(notNullIndex) == 1,
                        defaultValue = if (cursor.isNull(defaultValueIndex)) {
                            null
                        } else {
                            cursor.getString(defaultValueIndex)
                        },
                        primaryKeyPosition = cursor.getInt(primaryKeyIndex)
                    )
                    break
                }
            }
            existingColumn
        }

        if (folderIdColumn != null && !folderIdColumn.isCompatible()) {
            throw IllegalStateException("Existing folder column is incompatible with database schema v26")
        }

        // 新建文件夹表
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_folder` (
                `id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `sort_index` INTEGER NOT NULL DEFAULT 0,
                `create_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversation_folder_assistant_id` " +
                "ON `conversation_folder` (`assistant_id`)"
        )

        // RikkaHub 2.4.8 的 v24 数据库已经包含兼容的 folder_id；仅在缺少时新增。
        if (folderIdColumn == null) {
            db.execSQL("ALTER TABLE conversationentity ADD COLUMN folder_id TEXT NOT NULL DEFAULT ''")
        }
    }

    private data class ExistingColumn(
        val declaredType: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int
    ) {
        fun isCompatible(): Boolean =
            declaredType.trim().equals("TEXT", ignoreCase = true) &&
                notNull &&
                defaultValue?.trim() == "''" &&
                primaryKeyPosition == 0
    }

    private const val FOLDER_ID_COLUMN = "folder_id"
}
