package io.trazo.app.data
import android.app.Application
import io.trazo.ai.AiTokenUsage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class AiJournalUsageTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun actualUsageAndRequestSizesSurviveReopen() {
        val dir = temp.newFolder("usage")
        val expected = AiSend(1, "Test", "model", "book", 1, 3, 40000, false, 100,
            textChars = 900, imageWidth = 1568, imageHeight = 1000,
            usage = AiTokenUsage(35000, 400, 300, 1000, null))
        AiJournal(dir).record(expected)
        assertEquals(expected, AiJournal(dir).log.value.single())
        assertNull(AiJournal(dir).log.value.single().usage!!.imageInputTokens)
    }
    @Test fun oldRecordsHaveUnknownCountersInsteadOfInventedZeroes() {
        val dir = temp.newFolder("old")
        File(dir, "ai_log.json").writeText("""[{"at":1,"profile":"old","page":1,"lines":1,"imageBytes":1024,"millis":3}]""")
        val record = AiJournal(dir).log.value.single()
        assertNull(record.textChars); assertNull(record.usage); assertNull(record.imageWidth)
    }
}
