package app.momoding.chat

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The time travels with each message, not in the system prompt (调研/M50 A2, as Grok Bot does).
 * A clock to the minute inside the instructions changed them every minute, and every request after
 * that change resent its whole history uncached. Each message instead opens with when it arrived,
 * from its own stored time, so the same message reads the same in every later request.
 */
object Clock {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm", Locale.ENGLISH)

    fun stamp(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "<time>" + FORMAT.format(Instant.ofEpochMilli(atMs).atZone(zone)) + "</time>\n"

    /** For the instructions: where the time is, and the zone, which does not change minute to minute. */
    fun promptSection(zone: ZoneId = ZoneId.systemDefault()): String =
        "## Time\nThe person's time zone is ${zone.id}. Each message opens with <time>…</time>, when it arrived: " +
            "work out today, tomorrow and yesterday from the latest one, not from your notes."
}
