package gcc2speedscope

import java.io.BufferedReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTest {

    @Test
    fun `produces expected json`() {
        val speedscopeDocument = speedscopeDocumentFor(bufferedReaderForResource("/debug.log"))
        // uncomment to update expected file
        // java.io.File("./src/test/resources/expected.json").writeText(speedscopeDocument)
        assertEquals(
            bufferedReaderForResource("/expected.json").readText(),
            speedscopeDocument
        )
    }

    private
    fun speedscopeDocumentFor(debugLog: BufferedReader): String = StringWriter().run {
        writeSpeedscopeDocumentFor(parseConfigurationCacheDebugLog(debugLog), prettyPrint = true)
        toString()
    }

    private
    fun bufferedReaderForResource(resource: String) =
        javaClass.getResourceAsStream(resource).bufferedReader()
}
