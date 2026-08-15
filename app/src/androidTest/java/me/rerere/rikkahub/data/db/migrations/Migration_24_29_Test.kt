/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_24_29_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun rikkaHub248V24WithoutMemoryBankMigratesThroughCurrentRoomSchema() {
        val databaseName = "migration-24-29-rikkahub"
        helper.createDatabase(databaseName, 24).apply {
            execSQL("DROP TABLE `memory_bank`")
            addRikkaHubConversationColumns()
            createConversationFolderObjects()
            insertRikkaHubData()
            assertFalse(tableExists("memory_bank"))
            close()
        }

        val roomDatabase = openCurrentDatabase(databaseName)
        val db = roomDatabase.openHelper.writableDatabase

        assertEquals(29, db.version)
        assertMemoryBankSchema(db)
        assertEquals(0, queryInt(db, "SELECT COUNT(*) FROM memory_bank"))
        assertRikkaHubDataPreserved(db)
        roomDatabase.close()
    }

    @Test
    fun orangeChatV24MemoryBankDataIsPreservedThroughCurrentRoomSchema() {
        val databaseName = "migration-24-29-orangechat"
        helper.createDatabase(databaseName, 24).apply {
            insertMemoryBankRow()
            insertNativeConversationAndMessage()
            close()
        }

        val roomDatabase = openCurrentDatabase(databaseName)
        val db = roomDatabase.openHelper.writableDatabase

        assertEquals(29, db.version)
        assertMemoryBankSchema(db)
        assertMemoryBankRowPreserved(db)
        assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM conversationentity"))
        assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM message_node"))
        roomDatabase.close()
    }

    @Test
    fun knownMemoryBankWithoutEmbeddingAddsColumnAndPreservesData() {
        val db = helper.createDatabase("migration-24-25-without-embedding", 24).apply {
            insertMemoryBankRow()
            rebuildMemoryBankWithoutEmbedding()
        }

        Migration_24_25.migrate(db)

        assertMemoryBankSchema(db)
        assertEquals(
            1,
            queryInt(
                db,
                "SELECT COUNT(*) FROM memory_bank WHERE id = ? AND content = ? AND embedding IS NULL",
                arrayOf<Any?>(7, "memory-content")
            )
        )
        db.close()
    }

    @Test
    fun incompatibleMemoryBankFailsBeforeChangingData() {
        val db = helper.createDatabase("migration-24-25-incompatible-memory", 24).apply {
            execSQL("DROP TABLE `memory_bank`")
            execSQL(
                """
                CREATE TABLE `memory_bank` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `content` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            execSQL("INSERT INTO memory_bank (id, content) VALUES (?, ?)", arrayOf<Any?>(11, 42))
        }

        val failure = try {
            Migration_24_25.migrate(db)
            fail("Migration must reject an incompatible memory bank schema")
            null
        } catch (throwable: Throwable) {
            throwable
        }

        assertNotNull(failure)
        assertTrue(failure is IllegalStateException)
        assertEquals(42, queryInt(db, "SELECT content FROM memory_bank WHERE id = 11"))
        assertFalse(columnExists(db, "memory_bank", "embedding"))
        db.close()
    }

    private fun openCurrentDatabase(name: String): AppDatabase = Room.databaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
        name
    ).addMigrations(Migration_24_25, Migration_25_26)
        .allowMainThreadQueries()
        .build()

    private fun SupportSQLiteDatabase.addRikkaHubConversationColumns() {
        execSQL(
            "ALTER TABLE conversationentity " +
                "ADD COLUMN mode_injection_ids TEXT NOT NULL DEFAULT '[]'"
        )
        execSQL(
            "ALTER TABLE conversationentity " +
                "ADD COLUMN lorebook_ids TEXT NOT NULL DEFAULT '[]'"
        )
        execSQL("ALTER TABLE conversationentity ADD COLUMN workspace_cwd TEXT NOT NULL DEFAULT ''")
        execSQL("ALTER TABLE conversationentity ADD COLUMN folder_id TEXT NOT NULL DEFAULT ''")
    }

    private fun SupportSQLiteDatabase.createConversationFolderObjects() {
        execSQL(
            """
            CREATE TABLE conversation_folder (
                id TEXT NOT NULL,
                assistant_id TEXT NOT NULL,
                name TEXT NOT NULL,
                sort_index INTEGER NOT NULL DEFAULT 0,
                create_at INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        execSQL(
            "CREATE INDEX index_conversation_folder_assistant_id " +
                "ON conversation_folder (assistant_id)"
        )
    }

    private fun SupportSQLiteDatabase.insertRikkaHubData() {
        execSQL(
            "INSERT INTO conversation_folder " +
                "(id, assistant_id, name, sort_index, create_at) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>("folder-rikka", "assistant-rikka", "Rikka folder", 3, 300L)
        )
        execSQL(
            "INSERT INTO conversationentity " +
                "(id, assistant_id, title, nodes, create_at, update_at, suggestions, " +
                "is_pinned, custom_system_prompt, mode_injection_ids, lorebook_ids, " +
                "workspace_cwd, folder_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "conversation-rikka",
                "assistant-rikka",
                "Rikka conversation",
                "[\"preserved-node\"]",
                100L,
                200L,
                "[\"suggestion\"]",
                1,
                "preserved-system-prompt",
                "[\"mode-test-id\"]",
                "[\"lorebook-test-id\"]",
                "/workspace/project",
                "folder-rikka"
            )
        )
        execSQL(
            "INSERT INTO message_node " +
                "(id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>("message-rikka", "conversation-rikka", 4, "[\"preserved-message\"]", 2)
        )
    }

    private fun SupportSQLiteDatabase.insertNativeConversationAndMessage() {
        execSQL(
            "INSERT INTO conversationentity " +
                "(id, assistant_id, title, nodes, create_at, update_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>("conversation-native", "assistant-native", "Native conversation", "[]", 10L, 20L)
        )
        execSQL(
            "INSERT INTO message_node " +
                "(id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>("message-native", "conversation-native", 0, "[]", 0)
        )
    }

    private fun SupportSQLiteDatabase.insertMemoryBankRow() {
        execSQL(
            "INSERT INTO memory_bank " +
                "(id, content, type, conversation_id, assistant_id, role, created_at, date_group, " +
                "vector_status, vector_retry_count, embedding) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                7,
                "memory-content",
                "manual",
                "conversation-native",
                "assistant-native",
                "user",
                987654L,
                "2026-01-02",
                "done",
                4,
                "[0.25,0.75]"
            )
        )
    }

    private fun SupportSQLiteDatabase.rebuildMemoryBankWithoutEmbedding() {
        execSQL(
            """
            CREATE TABLE `memory_bank_without_embedding` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `content` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `conversation_id` TEXT,
                `assistant_id` TEXT,
                `role` TEXT,
                `created_at` INTEGER NOT NULL,
                `date_group` TEXT,
                `vector_status` TEXT NOT NULL,
                `vector_retry_count` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO `memory_bank_without_embedding` (
                `id`, `content`, `type`, `conversation_id`, `assistant_id`, `role`,
                `created_at`, `date_group`, `vector_status`, `vector_retry_count`
            )
            SELECT
                `id`, `content`, `type`, `conversation_id`, `assistant_id`, `role`,
                `created_at`, `date_group`, `vector_status`, `vector_retry_count`
            FROM `memory_bank`
            """.trimIndent()
        )
        execSQL("DROP TABLE `memory_bank`")
        execSQL("ALTER TABLE `memory_bank_without_embedding` RENAME TO `memory_bank`")
    }

    private fun assertMemoryBankSchema(db: SupportSQLiteDatabase) {
        val expected = mapOf(
            "id" to ColumnExpectation("INTEGER", true, 1),
            "content" to ColumnExpectation("TEXT", true),
            "type" to ColumnExpectation("TEXT", true),
            "conversation_id" to ColumnExpectation("TEXT", false),
            "assistant_id" to ColumnExpectation("TEXT", false),
            "role" to ColumnExpectation("TEXT", false),
            "created_at" to ColumnExpectation("INTEGER", true),
            "date_group" to ColumnExpectation("TEXT", false),
            "vector_status" to ColumnExpectation("TEXT", true),
            "vector_retry_count" to ColumnExpectation("INTEGER", true),
            "embedding" to ColumnExpectation("TEXT", false)
        )
        val found = mutableMapOf<String, ColumnExpectation>()
        db.query("PRAGMA table_info(`memory_bank`)").use { cursor ->
            while (cursor.moveToNext()) {
                found[cursor.getString(cursor.getColumnIndexOrThrow("name"))] = ColumnExpectation(
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                    primaryKeyPosition = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                )
            }
        }
        assertEquals(expected, found)
    }

    private fun assertMemoryBankRowPreserved(db: SupportSQLiteDatabase) {
        assertEquals(
            1,
            queryInt(
                db,
                "SELECT COUNT(*) FROM memory_bank WHERE " +
                    "id = ? AND content = ? AND type = ? AND conversation_id = ? " +
                    "AND assistant_id = ? AND role = ? AND created_at = ? AND date_group = ? " +
                    "AND vector_status = ? AND vector_retry_count = ? AND embedding = ?",
                arrayOf<Any?>(
                    7,
                    "memory-content",
                    "manual",
                    "conversation-native",
                    "assistant-native",
                    "user",
                    987654L,
                    "2026-01-02",
                    "done",
                    4,
                    "[0.25,0.75]"
                )
            )
        )
    }

    private fun assertRikkaHubDataPreserved(db: SupportSQLiteDatabase) {
        assertEquals(
            1,
            queryInt(
                db,
                "SELECT COUNT(*) FROM conversationentity WHERE " +
                    "id = ? AND assistant_id = ? AND title = ? AND nodes = ? " +
                    "AND suggestions = ? AND is_pinned = ? AND custom_system_prompt = ? AND folder_id = ?",
                arrayOf<Any?>(
                    "conversation-rikka",
                    "assistant-rikka",
                    "Rikka conversation",
                    "[\"preserved-node\"]",
                    "[\"suggestion\"]",
                    1,
                    "preserved-system-prompt",
                    "folder-rikka"
                )
            )
        )
        assertEquals(
            1,
            queryInt(
                db,
                "SELECT COUNT(*) FROM message_node WHERE " +
                    "id = ? AND conversation_id = ? AND node_index = ? AND messages = ? AND select_index = ?",
                arrayOf<Any?>(
                    "message-rikka",
                    "conversation-rikka",
                    4,
                    "[\"preserved-message\"]",
                    2
                )
            )
        )
        assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM conversation_folder WHERE id = 'folder-rikka'"))
        db.query(
            "SELECT mode_injection_ids, lorebook_ids, workspace_cwd " +
                "FROM rikkahub_248_conversation_compat WHERE conversation_id = ?",
            arrayOf("conversation-rikka")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[\"mode-test-id\"]", cursor.getString(0))
            assertEquals("[\"lorebook-test-id\"]", cursor.getString(1))
            assertEquals("/workspace/project", cursor.getString(2))
        }
    }

    private fun SupportSQLiteDatabase.tableExists(table: String): Boolean =
        query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 }

    private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex).equals(column, ignoreCase = true)) return true
            }
            false
        }

    private fun queryInt(
        db: SupportSQLiteDatabase,
        sql: String,
        arguments: Array<out Any?> = emptyArray()
    ): Int = db.query(sql, arguments).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private data class ColumnExpectation(
        val type: String,
        val notNull: Boolean,
        val primaryKeyPosition: Int = 0
    )
}
