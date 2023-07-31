package gcc2speedscope

import org.junit.jupiter.api.io.TempDir
import java.io.Reader
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTest {

    @Test
    fun `produces expected json`(@TempDir tempDir: Path) {
        val outputFile = tempDir.resolve("actual.json")
        writeSpeedscopeDocumentFor(
            bufferedReaderForResource("/debug.log"),
            outputFile,
            prettyPrint = true
        )

        // uncomment to update expected file
        // java.io.File("./src/test/resources/expected.json").writeText(speedscopeDocument)
        assertEquals(
            bufferedReaderForResource("/expected.json").readText(),
            outputFile.toFile().readText()
        )
    }

    private
    fun bufferedReaderForResource(resource: String) =
        javaClass.getResourceAsStream(resource)!!.bufferedReader()
}


internal
fun writeSpeedscopeDocumentFor(debugLogReader: Reader, outputFile: Path, prettyPrint: Boolean = false) {
    outputFile.toFile().bufferedWriter().use { writer ->
        writeSpeedscopeDocumentTo(writer, debugLogReader, prettyPrint, "$outputFile.db")
    }
}
