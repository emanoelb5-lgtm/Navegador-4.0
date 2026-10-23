package br.com.emanuel.navegador

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.*
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import org.hamcrest.Matchers.equalTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class BrowserSmokeTest {
    @Before fun clearSession() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("browser", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun menuAndTabsAreUsable() {
        ActivityScenario.launch(BrowserActivity::class.java).use {
            onView(withContentDescription("Menu")).perform(click())
            onView(withText("Favoritos")).check(matches(isDisplayed()))
            screenshot("menu")
            onView(withText("Favoritos")).perform(click())
            onView(withText("Suas páginas favoritas aparecem aqui.")).check(matches(isDisplayed()))
            pressBack()
            onView(withContentDescription("Abas")).perform(click())
            onView(withContentDescription("Nova aba")).perform(click())
            onView(withContentDescription("Abas")).perform(click())
            onView(withText("Suas abas · 2")).check(matches(isDisplayed()))
            pressBack(); screenshot("home")
        }
    }
    @Test fun blockedSchemesCannotLeaveTheAddressBar() {
        ActivityScenario.launch(BrowserActivity::class.java).use {
            onView(withContentDescription("Endereço e pesquisa")).perform(click())
            onView(withId(R.id.address_input)).perform(replaceText("javascript:alert(1)"))
            onView(withText("Ir")).perform(click())
            onView(withId(R.id.address_input)).check(matches(hasErrorText("Digite uma busca ou um endereço HTTP/HTTPS válido.")))
        }
    }
    @Test fun rendersPageNavigatesAndSavesBookmark() {
        val running = AtomicBoolean(true)
        val server = ServerSocket(0)
        val thread = Thread {
            while (running.get()) runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 4000
                    val input = socket.getInputStream().bufferedReader()
                    val request = input.readLine().orEmpty()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val second = request.contains("/second")
                    val body = "<html><head><title>${if(second) "Página B" else "Página A"}</title></head><body><h1 id='headline'>${if(second) "Segunda página" else "Página de teste"}</h1><a id='next' href='/second'>Continuar</a></body></html>".toByteArray()
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(body); output.flush()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            ActivityScenario.launch(BrowserActivity::class.java).use {
                onView(withContentDescription("Endereço e pesquisa")).perform(click())
                onView(withId(R.id.address_input)).perform(replaceText("http://127.0.0.1:${server.localPort}/"), pressImeActionButton())
                onWebView(withId(R.id.browser_view)).withElement(findElement(Locator.ID, "headline")).check(webMatches(getText(), equalTo("Página de teste")))
                onWebView(withId(R.id.browser_view)).withElement(findElement(Locator.ID, "next")).perform(webClick())
                onWebView(withId(R.id.browser_view)).withElement(findElement(Locator.ID, "headline")).check(webMatches(getText(), equalTo("Segunda página")))
                onView(withContentDescription("Menu")).perform(click())
                onView(withText("Salvar")).perform(click())
                onView(withContentDescription("Menu")).perform(click())
                onView(withText("Favoritos")).perform(click())
                onView(withText("Página B")).check(matches(isDisplayed()))
                pressBack()
                onView(withContentDescription("Voltar")).perform(click())
                onWebView(withId(R.id.browser_view)).withElement(findElement(Locator.ID, "headline")).check(webMatches(getText(), equalTo("Página de teste")))
            }
        } finally { running.set(false); server.close(); thread.join(1000) }
    }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
