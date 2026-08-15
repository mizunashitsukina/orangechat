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
 * v24 -> v25: 确保完整的 memory_bank schema。
 *
 * OrangeChat 原生 v24 已包含 memory_bank；RikkaHub 2.4.8 v24 没有该表。
 * 迁移只接受这两个已知来源，或精确匹配但尚缺 embedding 的旧 OrangeChat 表。
 */
object Migration_24_25 : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!tableExists(db, MEMORY_BANK_TABLE)) {
            Migration_25_26.requireRikkaHub248SourceSchema(db)
            db.execSQL(CREATE_MEMORY_BANK_TABLE)
        } else {
            validateNoIndexesOrTriggers(db)
            val columns = readColumns(db)
            when {
                columns.matches(MEMORY_BANK_COLUMNS) -> Unit
                columns.matches(MEMORY_BANK_COLUMNS - EMBEDDING_COLUMN) -> {
                    db.execSQL("ALTER TABLE `memory_bank` ADD COLUMN `embedding` TEXT")
                }
                else -> throw IllegalStateException("Unsupported memory bank schema for migration to v25")
            }
        }

        check(readColumns(db).matches(MEMORY_BANK_COLUMNS)) {
            "Memory bank schema validation failed after migration to v25"
        }
    }

    private fun validateNoIndexesOrTriggers(db: SupportSQLiteDatabase) {
        db.query("PRAGMA index_list(`memory_bank`)").use { cursor ->
            check(cursor.count == 0) { "Unexpected index in memory bank migration source" }
        }
        db.query(
            "SELECT COUNT(*) FROM sqlite_master " +
                "WHERE type = 'trigger' AND lower(tbl_name) = lower(?)",
            arrayOf(MEMORY_BANK_TABLE)
        ).use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == 0L) {
                "Unexpected trigger in memory bank migration source"
            }
        }
        db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND lower(name) = lower(?)",
            arrayOf(MEMORY_BANK_TABLE)
        ).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0).contains("AUTOINCREMENT", ignoreCase = true)) {
                "Memory bank primary key schema is incompatible"
            }
        }
    }

    private fun readColumns(db: SupportSQLiteDatabase): Map<String, ExistingColumn> =
        db.query("PRAGMA table_info(`memory_bank`)").use { cursor ->
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
        val notNull: Boolean,
        val defaultValue: String? = null,
        val primaryKeyPosition: Int = 0
    )

    private const val MEMORY_BANK_TABLE = "memory_bank"
    private const val EMBEDDING_COLUMN = "embedding"

    private val MEMORY_BANK_COLUMNS = mapOf(
        "id" to ColumnSpec("INTEGER", notNull = true, primaryKeyPosition = 1),
        "content" to ColumnSpec("TEXT", notNull = true),
        "type" to ColumnSpec("TEXT", notNull = true),
        "conversation_id" to ColumnSpec("TEXT", notNull = false),
        "assistant_id" to ColumnSpec("TEXT", notNull = false),
        "role" to ColumnSpec("TEXT", notNull = false),
        "created_at" to ColumnSpec("INTEGER", notNull = true),
        "date_group" to ColumnSpec("TEXT", notNull = false),
        "vector_status" to ColumnSpec("TEXT", notNull = true),
        "vector_retry_count" to ColumnSpec("INTEGER", notNull = true),
        EMBEDDING_COLUMN to ColumnSpec("TEXT", notNull = false)
    )

    private val CREATE_MEMORY_BANK_TABLE =
        """
        CREATE TABLE `memory_bank` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `content` TEXT NOT NULL,
            `type` TEXT NOT NULL,
            `conversation_id` TEXT,
            `assistant_id` TEXT,
            `role` TEXT,
            `created_at` INTEGER NOT NULL,
            `date_group` TEXT,
            `vector_status` TEXT NOT NULL,
            `vector_retry_count` INTEGER NOT NULL,
            `embedding` TEXT
        )
        """.trimIndent()
}
