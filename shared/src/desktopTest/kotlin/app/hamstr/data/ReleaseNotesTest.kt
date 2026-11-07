package app.hamstr.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseNotesTest {
    private val md = """
        ![What's new in Hamstr 1.2.3](https://example.com/v1.2.3.png)

        Hamstr 1.2.3 is a bugfix release.

        ### Phone and computer

        - **Both get this.** It works *everywhere*, see [the docs](https://example.com).

        ### Phone

        - **Only the phone.** Swipe it.
          It goes on here.

        ### Computer

        - **Only the computer.** Click it.

        ### Details

        Cheek stays at 0.3.2. Tests passed.
    """.trimIndent()

    @Test fun thePhoneSeesItsOwnAndBoth() {
        val n = ReleaseNotes.parse(md, desktop = false)
        assertEquals("Hamstr 1.2.3 is a bugfix release.", n.summary)
        assertEquals(
            listOf(
                ReleaseNotes.Point("Both get this.", "It works everywhere, see the docs."),
                ReleaseNotes.Point("Only the phone.", "Swipe it. It goes on here."),
            ),
            n.points,
        )
    }

    @Test fun theComputerSeesItsOwnAndBoth() {
        assertEquals(listOf("Both get this.", "Only the computer."), ReleaseNotes.parse(md, desktop = true).points.map { it.title })
    }

    /** Every release's notes from 0.10.8 on keep to the format in AGENTS.md. */
    @Test fun releaseNotesKeepToTheFormat() {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, ".github/release-notes") }.first { it.isDirectory }
        val versions = dir.listFiles()!!.mapNotNull { f -> Regex("""v(\d+)\.(\d+)\.(\d+)\.md""").matchEntire(f.name)?.let { m -> f to m.groupValues.drop(1).map(String::toInt) } }
        val checked = versions.filter { (_, v) -> v[0] > 0 || v[1] > 10 || (v[1] == 10 && v[2] >= 8) }
        assertTrue(checked.isNotEmpty())
        for ((file, _) in checked) {
            val text = file.readText()
            val headings = text.lines().filter { it.startsWith("#") }
            assertTrue(text.startsWith("![What's new in Hamstr "), "${file.name}: starts with the picture")
            assertTrue(headings.all { it in setOf("### Phone and computer", "### Phone", "### Computer", "### Details") }, "${file.name}: only the four sections, as ### headings: $headings")
            assertEquals(headings, headings.sortedBy { listOf("### Phone and computer", "### Phone", "### Computer", "### Details").indexOf(it) }, "${file.name}: sections in order")
            assertTrue("### Details" in headings, "${file.name}: has ### Details")
            for (desktop in listOf(false, true)) {
                val n = ReleaseNotes.parse(text, desktop)
                assertTrue(n.summary.isNotBlank(), "${file.name}: has a summary")
                assertTrue(n.points.all { it.title.isNotBlank() && it.text.isNotBlank() }, "${file.name}: every point is - **Title.** Text.")
            }
        }
    }
}
