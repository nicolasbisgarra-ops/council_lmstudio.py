package io.trazo.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewWebPageTest {
    private val review = SharedReview(
        title = "Hoja <1>",
        messages = listOf(
            SharedMessage(fromStudent = true, text = "¿Está bien?\n<script>alert('x')</script>", line = 2),
            SharedMessage(fromStudent = false, text = "Revisá el signo & \"el\" paso", correct = false),
            SharedMessage(fromStudent = false, text = "falló", error = true),
        ),
        notes = listOf(SharedNote(3, "signo", "<b>mal</b>", "detalle 'x'", "poné +")),
        updatedAt = 42L,
    )

    @Test fun htmlEscapesEverything() {
        val html = ReviewWebPage.html(review, "tok", 15)
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<b>mal</b>"))
        assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"))
        assertTrue(html.contains("Hoja &lt;1&gt;"))
        assertTrue(html.contains("&amp; &quot;el&quot;"))
        assertTrue(html.contains("src=\"/tok/page.png?t=42\""))
        assertTrue(html.contains("http-equiv=\"refresh\" content=\"15\""))
        assertTrue(html.contains("Hay un error"))
        assertTrue(html.contains("msg err"))
        assertFalse(ReviewWebPage.html(review, "tok", 0).contains("http-equiv"))
        assertEquals("&amp;&lt;&gt;&quot;&#39;", ReviewWebPage.escape("&<>\"'"))
    }
}
