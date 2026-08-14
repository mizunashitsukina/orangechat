/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale

/**
 * v25 -> v26: 新增会话文件夹分组（助手内分组）。
 *
 * RikkaHub 2.4.8 的 v24 数据库已经包含文件夹列，并额外保存了每个对话的
 * Mode Injection、Lorebook 与工作目录关联。其备份进入 OrangeChat 的 v25 后，
 * 这里会把尚无等价运行时语义的三项数据完整保存到版本化兼容表，再将会话表
 * 收敛为 OrangeChat v26 的精确 schema。兼容表随数据库备份保留，
 * 供后续功能迁移使用。
 */
object Migration_25_26 : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        when (detectSourceSchema(db)) {
            SourceSchema.ORANGECHAT_V25 -> migrateOrangeChatV25(db)
            SourceSchema.RIKKAHUB_248 -> migrateRikkaHub248(db)
        }
    }

    private fun migrateOrangeChatV25(db: SupportSQLiteDatabase) {
        createConversationFolderObjects(db)
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN folder_id TEXT NOT NULL DEFAULT ''")
    }

    private fun migrateRikkaHub248(db: SupportSQLiteDatabase) {
        check(!tableExists(db, LEGACY_METADATA_TABLE)) {
            "Legacy conversation compatibility storage already exists"
        }

        val conversationCount = queryLong(db, "SELECT COUNT(*) FROM `ConversationEntity`")
        val messageCount = queryLong(db, "SELECT COUNT(*) FROM `message_node`")

        db.execSQL(
            """
            CREATE TABLE `$LEGACY_METADATA_TABLE` (
                `conversation_id` TEXT NOT NULL,
                `mode_injection_ids` TEXT NOT NULL,
                `lorebook_ids` TEXT NOT NULL,
                `workspace_cwd` TEXT NOT NULL,
                PRIMARY KEY(`conversation_id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `$LEGACY_METADATA_TABLE` (
                `conversation_id`, `mode_injection_ids`, `lorebook_ids`, `workspace_cwd`
            )
            SELECT `id`, `mode_injection_ids`, `lorebook_ids`, `workspace_cwd`
            FROM `ConversationEntity`
            """.trimIndent()
        )
        requireExactCopy(
            db = db,
            expectedCount = conversationCount,
            copiedTable = LEGACY_METADATA_TABLE,
            mismatchQuery =
                """
                SELECT COUNT(*)
                FROM `ConversationEntity` AS source
                LEFT JOIN `$LEGACY_METADATA_TABLE` AS copied
                    ON copied.`conversation_id` = source.`id`
                WHERE copied.`conversation_id` IS NULL
                    OR copied.`mode_injection_ids` IS NOT source.`mode_injection_ids`
                    OR copied.`lorebook_ids` IS NOT source.`lorebook_ids`
                    OR copied.`workspace_cwd` IS NOT source.`workspace_cwd`
                """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TEMP TABLE `$MESSAGE_BACKUP_TABLE` (
                `id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `node_index` INTEGER NOT NULL,
                `messages` TEXT NOT NULL,
                `select_index` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `$MESSAGE_BACKUP_TABLE`
                (`id`, `conversation_id`, `node_index`, `messages`, `select_index`)
            SELECT `id`, `conversation_id`, `node_index`, `messages`, `select_index`
            FROM `message_node`
            """.trimIndent()
        )
        requireExactCopy(
            db = db,
            expectedCount = messageCount,
            copiedTable = MESSAGE_BACKUP_TABLE,
            mismatchQuery = messageMismatchQuery("message_node", MESSAGE_BACKUP_TABLE)
        )

        db.execSQL(CREATE_TARGET_CONVERSATION_TABLE)
        db.execSQL(
            """
            INSERT INTO `$TARGET_CONVERSATION_TABLE` (
                `id`, `assistant_id`, `title`, `nodes`, `create_at`, `update_at`,
                `suggestions`, `is_pinned`, `custom_system_prompt`, `folder_id`
            )
            SELECT
                `id`, `assistant_id`, `title`, `nodes`, `create_at`, `update_at`,
                `suggestions`, `is_pinned`, `custom_system_prompt`, `folder_id`
            FROM `ConversationEntity`
            """.trimIndent()
        )
        requireExactCopy(
            db = db,
            expectedCount = conversationCount,
            copiedTable = TARGET_CONVERSATION_TABLE,
            mismatchQuery = conversationMismatchQuery("ConversationEntity", TARGET_CONVERSATION_TABLE)
        )

        // API 26 的 SQLite 不支持 DROP COLUMN。先备份并移除带外键的子表，避免
        // 替换父表时触发级联删除，再按原 schema 原样恢复消息节点。
        db.execSQL("DROP TABLE `message_node`")
        db.execSQL("DROP TABLE `ConversationEntity`")
        db.execSQL("ALTER TABLE `$TARGET_CONVERSATION_TABLE` RENAME TO `ConversationEntity`")
        createMessageNodeTable(db)
        db.execSQL(
            """
            INSERT INTO `message_node`
                (`id`, `conversation_id`, `node_index`, `messages`, `select_index`)
            SELECT `id`, `conversation_id`, `node_index`, `messages`, `select_index`
            FROM `$MESSAGE_BACKUP_TABLE`
            """.trimIndent()
        )
        requireExactCopy(
            db = db,
            expectedCount = messageCount,
            copiedTable = "message_node",
            mismatchQuery = messageMismatchQuery(MESSAGE_BACKUP_TABLE, "message_node")
        )
        db.query("PRAGMA foreign_key_check").use { cursor ->
            check(cursor.count == 0) {
                "Foreign key validation failed after conversation migration"
            }
        }
        db.execSQL("DROP TABLE `$MESSAGE_BACKUP_TABLE`")
    }

    private fun detectSourceSchema(db: SupportSQLiteDatabase): SourceSchema {
        val columns = readColumns(db, "conversationentity")
        return when {
            columns.matches(ORANGECHAT_V25_COLUMNS) -> SourceSchema.ORANGECHAT_V25
            columns.matches(RIKKAHUB_248_COLUMNS) -> {
                requireRikkaHub248SourceSchema(db)
                SourceSchema.RIKKAHUB_248
            }
            else -> throw IllegalStateException("Unsupported conversation schema for migration to v26")
        }
    }

    internal fun requireRikkaHub248SourceSchema(db: SupportSQLiteDatabase) {
        check(readColumns(db, "conversationentity").matches(RIKKAHUB_248_COLUMNS)) {
            "RikkaHub conversation schema is incompatible"
        }
        validateConversationFolderObjects(db)
        validateMessageNodeTable(db)
    }

    private fun validateConversationFolderObjects(db: SupportSQLiteDatabase) {
        check(readColumns(db, "conversation_folder").matches(CONVERSATION_FOLDER_COLUMNS)) {
            "RikkaHub conversation folder schema is incompatible"
        }
        validateSingleIndex(
            db = db,
            table = "conversation_folder",
            expectedName = "index_conversation_folder_assistant_id",
            expectedColumn = "assistant_id"
        )
    }

    private fun validateMessageNodeTable(db: SupportSQLiteDatabase) {
        check(readColumns(db, "message_node").matches(MESSAGE_NODE_COLUMNS)) {
            "RikkaHub message node schema is incompatible"
        }
        validateSingleIndex(
            db = db,
            table = "message_node",
            expectedName = "index_message_node_conversation_id",
            expectedColumn = "conversation_id"
        )
        db.query("PRAGMA foreign_key_list(`message_node`)").use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst()) {
                "RikkaHub message node foreign key schema is incompatible"
            }
            check(cursor.getString(cursor.getColumnIndexOrThrow("table")).equals("ConversationEntity", true))
            check(cursor.getString(cursor.getColumnIndexOrThrow("from")).equals("conversation_id", true))
            check(cursor.getString(cursor.getColumnIndexOrThrow("to")).equals("id", true))
            check(cursor.getString(cursor.getColumnIndexOrThrow("on_update")).equals("NO ACTION", true))
            check(cursor.getString(cursor.getColumnIndexOrThrow("on_delete")).equals("CASCADE", true))
        }
    }

    private fun validateSingleIndex(
        db: SupportSQLiteDatabase,
        table: String,
        expectedName: String,
        expectedColumn: String
    ) {
        var foundExpected = false
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                if (name.startsWith("sqlite_autoindex_", ignoreCase = true)) continue
                check(name.equals(expectedName, ignoreCase = true) && cursor.getInt(uniqueIndex) == 0) {
                    "Unexpected index in migration source schema"
                }
                foundExpected = true
            }
        }
        check(foundExpected) { "Required migration source index is missing" }

        db.query("PRAGMA index_info(`$expectedName`)").use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst()) {
                "Migration source index structure is incompatible"
            }
            check(cursor.getString(cursor.getColumnIndexOrThrow("name")).equals(expectedColumn, true)) {
                "Migration source index column is incompatible"
            }
        }
    }

    private fun createConversationFolderObjects(db: SupportSQLiteDatabase) {
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
    }

    private fun createMessageNodeTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE `message_node` (
                `id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `node_index` INTEGER NOT NULL,
                `messages` TEXT NOT NULL,
                `select_index` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX `index_message_node_conversation_id` " +
                "ON `message_node` (`conversation_id`)"
        )
    }

    private fun readColumns(db: SupportSQLiteDatabase, table: String): Map<String, ExistingColumn> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val defaultValueIndex = cursor.getColumnIndexOrThrow("dflt_value")
            val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
            buildMap {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    put(
                        name.lowercase(Locale.ROOT),
                        ExistingColumn(
                            declaredType = cursor.getString(typeIndex),
                            notNull = cursor.getInt(notNullIndex) == 1,
                            defaultValue = if (cursor.isNull(defaultValueIndex)) {
                                null
                            } else {
                                cursor.getString(defaultValueIndex)
                            },
                            primaryKeyPosition = cursor.getInt(primaryKeyIndex)
                        )
                    )
                }
            }
        }

    private fun Map<String, ExistingColumn>.matches(expected: Map<String, ColumnSpec>): Boolean =
        size == expected.size && expected.all { (name, spec) ->
            get(name)?.isCompatible(spec) == true
        }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND lower(name) = lower(?)",
            arrayOf(table)
        ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) != 0L }

    private fun requireExactCopy(
        db: SupportSQLiteDatabase,
        expectedCount: Long,
        copiedTable: String,
        mismatchQuery: String
    ) {
        check(queryLong(db, "SELECT COUNT(*) FROM `$copiedTable`") == expectedCount) {
            "Database migration copy count validation failed"
        }
        check(queryLong(db, mismatchQuery) == 0L) {
            "Database migration copy content validation failed"
        }
    }

    private fun queryLong(db: SupportSQLiteDatabase, sql: String): Long = db.query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "Database migration validation query returned no result" }
        cursor.getLong(0)
    }

    private fun conversationMismatchQuery(source: String, copied: String): String =
        """
        SELECT COUNT(*)
        FROM `$source` AS source
        LEFT JOIN `$copied` AS copied ON copied.`id` = source.`id`
        WHERE copied.`id` IS NULL
            OR copied.`assistant_id` IS NOT source.`assistant_id`
            OR copied.`title` IS NOT source.`title`
            OR copied.`nodes` IS NOT source.`nodes`
            OR copied.`create_at` IS NOT source.`create_at`
            OR copied.`update_at` IS NOT source.`update_at`
            OR copied.`suggestions` IS NOT source.`suggestions`
            OR copied.`is_pinned` IS NOT source.`is_pinned`
            OR copied.`custom_system_prompt` IS NOT source.`custom_system_prompt`
            OR copied.`folder_id` IS NOT source.`folder_id`
        """.trimIndent()

    private fun messageMismatchQuery(source: String, copied: String): String =
        """
        SELECT COUNT(*)
        FROM `$source` AS source
        LEFT JOIN `$copied` AS copied ON copied.`id` = source.`id`
        WHERE copied.`id` IS NULL
            OR copied.`conversation_id` IS NOT source.`conversation_id`
            OR copied.`node_index` IS NOT source.`node_index`
            OR copied.`messages` IS NOT source.`messages`
            OR copied.`select_index` IS NOT source.`select_index`
        """.trimIndent()

    private data class ExistingColumn(
        val declaredType: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int
    ) {
        fun isCompatible(expected: ColumnSpec): Boolean =
            declaredType.trim().equals(expected.declaredType, ignoreCase = true) &&
                notNull == expected.notNull &&
                defaultValue?.trim() == expected.defaultValue &&
                primaryKeyPosition == expected.primaryKeyPosition
    }

    private data class ColumnSpec(
        val declaredType: String,
        val notNull: Boolean = true,
        val defaultValue: String? = null,
        val primaryKeyPosition: Int = 0
    )

    private enum class SourceSchema {
        ORANGECHAT_V25,
        RIKKAHUB_248
    }

    private const val LEGACY_METADATA_TABLE = "rikkahub_248_conversation_compat"
    private const val MESSAGE_BACKUP_TABLE = "migration_25_26_message_backup"
    private const val TARGET_CONVERSATION_TABLE = "migration_25_26_conversation"

    private val ORANGECHAT_V25_COLUMNS = mapOf(
        "id" to ColumnSpec("TEXT", primaryKeyPosition = 1),
        "assistant_id" to ColumnSpec("TEXT", defaultValue = "'0950e2dc-9bd5-4801-afa3-aa887aa36b4e'"),
        "title" to ColumnSpec("TEXT"),
        "nodes" to ColumnSpec("TEXT"),
        "create_at" to ColumnSpec("INTEGER"),
        "update_at" to ColumnSpec("INTEGER"),
        "suggestions" to ColumnSpec("TEXT", defaultValue = "'[]'"),
        "is_pinned" to ColumnSpec("INTEGER", defaultValue = "0"),
        "custom_system_prompt" to ColumnSpec("TEXT", defaultValue = "''")
    )

    private val RIKKAHUB_248_COLUMNS = ORANGECHAT_V25_COLUMNS + mapOf(
        "mode_injection_ids" to ColumnSpec("TEXT", defaultValue = "'[]'"),
        "lorebook_ids" to ColumnSpec("TEXT", defaultValue = "'[]'"),
        "workspace_cwd" to ColumnSpec("TEXT", defaultValue = "''"),
        "folder_id" to ColumnSpec("TEXT", defaultValue = "''")
    )

    private val CONVERSATION_FOLDER_COLUMNS = mapOf(
        "id" to ColumnSpec("TEXT", primaryKeyPosition = 1),
        "assistant_id" to ColumnSpec("TEXT"),
        "name" to ColumnSpec("TEXT"),
        "sort_index" to ColumnSpec("INTEGER", defaultValue = "0"),
        "create_at" to ColumnSpec("INTEGER")
    )

    private val MESSAGE_NODE_COLUMNS = mapOf(
        "id" to ColumnSpec("TEXT", primaryKeyPosition = 1),
        "conversation_id" to ColumnSpec("TEXT"),
        "node_index" to ColumnSpec("INTEGER"),
        "messages" to ColumnSpec("TEXT"),
        "select_index" to ColumnSpec("INTEGER")
    )

    private val CREATE_TARGET_CONVERSATION_TABLE =
        """
        CREATE TABLE `$TARGET_CONVERSATION_TABLE` (
            `id` TEXT NOT NULL,
            `assistant_id` TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
            `title` TEXT NOT NULL,
            `nodes` TEXT NOT NULL,
            `create_at` INTEGER NOT NULL,
            `update_at` INTEGER NOT NULL,
            `suggestions` TEXT NOT NULL DEFAULT '[]',
            `is_pinned` INTEGER NOT NULL DEFAULT 0,
            `custom_system_prompt` TEXT NOT NULL DEFAULT '',
            `folder_id` TEXT NOT NULL DEFAULT '',
            PRIMARY KEY(`id`)
        )
        """.trimIndent()
}
