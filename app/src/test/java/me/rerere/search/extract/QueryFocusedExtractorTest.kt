package me.rerere.search.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Query-focused extraction: block selection, ordering, de-duplication, the cap, the safe
 * fallback and determinism. All pure JVM — no network, no Android, no fixture files.
 */
class QueryFocusedExtractorTest {

    private val menu = "Главная О компании Услуги Контакты Карьера Блог Поддержка Вход"

    private val releaseArticle = listOf(
        "Главная О компании Услуги Контакты Карьера Блог Поддержка Вход Регистрация",
        "В версии 2.5.1 появились важные изменения в подсистеме синхронизации. " +
            "Теперь очередь миграций применяется строго по порядку, а старые базы данных " +
            "обновляются без потери пользовательских сообщений.",
        "Наша команда рада предложить вам скидки на подписку в этом месяце. " +
            "Подпишитесь до конца недели и получите дополнительные возможности бесплатно.",
        "Также в релизе 2.5.1 исправлена ошибка, из-за которой длинные вложения не " +
            "отображались в списке чатов после перезапуска приложения.",
        "Следите за новостями в нашем блоге и подписывайтесь на рассылку, чтобы не " +
            "пропустить интересные предложения и акции нашего сервиса.",
    ).joinToString("\n\n")

    private val englishArticle = listOf(
        "Home About Products Pricing Careers Blog Support Sign in",
        "The 2.5.1 release changes how the migration queue is applied: steps now run " +
            "strictly in order, and older databases are upgraded without losing any " +
            "user messages stored on the device.",
        "Subscribe to our newsletter this month and get a discount on your subscription " +
            "plan, including extra storage and priority support from our team.",
        "Another 2.5.1 fix addresses attachments that failed to render in the chat list " +
            "after the application was restarted from a cold start.",
    ).joinToString("\n\n")

    @Test
    fun `relevant paragraph wins over the menu and the promo blocks`() {
        val result = QueryFocusedExtractor.focus(
            text = releaseArticle,
            focus = "какие изменения появились в версии 2.5.1",
            maxChars = 8_000,
        )

        assertTrue(result.applied)
        assertTrue("the release paragraph must survive", result.text.contains("подсистеме синхронизации"))
        assertTrue("the second release paragraph must survive", result.text.contains("длинные вложения"))
        assertFalse("the navigation menu must not be selected", result.text.contains("Карьера"))
        assertFalse("the promo block must not be selected", result.text.contains("скидки на подписку"))
    }

    @Test
    fun `russian question finds the russian paragraph`() {
        val result = QueryFocusedExtractor.focus(
            text = releaseArticle,
            focus = "что случилось с вложениями после перезапуска",
            maxChars = 8_000,
        )

        assertTrue(result.applied)
        assertTrue(result.text.contains("длинные вложения"))
        assertFalse(result.text.contains("рассылку"))
    }

    @Test
    fun `english question finds the english paragraph`() {
        val result = QueryFocusedExtractor.focus(
            text = englishArticle,
            focus = "how does the migration queue behave in 2.5.1",
            maxChars = 8_000,
        )

        assertTrue(result.applied)
        assertTrue(result.text.contains("migration queue"))
        assertFalse(result.text.contains("Sign in"))
    }

    @Test
    fun `selected blocks keep the original document order`() {
        val result = QueryFocusedExtractor.focus(
            text = releaseArticle,
            focus = "2.5.1 изменения вложения синхронизация",
            maxChars = 8_000,
        )

        assertTrue(result.applied)
        val sync = result.text.indexOf("подсистеме синхронизации")
        val attach = result.text.indexOf("длинные вложения")
        assertTrue(sync in 0 until attach)
    }

    @Test
    fun `duplicate paragraphs are collapsed`() {
        val repeated = ("Unique lead sentence about widgets and gears, long enough to count.\n\n" +
            "The answer paragraph explains the migration behaviour in detail for 2.5.1 users " +
            "and mentions the queue ordering explicitly.\n\n").repeat(2)

        val result = QueryFocusedExtractor.focus(
            text = repeated,
            focus = "migration queue 2.5.1",
            maxChars = 8_000,
        )

        assertTrue(result.applied)
        assertEquals(1, result.text.split("queue ordering explicitly").size - 1)
    }

    @Test
    fun `max chars is respected`() {
        val result = QueryFocusedExtractor.focus(
            text = releaseArticle,
            focus = "2.5.1 изменения",
            maxChars = 60,
        )

        assertTrue(result.text.length <= 60)
    }

    @Test
    fun `empty focus preserves the previous behaviour`() {
        val result = QueryFocusedExtractor.focus(
            text = releaseArticle,
            focus = "   ",
            maxChars = 8_000,
        )

        assertFalse(result.applied)
        assertEquals(releaseArticle, result.text)
        assertEquals(0, result.selectedBlocks)
    }

    @Test
    fun `focus without a single match falls back instead of returning nothing`() {
        val result = QueryFocusedExtractor.focus(
            text = releaseArticle,
            focus = "квантовая запутанность в кулинарии",
            maxChars = 8_000,
        )

        assertFalse(result.applied)
        assertEquals(releaseArticle, result.text)
    }

    @Test
    fun `focus made only of stop words falls back`() {
        val result = QueryFocusedExtractor.focus(
            text = releaseArticle,
            focus = "what is the and or of",
            maxChars = 8_000,
        )

        assertFalse(result.applied)
        assertEquals(releaseArticle, result.text)
    }

    @Test
    fun `result is deterministic across runs`() {
        val a = QueryFocusedExtractor.focus(releaseArticle, "2.5.1 вложения", 8_000)
        val b = QueryFocusedExtractor.focus(releaseArticle, "2.5.1 вложения", 8_000)
        val c = QueryFocusedExtractor.focus(releaseArticle, "2.5.1 вложения", 8_000)

        assertEquals(a.text, b.text)
        assertEquals(b.text, c.text)
        assertEquals(a.selectedBlocks, c.selectedBlocks)
    }
}
