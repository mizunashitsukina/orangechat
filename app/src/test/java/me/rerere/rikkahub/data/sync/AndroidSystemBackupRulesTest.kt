/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AndroidSystemBackupRulesTest {
    @Test
    fun manifestDisablesSystemBackupAndKeepsBothRuleFiles() {
        val application = parseXml(projectFile("src/main/AndroidManifest.xml"))
            .getElementsByTagName("application")
            .item(0) as Element

        assertEquals("false", application.getAttributeNS(ANDROID_NAMESPACE, "allowBackup"))
        assertEquals(
            "@xml/backup_rules",
            application.getAttributeNS(ANDROID_NAMESPACE, "fullBackupContent")
        )
        assertEquals(
            "@xml/data_extraction_rules",
            application.getAttributeNS(ANDROID_NAMESPACE, "dataExtractionRules")
        )
    }

    @Test
    fun androidElevenAndLowerExcludeEverySupportedDataDomain() {
        val document = parseXml(projectFile("src/main/res/xml/backup_rules.xml"))
        assertEquals("full-backup-content", document.documentElement.tagName)
        assertNoIncludes(document.documentElement)
        assertCompleteExclusions(document.documentElement)
    }

    @Test
    fun androidTwelveAndHigherExcludeCloudAndDeviceTransferData() {
        val document = parseXml(projectFile("src/main/res/xml/data_extraction_rules.xml"))
        assertEquals("data-extraction-rules", document.documentElement.tagName)

        val cloudBackup = document.getElementsByTagName("cloud-backup").item(0) as? Element
        val deviceTransfer = document.getElementsByTagName("device-transfer").item(0) as? Element
        assertNotNull("cloud-backup rules must be present", cloudBackup)
        assertNotNull("device-transfer rules must be present", deviceTransfer)

        listOf(cloudBackup!!, deviceTransfer!!).forEach { rules ->
            assertNoIncludes(rules)
            assertCompleteExclusions(rules)
        }
    }

    private fun assertNoIncludes(parent: Element) {
        assertEquals("backup rules must not opt any data back in", 0, parent.getElementsByTagName("include").length)
    }

    private fun assertCompleteExclusions(parent: Element) {
        val exclusions = parent.getElementsByTagName("exclude")
        val domains = buildSet {
            repeat(exclusions.length) { index ->
                val exclusion = exclusions.item(index) as Element
                assertEquals("each domain must be excluded from its root", ".", exclusion.getAttribute("path"))
                assertTrue("duplicate backup domain", add(exclusion.getAttribute("domain")))
            }
        }

        assertEquals(EXCLUDED_DOMAINS, domains)
    }

    private fun parseXml(path: Path) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(path.toFile())

    private fun projectFile(relativePath: String): Path {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        val candidates = listOfNotNull(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app").resolve(relativePath),
            workingDirectory.parent?.resolve("app")?.resolve(relativePath)
        )
        val match = candidates.firstOrNull { Files.isRegularFile(it) }
        assertFalse("project file lookup must not use an absolute fixture path", Path.of(relativePath).isAbsolute)
        return requireNotNull(match) { "required Android backup configuration is missing" }
    }

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        private val EXCLUDED_DOMAINS = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref"
        )
    }
}
