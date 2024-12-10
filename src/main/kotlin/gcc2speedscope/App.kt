package gcc2speedscope

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.Reader
import java.io.Writer
import java.lang.System.getenv
import java.nio.file.Files
import java.nio.file.Files.newBufferedReader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.system.exitProcess


val DEFAULT_EVENT_FILTER: ParsedEvent.() -> Boolean = { true }
val DEFAULT_CHANNEL_CAPACITY = 1024

fun main(args: Array<String>) {
    if (args.size != 1) {
        println("gcc2speedscope (<Gradle debug log file> | --)")
        exitProcess(1)
    }

    val debugLogFileName = args[0]

    processDebugLog(debugLogFileName)
}

private fun processDebugLog(debugLogFileName: String) {
    val debugLogReader = when (debugLogFileName) {
        "--" -> System.`in`.bufferedReader()
        else -> newBufferedReader(Paths.get(debugLogFileName))
    }

    val databaseDir = getenv("GCC2SS_DATA_DIR")?.let(::File)?.toPath() ?: Files.createTempDirectory("gcc2speedscope")

    val eventDatabaseFile = getenv("GCC2SS_DATABASE")?.let(Paths::get)
        ?: databaseDir.resolve("events.db")

    val channelCapacity = getenv("GCC2SS_CHANNEL_CAPACITY")?.toInt()

    val parsedEventFilter: (ParsedEvent.() -> Boolean)? =
        getenv("GCC2SS_INCLUDE")
            ?.let(Pattern::compile)
            ?.let { pattern -> { pattern.matcher(frame).matches() } }

    processDebugLog(
        Options(
            debugLogReader = debugLogReader,
            speedscopeWriter = System.out.bufferedWriter(),
            prettyPrint = getenv("GCC2SS_PRETTY_PRINT") !== null,
            eventDatabaseFile = eventDatabaseFile,
            eventFilter = parsedEventFilter ?: DEFAULT_EVENT_FILTER,
            channelCapacity = channelCapacity ?: DEFAULT_CHANNEL_CAPACITY
        )
    )
}


data class Options(
    val debugLogReader: Reader,
    val speedscopeWriter: Writer,
    val prettyPrint: Boolean,
    val eventDatabaseFile: Path,
    val eventFilter: (ParsedEvent.() -> Boolean),
    val channelCapacity: Int
)


fun writeSpeedscopeDocumentTo(
    writer: Writer,
    debugLogReader: Reader,
    prettyPrint: Boolean,
    databaseFile: String
): Unit =
    processDebugLog(Options(
        debugLogReader = debugLogReader,
        speedscopeWriter = writer,
        prettyPrint = prettyPrint,
        eventDatabaseFile = Paths.get(databaseFile),
        eventFilter = DEFAULT_EVENT_FILTER,
        channelCapacity = DEFAULT_CHANNEL_CAPACITY
    ))


fun processDebugLog(
    options: Options
): Unit = runBlocking {

    val mutableSharedFlow = MutableSharedFlow<String?>(replay = Int.MAX_VALUE)
    options.debugLogReader.useLines { lines ->
        lines.forEach { line ->
            mutableSharedFlow.emit(line)
        }
        mutableSharedFlow.emit(null)
    }
    val linesFlow = mutableSharedFlow
    val linePattern = logLinePattern()
    val matchesFlow = linesFlow.map { it?.let(linePattern::matcher) }.filter { it == null || it.matches() }
    // Launch `parser`

    var processed = 0
    val parsed = configurationCacheEventsFromDebugLogLines(matchesFlow)
        .takeWhile { it != null }
        .map { it!! }
        .filter { options.eventFilter(it) }
        .onEach { processed++ }
        .onCompletion {
            require(processed > 0) {
                "Could not recognize a single line from input"
            }
        }

    // Launch `aggregator`
    val aggregates = Channel<Aggregate>(options.channelCapacity)
    launch(Dispatchers.IO) {
        aggregates.use {
            aggregateEvents(parsed, options.eventDatabaseFile)
        }
    }

    // Launch `writer`
    launch(Dispatchers.IO) {
        writeJsonTo(options.speedscopeWriter, aggregates, options.prettyPrint)
        options.speedscopeWriter.flush()
    }

    launch(Dispatchers.IO) {
        val collector = DefaultCollector()
        buildInstanceStats(parsed.filter { it.oid != null }).map { it!! }.collect {
            collector.collect(it.context, it.objectType, it.oid, it.hash, it.length)
        }
        collector.printStats()
    }
}

data class InstanceStats(val context: String, val objectType: String, val oid: String, val hash: String, val length: Long)

private suspend fun buildInstanceStats(parsedEvents: Flow<ParsedEvent?>): Flow<InstanceStats?> {
    data class InstanceData(
        val context: Context,
        val frame: String,
        val oid: String,
        val hash: String,
        val offset: Long
    )

    val stack = mutableListOf<InstanceData>()
    val instanceStats = flow {
        parsedEvents
            .map { it!! }
            .collect { event ->
                when (event.type) {
                    "O" -> stack.add(InstanceData(event.profile, event.frame, event.oid!!, event.hash!!, event.at))
                    "C" -> {
                        val top = stack.removeLast()
                        require(top.oid == event.oid)
                        require(top.hash == event.hash)
                        require(top.context == event.profile)
                        emit(InstanceStats(top.context, top.frame, top.oid, top.hash, event.at - top.offset))
                    }
                    else -> error("Unexpected type ${event.type}")
                }
            }
    }
    require(stack.isEmpty())
    return instanceStats
}


private
suspend fun <T> Channel<T>.use(action: suspend Channel<T>.() -> Unit) {
    try {
        action()
    } finally {
        close()
    }
}


private
suspend fun Channel<Aggregate>.aggregateEvents(
    events: Flow<ParsedEvent?>,
    databaseFile: Path
) {
    EventStore(databaseFile).use { eventStore ->

        // Insert all events while notifying the writer whenever a new frame is discovered
        events.collect { e ->
            if (e != null) {
                eventStore.store(e)?.let { newFrame ->
                    send(Aggregate.Frame(newFrame))
                }
            }
        }

        // Notify writer about all profiles
        for (p in eventStore.queryProfiles()) {
            send(Aggregate.BeginProfile(p.name, p.lastValue))

            for (e in eventStore.eventsOf(p)) {
                send(
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
    val at: Long,
    val oid: String?,
    val hash: String?
)

private
fun configurationCacheEventsFromDebugLogLines(matchers: Flow<Matcher?>): Flow<ParsedEvent?> {
    return matchers
        .map { matcher ->
            matcher?.group(1)?.let { jsonEvent ->
                try {
                    JsonParser
                        .parseString(jsonEvent)
                        .asJsonObject.run {
                            ParsedEvent(
                                sequenceNumber = getLong("sn"),
                                profile = getString("profile"),
                                type = getString("type"),
                                frame = getString("frame"),
                                at = getLong("at"),
                                oid = getStringOrNull("oid"),
                                hash = getStringOrNull("hash")
                            )
                        }
                } catch (e: JsonParseException) {
                    throw IllegalArgumentException("failed to parse $jsonEvent", e)
                }
            }
        }
}



private
fun JsonObject.getString(memberName: String): String = get(memberName)!!.asString

private
fun JsonObject.getStringOrNull(memberName: String): String? = get(memberName)?.asString


private
fun JsonObject.getLong(memberName: String) = get(memberName)!!.asLong


private
fun logLinePattern(): Pattern {
    // Example log line:
    // 2020-08-13T15:19:11.495-0300 [DEBUG] [org.gradle.configurationcache...] {"profile":"state","type":"O","frame":"Gradle","at":6,"sn":1}
    return Pattern.compile("[0-9:T.\\-+]+ \\[DEBUG\\] \\[org\\.gradle\\.configurationcache(?:\\.DefaultConfigurationCache)?\\] (\\{.*?})")
}

