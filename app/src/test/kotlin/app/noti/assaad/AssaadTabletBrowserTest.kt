package app.noti.assaad

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.webkit.WebSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssaadTabletBrowserTest {
    @Test fun onlyTheRealHttpsPlatformOriginIsTrustedForMessagePorts() {
        assertTrue(trustedAssaadPage(ASSAAD_WEBSITE + "/banco-de-questoes/explorar"))
        for (value in listOf("http://app.plataformaassaad.com.br/", "https://app.plataformaassaad.com.br.evil.example/", "https://example.com/", "file:///private", "https://name@app.plataformaassaad.com.br/")) assertFalse(trustedAssaadPage(value))
    }
    @Test fun packagedBrowserKeepsNormalWebSecurityAndSharesTheExtensionAdapter() {
        val context = RuntimeEnvironment.getApplication()
        val browser = AssaadTabletBrowser(context) { _, _, _ -> }
        try {
            assertTrue(browser.view.settings.javaScriptEnabled)
            assertTrue(browser.view.settings.domStorageEnabled)
            assertFalse(browser.view.settings.allowFileAccess)
            assertFalse(browser.view.settings.allowContentAccess)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, browser.view.settings.mixedContentMode)
            val adapter = context.assets.open("platform-api.js").bufferedReader().use { it.readText() }
            assertTrue(adapter.contains("runAssaadOperation"))
            assertTrue(adapter.contains("/question/metadata"))
        } finally { browser.destroy() }
    }
}
