package gcc2speedscope

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Reader
import java.io.Writer
import java.nio.file.Files
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

    System.out.bufferedWriter().use { writer ->
        writeSpeedscopeDocumentTo(
            writer, reader,
            prettyPrint = System.getenv("GCC2SS_PRETTY_PRINT") !== null,
            databaseFile = System.getenv("GCC2SS_DATABASE") ?: createTempDatabaseFile()
        )
    }
}


private
fun createTempDatabaseFile() =
    Files.createTempFile("gcc2speedscope", "db").toString()


fun writeSpeedscopeDocumentTo(
    writer: Writer,
    debugLogReader: Reader,
    prettyPrint: Boolean,
    databaseFile: String
): Unit = runBlocking {

    val channelCapacity = System.getenv("GCC2SS_CHANNEL_CAPACITY")?.toInt() ?: 1024

    // Launch `parser`
    val events = Channel<ParsedEvent>(channelCapacity)
    launch(Dispatchers.IO) {
        try {
            debugLogReader.useLines { lines ->
                for (e in configurationCacheEventsFromDebugLogLines(lines)) {
                    events.send(e)
                }
            }
        } finally {
            events.close()
        }
    }

    // Launch `aggregator`
    val aggregates = Channel<Aggregate>(channelCapacity)
    launch(Dispatchers.IO) {
        try {
            aggregateEvents(events, aggregates, databaseFile)
        } finally {
            aggregates.close()
        }
    }

    // Launch `writer`
    launch(Dispatchers.IO) {
        writeJsonTo(writer, aggregates, prettyPrint)
    }
}


private
suspend fun aggregateEvents(
    events: Channel<ParsedEvent>,
    aggregates: Channel<Aggregate>,
    databaseFile: String
) {
    EventStore(databaseFile).use { eventStore ->

        // Insert all events while notifying the writer whenever a new frame is discovered
        for (e in events) {
            eventStore.store(e)?.let { newFrame ->
                aggregates.send(Aggregate.Frame(newFrame))
            }
        }

        // Notify writer about all profiles
        for (p in eventStore.queryProfiles()) {
            aggregates.send(Aggregate.BeginProfile(p.name, p.lastValue))

            for (e in eventStore.eventsOf(p)) {
                aggregates.send(
                    Aggregate.Event(
                        e.type,
                        e.frameIndex - 1 /* db is one-based, output model is zero-based */,
                        e.at
                    )
                )
            }
        }
    }
}


private
suspend fun writeJsonTo(
    writer: Writer,
    aggregates: Channel<Aggregate>,
    prettyPrint: Boolean
) {
    JsonWriter(writer).run {
        if (prettyPrint) {
            setIndent("  ")
        }
        beginObject()
        name("exporter").value("gcc2speedscope@0.2.0")
        name("${'$'}schema").value("https://www.speedscope.app/file-format-schema.json")
        name("activeProfileIndex").value(0)

        var next: Aggregate?

        name("shared")
        run {
            beginObject()
            name("frames")
            beginArray()
            while (true) {
                when (val f = aggregates.receive()) {
                    is Aggregate.Frame -> {
                        beginObject()
                        name("name").value(f.name)
                        endObject()
                    }

                    else -> {
                        next = f
                        break
                    }
                }
            }
            endArray()
            endObject()
        }

        name("profiles")
        run {
            beginArray()
            while (next !== null) {
                val profile = next as Aggregate.BeginProfile
                val startValue = 0 // or should it be `events.first().payload.at` ?
                val endValue = profile.lastValue
                beginObject()
                name("type").value("evented")
                name("name").value(profile.name)
                name("unit").value("bytes")
                name("startValue").value(startValue)
                name("endValue").value(endValue)
                name("events")
                beginArray()
                while (true) {
                    when (val e = aggregates.receiveCatching().takeIf { !it.isClosed }?.getOrThrow()) {
                        is Aggregate.Event -> {
                            beginObject()
                            name("type").value(e.type)
                            name("frame").value(e.frameIndex)
                            name("at").value(e.at)
                            endObject()
                        }

                        else -> {
                            next = e
                            break
                        }
                    }
                }
                endArray()
                endObject()
            }
            endArray()
        }
        name("name").value("Gradle Configuration Cache Space Usage")
        endObject()
        flush()
    }
}


/**
 * Messages sent from [the event aggregator][aggregateEvents] are sent in the following order:
 *   `Frame+, (BeginProfile, Event+)+`
 */
private
sealed interface Aggregate {
    data class Frame(val name: String) : Aggregate
    data class BeginProfile(val name: String, val lastValue: Long) : Aggregate
    data class Event(val type: String, val frameIndex: Long, val at: Long) : Aggregate
}


data class ParsedEvent(
    val sequenceNumber: Long,
    val profile: String,
    val type: String,
    val frame: String,
    val at: Long
)


private
fun configurationCacheEventsFromDebugLogLines(lines: Sequence<String>) = sequence {
    // Example log line:
    // 2020-08-13T15:19:11.495-0300 [DEBUG] [org.gradle.configurationcache.DefaultConfigurationCache] {"profile":"state","type":"O","frame":"Gradle","at":6,"sn":1}
    val linePattern = logLinePattern()
    lines.forEachIndexed { index, line ->
        val matcher = linePattern.matcher(line)
        if (matcher.matches()) {
            val jsonEvent = matcher.group(1)
            try {
                val jsonObject = JsonParser.parseString(jsonEvent).asJsonObject
                yield(jsonObject.run {
                    ParsedEvent(
                        sequenceNumber = get("sn")!!.asLong,
                        profile = get("profile")!!.asString,
                        type = get("type")!!.asString,
                        frame = get("frame")!!.asString,
                        at = get("at")!!.asLong
                    )
                })
            } catch (e: JsonParseException) {
                throw IllegalArgumentException("line ${index + 1}: failed to parse $jsonEvent", e)
            }
        }
    }
}


private
fun logLinePattern(): Pattern {
    val logPrefix = "[DEBUG] [org.gradle.configurationcache.DefaultConfigurationCache]"
    return Pattern.compile("[0-9:T.\\-+]+ ${Regex.escape(logPrefix)} (\\{.*?})")
}
