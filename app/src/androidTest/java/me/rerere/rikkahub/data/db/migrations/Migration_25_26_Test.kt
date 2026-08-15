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
    fun incompatibleExistingFolderColumnFailsBeforeChangingData() {
        val db = helper.createDatabase("migration-25-26-incompatible", 25).apply {
            execSQL("ALTER TABLE conversationentity ADD COLUMN folder_id INTEGER NOT NULL DEFAULT 0")
            insertConversationAndMessage("conversation-incompatible", "message-incompatible", 9)
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
        assertPreservedConversationAndMessage(db, "conversation-incompatible", "message-incompatible")
        assertEquals(0, tableCount(db, "conversation_folder"))
        db.close()
    }

    @Test
    fun unknownExtraConversationColumnFailsBeforeChangingData() {
        val db = helper.createDatabase("migration-25-26-unknown-column", 25).apply {
            execSQL(
                "ALTER TABLE conversationentity " +
                    "ADD COLUMN unexpected_legacy_data TEXT NOT NULL DEFAULT ''"
            )
            insertConversationAndMessage("conversation-unknown", "message-unknown")
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
        assertPreservedConversationAndMessage(db, "conversation-unknown", "message-unknown")
        assertEquals(0, tableCount(db, "conversation_folder"))
        assertEquals(0, tableCount(db, "rikkahub_248_conversation_compat"))
        db.close()
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
                arrayOf<Any?>(
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

    private fun tableCount(db: SupportSQLiteDatabase, table: String): Int = queryInt(
        db,
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(table)
    )
}
