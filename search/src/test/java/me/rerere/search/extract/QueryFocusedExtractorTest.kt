package me.rerere.search.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class QueryFocusedExtractorTest {

    private val football = "Матч по футболу закончился вничью, и тренер остался недоволен игрой полузащиты во втором тайме. ".repeat(6)
    private val android = "Команда выпустила новую версию приложения с поддержкой офлайн режима работы и ускоренной синхронизацией. ".repeat(6)
    private val cooking = "Кулинарный рецепт борща включает свёклу, капусту, картофель и наваристый мясной бульон для подачи. ".repeat(6)
    private val doc = "$football\n\n$android\n\n$cooking"

    private val enFootball = "The football match ended in a draw and the coach was unhappy with the midfield display. ".repeat(6)
    private val enKernel = "KernelSU grants direct root access on Android without Magisk and the kernel module is patched at boot. ".repeat(6)
    private val enCooking = "The borscht recipe calls for beetroot, cabbage, potatoes and a rich beef broth for serving. ".repeat(6)
    private val enDoc = "$enFootball\n\n$enKernel\n\n$enCooking"

    private fun quantumChunk(i: Int): String =
        "Квантовые вычисления и кубиты подробно разбираются в этом разделе статьи. ".repeat(4) +
            (1..30).joinToString(" ") { "деталь${i}номер$it" }

    @Test
    fun `russian query selects the russian passage`() {
        val result = QueryFocusedExtractor.focus(doc, "офлайн режима работы приложения")

        assertTrue(result.text.contains("Команда выпустила"))
        assertFalse(result.text.contains("футболу"))
        assertFalse(result.text.contains("борща"))
        assertEquals(3, result.chunksTotal)
        assertEquals(1, result.chunksSelected)
        assertTrue(result.focused)
        assertFalse(result.fallbackUsed)
        assertEquals(doc.length, result.originalChars)
        assertEquals(result.text.length, result.returnedChars)
    }

    @Test
    fun `english query selects the english passage`() {
        val result = QueryFocusedExtractor.focus(enDoc, "direct root access without Magisk")

        assertTrue(result.text.contains("KernelSU"))
        assertFalse(result.text.contains("midfield"))
        assertFalse(result.text.contains("borscht"))
        assertEquals(1, result.chunksSelected)
    }

    @Test
    fun `mixed russian and english query selects the mixed passage`() {
        val result = QueryFocusedExtractor.focus(enDoc, "KernelSU root доступ")

        assertTrue(result.text.contains("KernelSU"))
        assertFalse(result.text.contains("borscht"))
        assertFalse(result.text.contains("midfield"))
        assertEquals(1, result.chunksSelected)
    }

    @Test
    fun `irrelevant passages score lower than relevant ones`() {
        val ranked = QueryFocusedExtractor.rank(doc, "офлайн режима работы")

        assertEquals(3, ranked.size)
        assertEquals(listOf(0, 1, 2), ranked.map { it.index })
        val relevant = ranked[1]
        val unrelated = ranked[0]
        assertTrue(relevant.score > unrelated.score)
        assertEquals(0.0, unrelated.score, 0.0)
    }

    @Test
    fun `selected passages keep the document order`() {
        val result = QueryFocusedExtractor.focus(doc, "полузащиты борща рецепт")

        assertTrue(result.text.contains("футболу"))
        assertTrue(result.text.contains("борща"))
        assertTrue(result.text.indexOf("футболу") < result.text.indexOf("борща"))
        assertEquals(2, result.chunksSelected)
    }

    @Test
    fun `top K limits the number of selected passages`() {
        val many = (1..12).joinToString("\n\n") { quantumChunk(it) }

        val limited = QueryFocusedExtractor.focus(many, "квантовые вычисления кубиты", topK = 3)
        assertEquals(3, limited.chunksSelected)
        assertTrue(limited.chunksTotal >= 10)

        val byDefault = QueryFocusedExtractor.focus(many, "квантовые вычисления кубиты")
        assertEquals(QueryFocusedExtractor.DEFAULT_TOP_K, byDefault.chunksSelected)
    }

    @Test
    fun `char budget bounds the returned text`() {
        val many = (1..40).joinToString("\n\n") { quantumChunk(it) }

        val result = QueryFocusedExtractor.focus(many, "квантовые вычисления кубиты", charBudget = 2000)

        assertTrue("returned ${result.returnedChars}", result.returnedChars <= 2000)
        assertTrue(result.text.isNotEmpty())
        assertTrue(result.originalChars > 2000)
    }

    @Test
    fun `default budget and top K are the documented values`() {
        assertEquals(12 * 1024, QueryFocusedExtractor.FOCUS_CHAR_BUDGET)
        assertEquals(8, QueryFocusedExtractor.DEFAULT_TOP_K)
    }

    @Test
    fun `a caller budget below the default is respected`() {
        val many = (1..12).joinToString("\n\n") { quantumChunk(it) }

        val result = QueryFocusedExtractor.focus(many, "квантовые вычисления кубиты", charBudget = 900)

        assertTrue(result.returnedChars <= 900)
        assertTrue(result.returnedChars > 0)
    }

    @Test
    fun `small document is returned intact`() {
        val small = "Короткий текст про квантовые вычисления."

        val result = QueryFocusedExtractor.focus(small, "квантовые вычисления")

        assertEquals(small, result.text)
        assertEquals(small.length, result.returnedChars)
        assertEquals(1, result.chunksTotal)
        assertEquals(1, result.chunksSelected)
    }

    @Test
    fun `empty document yields an empty focused result`() {
        val result = QueryFocusedExtractor.focus("", "квантовые вычисления")

        assertEquals("", result.text)
        assertEquals(0, result.chunksTotal)
        assertEquals(0, result.chunksSelected)
        assertEquals(0, result.returnedChars)
    }

    @Test
    fun `blank focus returns the document unchanged`() {
        val result = QueryFocusedExtractor.focus(doc, "   ")

        assertFalse(result.focused)
        assertFalse(result.fallbackUsed)
        assertEquals(doc.trim(), result.text)
    }

    @Test
    fun `unicode cyrillic matches decomposed input`() {
        val text = "Ёлка стояла в углу комнаты и радовала детей гирляндами и игрушками. ".repeat(6)
        val decomposed = Normalizer.normalize("ёлка", Normalizer.Form.NFD)

        val result = QueryFocusedExtractor.focus(text, decomposed)

        assertTrue(result.text.contains("Ёлка"))
        assertEquals(1, result.chunksSelected)
    }

    @Test
    fun `duplicate paragraphs are de-duplicated before ranking`() {
        val para = "Квантовые вычисления требуют охлаждения и стабильных кубитов в лаборатории. ".repeat(6)
        val withDuplicate = "$para\n\n$para\n\n$football"

        val result = QueryFocusedExtractor.focus(withDuplicate, "квантовые вычисления")

        assertEquals(2, result.chunksTotal)
        assertEquals(1, result.chunksSelected)
        assertFalse(result.text.contains("футболу"))
    }

    @Test
    fun `near duplicate passages are dropped from the selection`() {
        val base = "Квантовые вычисления требуют стабильных кубитов и глубокого охлаждения в лаборатории. ".repeat(6)
        val nearDuplicate = base + "Дополнительное предложение про калибровку стенда."
        val document = "$base\n\n$nearDuplicate\n\n$football"

        val result = QueryFocusedExtractor.focus(document, "квантовые вычисления кубиты")

        assertEquals(3, result.chunksTotal)
        assertEquals(1, result.chunksSelected)
        assertFalse(result.text.contains("футболу"))
    }

    @Test
    fun `zero match falls back to the leading passages`() {
        val result = QueryFocusedExtractor.focus(doc, "гидроцикл карбюратор")

        assertTrue(result.fallbackUsed)
        assertTrue(result.text.isNotEmpty())
        assertTrue(result.text.contains("футболу"))
        assertEquals(3, result.chunksSelected)
    }

    @Test
    fun `relevant content after the first 32K characters can still be selected`() {
        val filler = "Заполняющий абзац про погоду и природу с достаточно длинным текстом. ".repeat(14)
        val late = "Гидроцикл с карбюратором требует обслуживания перед сезоном навигации. ".repeat(6)
        val source = (1..60).joinToString("\n\n") { "$filler абзац $it" } + "\n\n" + late

        val lateIndex = source.indexOf("Гидроцикл")
        assertTrue("the late passage should start past 32K but was at $lateIndex", lateIndex > 32_768)

        val result = QueryFocusedExtractor.focus(source, "гидроцикл карбюратором")

        assertTrue(result.text.contains("Гидроцикл"))
        assertEquals(1, result.chunksSelected)
    }

    @Test
    fun `very long input stays inside the budget`() {
        val huge = (1..400).joinToString("\n\n") { quantumChunk(it) }
        assertTrue(huge.length > 32 * 1024)

        val result = QueryFocusedExtractor.focus(huge, "квантовые вычисления кубиты")

        assertTrue(result.returnedChars <= QueryFocusedExtractor.FOCUS_CHAR_BUDGET)
        assertTrue(result.chunksTotal >= 300)
        assertTrue(result.text.isNotEmpty())
    }

    @Test
    fun `output is deterministic`() {
        val first = QueryFocusedExtractor.focus(doc, "офлайн режима работы")
        val second = QueryFocusedExtractor.focus(doc, "офлайн режима работы")

        assertEquals(first, second)
        assertEquals(first.text, second.text)
    }

    @Test
    fun `chunks stay inside the size bounds`() {
        val long = (1..150).joinToString(" ") {
            "Предложение номер $it с достаточно длинным содержимым для нарезки."
        }

        val ranked = QueryFocusedExtractor.rank(long, "предложение")

        assertTrue(ranked.size >= 4)
        for (chunk in ranked) {
            assertTrue("chunk ${chunk.index} is ${chunk.text.length} chars", chunk.text.length in 1..1600)
        }
    }

    @Test
    fun `tokenizer normalises case script and numbers`() {
        assertEquals(
            listOf("kernelsu", "root", "доступ", "2026"),
            QueryFocusedExtractor.tokenize("KernelSU ROOT доступ 2026"),
        )
        assertEquals(listOf("ёлка"), QueryFocusedExtractor.tokenize("ЁЛКА"))
        assertTrue(QueryFocusedExtractor.tokenize("!!! ,,, ...").isEmpty())
    }

    @Test
    fun `no focus is applied when the query has no usable token`() {
        val ranked = QueryFocusedExtractor.rank(doc, "!!! ???")
        assertEquals(3, ranked.size)
        assertTrue(ranked.all { it.score == 0.0 })
    }
}
