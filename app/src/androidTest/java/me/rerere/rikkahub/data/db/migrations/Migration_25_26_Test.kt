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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_25_26_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun orangeChatV25AddsFolderColumnAndPreservesData() {
        helper.createDatabase("migration-25-26-orangechat", 25).apply {
            insertConversationAndMessage(
                conversationId = "conversation-orangechat",
                messageId = "message-orangechat"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            "migration-25-26-orangechat",
            26,
            true,
            Migration_25_26
        )

        assertCompatibleFolderColumn(db)
        assertEquals("", queryString(db, "SELECT folder_id FROM conversationentity"))
        assertPreservedConversationAndMessage(
            db = db,
            conversationId = "conversation-orangechat",
            messageId = "message-orangechat"
        )
        assertEquals(26, queryInt(db, "PRAGMA user_version"))
        db.close()
    }

    @Test
    fun completeRikkaHub248SchemaIsNormalizedAndLegacyDataIsPreserved() {
        helper.createDatabase("migration-25-26-rikkahub", 25).apply {
            execSQL(
                "ALTER TABLE conversationentity " +
                    "ADD COLUMN mode_injection_ids TEXT NOT NULL DEFAULT '[]'"
            )
            execSQL(
                "ALTER TABLE conversationentity " +
                    "ADD COLUMN lorebook_ids TEXT NOT NULL DEFAULT '[]'"
            )
            execSQL(
                "ALTER TABLE conversationentity " +
                    "ADD COLUMN workspace_cwd TEXT NOT NULL DEFAULT ''"
            )
            execSQL("ALTER TABLE conversationentity ADD COLUMN folder_id TEXT NOT NULL DEFAULT ''")
            createFolderObjects()
            execSQL(
                "INSERT INTO conversation_folder " +
                    "(id, assistant_id, name, sort_index, create_at) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("folder-existing", "assistant-existing", "Existing folder", 7, 1234L)
            )
            insertConversationAndMessage(
                conversationId = "conversation-rikkahub",
                messageId = "message-rikkahub",
                folderId = "folder-existing",
                assistantId = "assistant-rikkahub",
                title = "RikkaHub conversation",
                nodes = "[\"preserved-node\"]",
                customSystemPrompt = "preserved-system-prompt",
                legacyMetadata = LegacyMetadata(
                    modeInjectionIds = "[\"mode-test-id\"]",
                    lorebookIds = "[\"lorebook-test-id\"]",
                    workspaceCwd = "/workspace/project"
                ),
                messages = "[\"preserved-message\"]"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            "migration-25-26-rikkahub",
            26,
            false,
            Migration_25_26
        )

        assertCompatibleFolderColumn(db)
        assertEquals("folder-existing", queryString(db, "SELECT folder_id FROM conversationentity"))
        assertLegacyColumnsRemoved(db)
        assertLegacyMetadataPreserved(db)
        assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM conversation_folder"))
        assertEquals(
            1,
            queryInt(
                db,
                "SELECT COUNT(*) FROM sqlite_master " +
                    "WHERE type = 'index' AND name = 'index_conversation_folder_assistant_id'"
            )
        )
        assertPreservedConversationAndMessage(
            db = db,
            conversationId = "conversation-rikkahub",
            messageId = "message-rikkahub",
            assistantId = "assistant-rikkahub",
            title = "RikkaHub conversation",
            nodes = "[\"preserved-node\"]",
            customSystemPrompt = "preserved-system-prompt",
            messages = "[\"preserved-message\"]"
        )
        assertEquals(26, queryInt(db, "PRAGMA user_version"))
        db.close()

        val roomDatabase = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            "migration-25-26-rikkahub"
        ).allowMainThreadQueries().build()
        val currentDb = roomDatabase.openHelper.writableDatabase
        assertEquals(29, currentDb.version)
        assertLegacyMetadataPreserved(currentDb)
        assertPreservedConversationAndMessage(
            db = currentDb,
            conversationId = "conversation-rikkahub",
            messageId = "message-rikkahub",
            assistantId = "assistant-rikkahub",
            title = "RikkaHub conversation",
            nodes = "[\"preserved-node\"]",
            customSystemPrompt = "preserved-system-prompt",
            messages = "[\"preserved-message\"]"
        )
        roomDatabase.close()
    }

    @Test
    fun incompatibleExistingFolderColumnFailsBeforeChangingData() {
        val db = helper.createDatabase("migration-25-26-incompatible", 25).apply {
            execSQL("ALTER TABLE conversationentity ADD COLUMN folder_id INTEGER NOT NULL DEFAULT 0")
            insertConversationAndMessage(
                conversationId = "conversation-incompatible",
                messageId = "message-incompatible",
                folderId = 9
            )
        }

        val failure = try {
            Migration_25_26.migrate(db)
            fail("Migration must reject an incompatible existing folder column")
            null
        } catch (throwable: Throwable) {
            throwable
        }

        assertNotNull(failure)
        assertTrue(failure is IllegalStateException)
        assertEquals(9, queryInt(db, "SELECT folder_id FROM conversationentity"))
        assertPreservedConversationAndMessage(
            db = db,
            conversationId = "conversation-incompatible",
            messageId = "message-incompatible"
        )
        assertEquals(
            0,
            queryInt(
                db,
                "SELECT COUNT(*) FROM sqlite_master " +
                    "WHERE type = 'table' AND name = 'conversation_folder'"
            )
        )
        db.close()
    }

    @Test
    fun unknownExtraConversationColumnFailsBeforeChangingData() {
        val db = helper.createDatabase("migration-25-26-unknown-column", 25).apply {
            execSQL(
                "ALTER TABLE conversationentity " +
                    "ADD COLUMN unexpected_legacy_data TEXT NOT NULL DEFAULT ''"
            )
            insertConversationAndMessage(
                conversationId = "conversation-unknown",
                messageId = "message-unknown"
            )
        }

        val failure = try {
            Migration_25_26.migrate(db)
            fail("Migration must reject an unknown source column")
            null
        } catch (throwable: Throwable) {
            throwable
        }

        assertNotNull(failure)
        assertTrue(failure is IllegalStateException)
        assertPreservedConversationAndMessage(
            db = db,
            conversationId = "conversation-unknown",
            messageId = "message-unknown"
        )
        assertEquals(0, tableCount(db, "conversation_folder"))
        assertEquals(0, tableCount(db, "rikkahub_248_conversation_compat"))
        db.close()
    }

    private fun SupportSQLiteDatabase.createFolderObjects() {
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

    private fun SupportSQLiteDatabase.insertConversationAndMessage(
        conversationId: String,
        messageId: String,
        folderId: Any? = null,
        assistantId: String = "assistant-test",
        title: String = "Test conversation",
        nodes: String = "[]",
        customSystemPrompt: String = "",
        legacyMetadata: LegacyMetadata? = null,
        messages: String = "[]"
    ) {
        if (legacyMetadata != null) {
            execSQL(
                "INSERT INTO conversationentity " +
                    "(id, assistant_id, title, nodes, create_at, update_at, custom_system_prompt, " +
                    "folder_id, mode_injection_ids, lorebook_ids, workspace_cwd) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    conversationId,
                    assistantId,
                    title,
                    nodes,
                    100L,
                    200L,
                    customSystemPrompt,
                    folderId,
                    legacyMetadata.modeInjectionIds,
                    legacyMetadata.lorebookIds,
                    legacyMetadata.workspaceCwd
                )
            )
        } else if (folderId == null) {
            execSQL(
                "INSERT INTO conversationentity " +
                    "(id, assistant_id, title, nodes, create_at, update_at, custom_system_prompt) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    conversationId,
                    assistantId,
                    title,
                    nodes,
                    100L,
                    200L,
                    customSystemPrompt
                )
            )
        } else {
            execSQL(
                "INSERT INTO conversationentity " +
                    "(id, assistant_id, title, nodes, create_at, update_at, " +
                    "custom_system_prompt, folder_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    conversationId,
                    assistantId,
                    title,
                    nodes,
                    100L,
                    200L,
                    customSystemPrompt,
                    folderId
                )
            )
        }
        execSQL(
            "INSERT INTO message_node " +
                "(id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(messageId, conversationId, 0, messages, 0)
        )
    }

    private fun assertLegacyColumnsRemoved(db: SupportSQLiteDatabase) {
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(`conversationentity`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex).lowercase()
        }
        assertTrue("mode_injection_ids must not remain in Room entity", "mode_injection_ids" !in columns)
        assertTrue("lorebook_ids must not remain in Room entity", "lorebook_ids" !in columns)
        assertTrue("workspace_cwd must not remain in Room entity", "workspace_cwd" !in columns)
    }

    private fun assertLegacyMetadataPreserved(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT mode_injection_ids, lorebook_ids, workspace_cwd " +
                "FROM rikkahub_248_conversation_compat WHERE conversation_id = ?",
            arrayOf("conversation-rikkahub")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[\"mode-test-id\"]", cursor.getString(0))
            assertEquals("[\"lorebook-test-id\"]", cursor.getString(1))
            assertEquals("/workspace/project", cursor.getString(2))
        }
    }

    private fun assertCompatibleFolderColumn(db: SupportSQLiteDatabase) {
        db.query("PRAGMA table_info(`conversationentity`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex).equals("folder_id", ignoreCase = true)) {
                    assertEquals("TEXT", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                    assertEquals("''", cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                    assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("pk")))
                    return
                }
            }
        }
        fail("folder_id column was not found")
    }

    private fun assertPreservedConversationAndMessage(
        db: SupportSQLiteDatabase,
        conversationId: String,
        messageId: String,
        assistantId: String = "assistant-test",
        title: String = "Test conversation",
        nodes: String = "[]",
        customSystemPrompt: String = "",
        messages: String = "[]"
    ) {
        assertEquals(
            1,
            queryInt(
                db,
                "SELECT COUNT(*) FROM conversationentity " +
                    "WHERE id = ? AND assistant_id = ? AND title = ? AND nodes = ? " +
                    "AND custom_system_prompt = ?",
                arrayOf(conversationId, assistantId, title, nodes, customSystemPrompt)
            )
        )
        assertEquals(
            1,
            queryInt(
                db,
                "SELECT COUNT(*) FROM message_node " +
                    "WHERE id = ? AND conversation_id = ? AND messages = ?",
                arrayOf(messageId, conversationId, messages)
            )
        )
    }

    private fun queryInt(
        db: SupportSQLiteDatabase,
        sql: String,
        arguments: Array<out Any?> = emptyArray()
    ): Int = db.query(sql, arguments).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun queryString(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun tableCount(db: SupportSQLiteDatabase, table: String): Int = queryInt(
        db,
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(table)
    )

    private data class LegacyMetadata(
        val modeInjectionIds: String,
        val lorebookIds: String,
        val workspaceCwd: String
    )
}
