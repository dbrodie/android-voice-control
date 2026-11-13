package com.brainstormity.voicecontrol.agent

/**
 * What the task string offers JEV: quoted text, 1-3 word phrases, websites and apps.
 * Faithful port of `quoted_texts`, `text_options`, `websites` (jev_agent.py) and `apps_in_task` (phone.py).
 *
 * Regex notes: Python 3's `\w`, `\d` and `\b` are Unicode-aware. Java's are ASCII-only on the JVM but Unicode on
 * Android (ICU), so we spell them out (W = `[\p{L}\p{N}_]`, word boundaries as lookarounds) to behave like
 * Python on both.
 */
object TaskParser {
    private const val W = """[\p{L}\p{N}_]"""
    private const val B_BEFORE = """(?<!$W)"""   // \b in front of a word character
    private const val B_AFTER = """(?!$W)"""     // \b after a word character

    /** Site names people write without ".com", in lowercase (KNOWN_SITES; order matters for the regex). */
    val KNOWN_SITES: Map<String, String> = linkedMapOf(
        "wikipedia" to "en.wikipedia.org", "youtube" to "youtube.com", "twitter" to "x.com", "google" to "google.com",
        "reddit" to "reddit.com", "github" to "github.com", "amazon" to "amazon.com", "linkedin" to "linkedin.com",
        "facebook" to "facebook.com", "instagram" to "instagram.com", "gmail" to "mail.google.com",
    )

    /** App names people write in tasks -> Android package names (phone.py KNOWN_APPS, same order). */
    val KNOWN_APPS: Map<String, String> = linkedMapOf(
        "x" to "com.twitter.android", "twitter" to "com.twitter.android", "instagram" to "com.instagram.android",
        "whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube", "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm", "google maps" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps", "spotify" to "com.spotify.music", "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca", "telegram" to "org.telegram.messenger", "tiktok" to "com.zhiliaoapp.musically",
        "reddit" to "com.reddit.frontpage", "linkedin" to "com.linkedin.android", "snapchat" to "com.snapchat.android",
        "discord" to "com.discord", "slack" to "com.Slack", "uber" to "com.ubercab", "netflix" to "com.netflix.mediaclient",
        "settings" to "com.android.settings", "play store" to "com.android.vending",
        "google" to "com.google.android.googlequicksearchbox",
        "messages" to "com.google.android.apps.messaging", "phone" to "com.google.android.dialer",
        "camera" to "com.android.camera", "calendar" to "com.google.android.calendar",
        "photos" to "com.google.android.apps.photos",
    )

    /**
     * Not in upstream (phone.py only knew KNOWN_APPS): common built-in apps, so "Open the Clock app" opens the
     * app directly instead of hunting through the app drawer. Pairs, because a name can have several packages
     * (AOSP vs Google builds); only installed ones count. Packages here are deliberately not in KNOWN_APPS.
     */
    val EXTRA_APPS: List<Pair<String, String>> = listOf(
        // Only names that are rarely ordinary words in a task ("keep", "drive", "files", "alarm" are left out).
        "clock" to "com.google.android.deskclock", "clock" to "com.android.deskclock",
        "calculator" to "com.google.android.calculator", "calculator" to "com.android.calculator2",
        "contacts" to "com.google.android.contacts", "contacts" to "com.android.contacts",
        "camera" to "com.android.camera2", "camera" to "com.google.android.GoogleCamera",
        "phone" to "com.android.dialer", "messages" to "com.android.messaging",
        "youtube music" to "com.google.android.apps.youtube.music", "google drive" to "com.google.android.apps.docs",
        "google keep" to "com.google.android.keep",
    )

    /** An app named in the task: [name] as in KNOWN_APPS (lowercase), [packageName] to launch. */
    data class App(val name: String, val packageName: String)

    private val QUOTED = Regex("\"([^\"]+)\"|“([^”]+)”")
    private val QUOTED_ANY = Regex("\"[^\"]*\"|“[^”]*”|\"[^\"]*$")
    private val WORD = Regex("""(?:$W|['&.+-])+""")
    private val TIME = Regex("""\p{Nd}{1,2}[.:]\p{Nd}{2}""")
    private val TIME_SPLIT = Regex("[.:]")
    private val SITES: Regex = run {
        val domain = """(?:https?://)?(?:(?:$W|-)+\.)+(?:com|org|net|io|ai|co|dev|app|edu|gov)$B_AFTER[^\s"']*"""
        val names = B_BEFORE + "(" + KNOWN_SITES.keys.joinToString("|") + ")" + B_AFTER
        Regex("$domain|$names", RegexOption.IGNORE_CASE)
    }

    /** Exact text from the task, in quotes. A quote opened but never closed runs to the end of the task. */
    fun quotedTexts(task: String): List<String> {
        val found = QUOTED.findAll(task).map { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }.toMutableList()
        if (task.count { it == '"' } % 2 == 1) {  // e.g. ...with text "Hello world!   (closing quote forgotten)
            found.add(task.substring(task.lastIndexOf('"') + 1).trim())
        }
        return found.filter { it.isNotEmpty() }
    }

    /**
     * What JEV may type. It can only pick, not write, so offer the quoted text first,
     * then every 1-3 word phrase from the rest of the task (e.g. a search term like "Aliens").
     */
    fun textOptions(task: String): List<String> {
        val rest = QUOTED_ANY.replace(task, " ")
        val words = WORD.findAll(rest).map { it.value }.toList()
        val phrases = mutableListOf<String>()
        for (n in 1..3) {
            for (i in 0..(words.size - n)) phrases.add(words.subList(i, i + n).joinToString(" ").trim('.'))
        }
        for (word in words) {  // times and numbers in parts, e.g. "1.30" -> "1", "30", "1:30" (for time pickers)
            if (TIME.matches(word)) {
                val (hours, minutes) = word.split(TIME_SPLIT, limit = 2)
                phrases += listOf(hours, minutes, "$hours:$minutes")
            }
        }
        return (quotedTexts(task) + phrases).filter { it.isNotEmpty() }.distinct().take(200)
    }

    /** Websites in the task, in the order they appear: x.com, https://youtube.com, or names like 'wikipedia'. */
    fun websites(task: String): List<String> {
        val found = mutableListOf<String>()
        for (m in SITES.findAll(task)) {
            val name = m.groups[1]?.value
            val site = if (!name.isNullOrEmpty()) KNOWN_SITES.getValue(name.lowercase())
            else m.value.trimEnd('.', ',', ';', ':', '!', '?', ')')
            found.add(if (site.startsWith("http")) site else "https://$site")
        }
        return found.distinct()
    }

    /**
     * Apps named in the task that are installed, in the order they appear.
     * "X" only counts in capitals (so the letter x in other words doesn't), other names in any case.
     */
    fun appsInTask(task: String, installed: Set<String>): List<App> {
        data class Hit(val start: Int, val name: String, val pkg: String)
        val found = mutableListOf<Hit>()
        for ((name, pkg) in KNOWN_APPS.entries.map { it.key to it.value } + EXTRA_APPS) {
            val regex = if (name == "x") Regex(B_BEFORE + "X" + B_AFTER)
            else Regex(B_BEFORE + Regex.escape(name) + B_AFTER, RegexOption.IGNORE_CASE)
            val match = regex.find(task)
            if (match != null && pkg in installed) found.add(Hit(match.range.first, name, pkg))
        }
        // Python sorts the (start, name, package) tuples.
        found.sortWith(compareBy<Hit>({ it.start }, { it.name }, { it.pkg }))
        val seen = mutableSetOf<String>()
        return found.filter { seen.add(it.pkg) }.map { App(it.name, it.pkg) }
    }
}
