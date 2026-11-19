package app.noti.openrouter

import app.noti.openrouter.OpenRouterMessage.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class OpenRouterLiveTest {
    @Test
    fun hy4FlashCompletesTextAndStreamsImageAnalysisAsJson() = runBlocking {
        val key = System.getenv("NOTI_OPENROUTER_TEST_KEY").orEmpty()
        assumeTrue("Live test requires NOTI_OPENROUTER_TEST_KEY", key.isNotBlank())
        val client = OpenRouterClient()
        val probe = withTimeout(45_000) { client.testConnection(key) }
        assertEquals(OpenRouterClient.MODEL, probe.model)
        assertTrue(probe.text.contains("OK", ignoreCase = true))

        val schema = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("color", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("color"))
            .put("additionalProperties", false)
        val input = OpenRouterRequest(
            messages = listOf(OpenRouterMessage(Role.User, listOf(
                OpenRouterPart.Text("Identify the dominant color. Use one common English color word."),
                OpenRouterPart.Image(ImageMediaType.Png, redImage()),
            ))),
            maxCompletionTokens = 128, schema = OpenRouterSchema("image_color", schema), reasoning = false,
        )
        val events = withTimeout(45_000) { client.stream(key, input).toList() }
        val result = (events.last() as OpenRouterStreamEvent.Finished).completion
        assertEquals(OpenRouterClient.MODEL, result.model)
        assertEquals("red", JSONObject(result.text).getString("color").lowercase())
        assertTrue((result.usage?.totalTokens ?: 0) > 0)
    }

    private fun redImage(): ByteArray {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.RED
            graphics.fillRect(0, 0, image.width, image.height)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().apply { ImageIO.write(image, "png", this) }.toByteArray()
    }
}
