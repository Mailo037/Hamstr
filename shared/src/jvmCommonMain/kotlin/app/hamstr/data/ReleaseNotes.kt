package app.hamstr.data

/**
 * A release's notes as the apps show them (AGENTS.md › Release notes has the format): the
 * summary, then the points for this kind of device. The picture, the other device's section
 * and "### Details" stay on GitHub's release page.
 */
data class ReleaseNotes(val summary: String, val points: List<Point>) {
    /** One "- **Title.** Text." line: the title in bold, what follows under it. */
    data class Point(val title: String, val text: String)

    val isEmpty get() = summary.isBlank() && points.isEmpty()

    companion object {
        private const val BOTH = "phone and computer"
        private const val PHONE = "phone"
        private const val COMPUTER = "computer"
        private val LINK = Regex("""\[([^]]+)]\([^)]+\)""")
        private val TITLE = Regex("""^\*\*(.+?)\*\*\s*(.*)$""")

        /** The notes for the phone ([desktop] false) or the computer, from the release's Markdown. */
        fun parse(md: String, desktop: Boolean): ReleaseNotes {
            val mine = setOf(BOTH, if (desktop) COMPUTER else PHONE)
            val summary = StringBuilder()
            val points = mutableListOf<Point>()
            var section: String? = null
            var shown = true
            for (raw in md.lines()) {
                val line = raw.trim()
                when {
                    line.isEmpty() || line.startsWith("![") || line.startsWith("<!--") -> {}
                    line.startsWith("#") -> {
                        section = line.trimStart('#').trim().lowercase()
                        shown = section in mine
                    }
                    !shown -> {}
                    section == null -> summary.append(if (summary.isEmpty()) "" else " ").append(plain(line))
                    line.startsWith("- ") || line.startsWith("* ") -> {
                        val body = line.drop(2).trim()
                        val m = TITLE.find(body)
                        points += if (m != null) Point(plain(m.groupValues[1]), plain(m.groupValues[2])) else Point("", plain(body))
                    }
                    // A point that goes on over another line.
                    points.isNotEmpty() -> points[points.lastIndex] = points.last().let { it.copy(text = (it.text + " " + plain(line)).trim()) }
                }
            }
            return ReleaseNotes(summary.toString(), points)
        }

        private fun plain(s: String) = s.replace(LINK, "$1").replace("**", "").replace("`", "").replace(Regex("""(?<!\w)\*(\S[^*]*)\*"""), "$1").trim()
    }
}
