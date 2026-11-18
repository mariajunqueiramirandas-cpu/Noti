package app.noti.assaad

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssaadMediaCacheTest {
    @Test fun savedMediaIsAvailableAfterRecreatingTheCacheWithoutNetworkOrCredentials() = runBlocking {
        var requests = 0
        val bytes = byteArrayOf(1, 2, 3, 4)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertNull(chain.request().header("Authorization")); assertNull(chain.request().header("Cookie"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "image/png").body(bytes.toResponseBody("image/png".toMediaType())).build()
        }.build()
        val context = RuntimeEnvironment.getApplication()
        val url = "https://cdn.example.com/" + System.nanoTime() + ".png"
        val first = AssaadMediaCache(context, http)
        assertArrayEquals(bytes, first.load(url)); assertTrue(first.contains(url))
        val second = AssaadMediaCache(context, http)
        assertArrayEquals(bytes, second.load(url)); assertEquals(1, requests)
        assertNull(second.load("https://localhost/private.png")); assertEquals(1, requests)
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
}
