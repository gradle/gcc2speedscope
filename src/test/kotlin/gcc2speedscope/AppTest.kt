package gcc2speedscope

import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTest {

    @Test
    fun `produces expected json`() {
        val log = parseConfigurationCacheDebugLog(bufferedReaderForResource("/debug.log"))
        assertEquals(
            bufferedReaderForResource("/expected.json").readText(),
            StringWriter().run {
                writeSpeedscopeDocumentFor(log)
                toString()
            }
        )
    }

    private
    fun bufferedReaderForResource(resource: String) =
        javaClass.getResourceAsStream(resource).bufferedReader()
}
