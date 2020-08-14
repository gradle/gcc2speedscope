package gcc2speedscope

import com.beust.klaxon.JsonObject
import com.beust.klaxon.KlaxonException
import com.beust.klaxon.Parser
import com.beust.klaxon.Render.renderValue
import java.io.Reader
import java.io.StringReader
import java.io.Writer
import java.nio.file.Files.newBufferedReader
import java.nio.file.Paths
import java.util.regex.Pattern
import kotlin.system.exitProcess


fun main(args: Array<String>) {
    if (args.size != 1) {
        println("gcc2speedscope (<Gradle debug log file> | --)")
        exitProcess(1)
    }

    val reader = when (val fileName = args[0]) {
        "--" -> System.`in`.bufferedReader()
        else -> newBufferedReader(Paths.get(fileName))
    }

    val log = parseConfigurationCacheDebugLog(reader)
    System.out.bufferedWriter().use { writer ->
        writer.writeSpeedscopeDocumentFor(log)
    }
}


data class Log(
    val frames: List<String>,
    val profiles: List<Profile>
)


data class Profile(
    val name: String,
    val events: List<Event>
)


data class Event(
    val timestamp: String,
    val category: String,
    val payload: JsonObject
)


internal
fun Writer.writeSpeedscopeDocumentFor(log: Log, prettyPrint: Boolean = false) {
    renderValue(
        speedscopeDocumentFor(log),
        result = this,
        canonical = false,
        prettyPrint = prettyPrint,
        level = 0
    )
}


private
fun speedscopeDocumentFor(log: Log) = mapOf<String, Any>(
    "exporter" to "speedscope@0.6.0",
    "${'$'}schema" to "https://www.speedscope.app/file-format-schema.json",
    "activeProfileIndex" to log.profiles
        .indexOfFirst { profile -> profile.name == "state" }
        .let { index -> if (index == -1) 0 else index },
    "shared" to mapOf(
        "frames" to log.frames.map {
            mapOf("name" to it)
        }
    ),
    "profiles" to log.profiles.map { profile ->
        val events = profile.events
        val startValue = 0 // or should it be `events.first().payload.at` ?
        val endValue = events.last().payload.at
        mapOf(
            "type" to "evented",
            "name" to profile.name,
            "unit" to "bytes",
            "startValue" to startValue,
            "endValue" to endValue,
            "events" to events.map { it.payload }
        )
    }
)


internal
fun parseConfigurationCacheDebugLog(reader: Reader): Log =
    reader.useLines {
        speedscopeLogFor(
            configurationCacheEventsFromDebugLogLines(it)
        )
    }


private
fun speedscopeLogFor(events: Sequence<Event>): Log {
    val frames = LinkedHashMap<String, Int>()
    val profiles = HashMap<String, ArrayList<Event>>()
    events.forEach { event ->
        // map frame name to frame index
        val jsonObject = event.payload
        val frame: String by jsonObject
        val frameIndex = frames.computeIfAbsent(frame) { frames.size }
        jsonObject["frame"] = frameIndex

        // group events by category
        profiles
            .computeIfAbsent(event.category) { ArrayList() }
            .add(event)
    }
    return Log(
        frames.keys.toList(),
        profiles.map { (name, events) ->
            Profile(
                name,
                events.apply {
                    sortBy { it.payload.at }
                }
            )
        }
    )
}


private
val JsonObject.at: Long
    get() = long("at")!!


private
fun configurationCacheEventsFromDebugLogLines(lines: Sequence<String>) = sequence<Event> {
    // Example log line:
    // 2020-08-13T15:19:11.495-0300 [DEBUG] [org.gradle.instantexecution.DefaultInstantExecution] [configuration cache state] {type:"O",frame:"Gradle",at:6}
    val linePattern = logLinePattern()
    val jsonParser = Parser.default()
    lines.forEachIndexed { index, line ->
        val matcher = linePattern.matcher(line)
        if (matcher.matches()) {
            val timestamp = matcher.group(1)
            val category = matcher.group(2)
            val jsonEvent = matcher.group(3)
            try {
                val jsonObject = jsonParser.parse(StringReader(jsonEvent)) as JsonObject
                yield(Event(timestamp, category, jsonObject))
            } catch (e: KlaxonException) {
                throw IllegalArgumentException("line ${index + 1}: failed to parse $jsonEvent", e)
            }
        }
    }
}


private
fun logLinePattern(): Pattern {
    val logPrefix = "[DEBUG] [org.gradle.instantexecution.DefaultInstantExecution] [configuration cache"
    return Pattern.compile("([0-9:T.\\-]+) ${Regex.escape(logPrefix)} (state|fingerprint)\\] (.*)")
}
