/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLoggingInterceptorTest {
    @Test
    fun safeRequestLogContainsOnlyMetadata() {
        val entry = safeRequestLog(
            method = "POST",
            responseCode = 401,
            durationMs = 123,
            errorType = "IOException",
        )

        assertEquals("[redacted]", entry.url)
        assertEquals("POST", entry.method)
        assertEquals(401, entry.responseCode)
        assertEquals(123L, entry.durationMs)
        assertEquals("IOException", entry.error)
        assertTrue(entry.requestHeaders.isEmpty())
        assertNull(entry.requestBody)
        assertTrue(entry.responseHeaders.isEmpty())
        assertNull(entry.responseBody)
    }
}
