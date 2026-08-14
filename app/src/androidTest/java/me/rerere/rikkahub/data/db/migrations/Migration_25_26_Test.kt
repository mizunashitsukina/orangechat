/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

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
            insertConversationAndMessage("conversation-orangechat", "message-orangechat")
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
        assertPreservedConversationAndMessage(db, "conversation-orangechat", "message-orangechat")
        assertEquals(26, queryInt(db, "PRAGMA user_version"))
        db.close()
    }

    @Test
    fun rikkaHub248StyleV25KeepsExistingFolderSchemaAndData() {
        helper.createDatabase("migration-25-26-rikkahub", 25).apply {
            execSQL("ALTER TABLE conversationentity ADD COLUMN folder_id TEXT NOT NULL DEFAULT ''")
            createFolderObjects()
            execSQL(
                "INSERT INTO conversation_folder " +
                    "(id, assistant_id, name, sort_index, create_at) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("folder-existing", "assistant-existing", "Existing folder", 7, 1234L)
            )
            insertConversationAndMessage(
                "conversation-rikkahub",
                "message-rikkahub",
                "folder-existing"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            "migration-25-26-rikkahub",
            26,
            true,
            Migration_25_26
        )

        assertCompatibleFolderColumn(db)
        assertEquals("folder-existing", queryString(db, "SELECT folder_id FROM conversationentity"))
        assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM conversation_folder"))
        assertEquals(
            1,
            queryInt(
                db,
                "SELECT COUNT(*) FROM sqlite_master " +
                    "WHERE type = 'index' AND name = 'index_conversation_folder_assistant_id'"
            )
        )
        assertPreservedConversationAndMessage(db, "conversation-rikkahub", "message-rikkahub")
        assertEquals(26, queryInt(db, "PRAGMA user_version"))
        db.close()
    }

    @Test
    fun incompatibleExistingFolderColumnFailsBeforeChangingData() {
        val db = helper.createDatabase("migration-25-26-incompatible", 25).apply {
            execSQL("ALTER TABLE conversationentity ADD COLUMN folder_id INTEGER NOT NULL DEFAULT 0")
            insertConversationAndMessage(
                "conversation-incompatible",
                "message-incompatible",
                9
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
            db,
            "conversation-incompatible",
            "message-incompatible"
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
        folderId: Any? = null
    ) {
        if (folderId == null) {
            execSQL(
                "INSERT INTO conversationentity " +
                    "(id, assistant_id, title, nodes, create_at, update_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(conversationId, "assistant-test", "Test conversation", "[]", 100L, 200L)
            )
        } else {
            execSQL(
                "INSERT INTO conversationentity " +
                    "(id, assistant_id, title, nodes, create_at, update_at, folder_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    conversationId,
                    "assistant-test",
                    "Test conversation",
                    "[]",
                    100L,
                    200L,
                    folderId
                )
            )
        }
        execSQL(
            "INSERT INTO message_node " +
                "(id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(messageId, conversationId, 0, "[]", 0)
        )
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
        messageId: String
    ) {
        assertEquals(
            1,
            queryInt(
                db,
                "SELECT COUNT(*) FROM conversationentity " +
                    "WHERE id = ? AND title = ? AND nodes = ?",
                arrayOf(conversationId, "Test conversation", "[]")
            )
        )
        assertEquals(
            1,
            queryInt(
                db,
                "SELECT COUNT(*) FROM message_node " +
                    "WHERE id = ? AND conversation_id = ? AND messages = ?",
                arrayOf(messageId, conversationId, "[]")
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
}
