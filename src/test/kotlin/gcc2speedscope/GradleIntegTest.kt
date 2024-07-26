package gcc2speedscope

import org.everit.json.schema.Schema
import org.everit.json.schema.loader.SchemaLoader
import org.gradle.tooling.GradleConnector
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.InputStream
import java.net.URL


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GradleIntegTest {

    @ParameterizedTest
    @ValueSource(strings = ["7.3", "8.2.1", "8.8", "8.9", "8.10-20240723224155+0000"])
    fun `produces expected json from Gradle debug log`(gradleVersion: String, @TempDir tempDir: File) {
        // given:
        val projectDir = tempDir.resolve("project")
        val debugLog = tempDir.resolve("debug.log")
        projectDir.run {
            mkdirs()
            resolve("settings.gradle").writeText("")
            resolve("build.gradle").writeText(
                """
                    plugins { id 'java-library' }
                    repositories { mavenCentral() }
                    dependencies { implementation 'org.apache.commons:commons-text:1.10.0' }
                """
            )
            resolve("src/main/java/acme/Lib.java").run {
                parentFile.mkdirs()
                writeText("package acme; public class Lib {}")
            }
        }
        // when:
        debugLog.outputStream().buffered().use { debugLogOutputStream ->
            GradleConnector.newConnector()
                .forProjectDirectory(projectDir)
                .useGradleVersion(gradleVersion)
                .connect().use {
                    it.newBuild()
                        .forTasks("assemble")
                        .withArguments("--configuration-cache", "-d")
                        .setStandardOutput(debugLogOutputStream)
                        .setStandardError(System.err)
                        .run()
                }
        }
        // and:
        val outputFile = tempDir.resolve("output.json")
        debugLog.bufferedReader().use { debugLogReader ->
            writeSpeedscopeDocumentFor(
                debugLogReader,
                outputFile.toPath(),
                prettyPrint = true
            )
        }
        // then:
        assert(outputFile.isFile)
        validateJsonSchemaOf(outputFile)
    }

    private
    fun validateJsonSchemaOf(jsonFile: File) {
        schema.validate(jsonObjectFrom(jsonFile.inputStream()))
    }

    private
    val schema by lazy {
        schema("https://www.speedscope.app/file-format-schema.json")
    }

    private
    fun schema(url: String): Schema = SchemaLoader.builder()
        .schemaJson(jsonObjectFromURL(url))
        .draftV7Support()
        .build()
        .load()
        .build()

    private
    fun jsonObjectFromURL(url: String): JSONObject =
        jsonObjectFrom(URL(url).openStream())

    private
    fun jsonObjectFrom(stream: InputStream): JSONObject =
        stream.use { JSONObject(JSONTokener(it)) }
}