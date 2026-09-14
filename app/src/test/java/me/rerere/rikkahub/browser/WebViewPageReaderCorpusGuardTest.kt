package me.rerere.rikkahub.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guard for the research corpus traversal in [WebViewPageReader].
 *
 * The behaviour itself is covered by `WebViewResearchInstrumentedTest` on an emulator, because the
 * traversal is JavaScript and a JVM has no WebView. That job takes minutes; this test costs
 * milliseconds and locks the exact regression that made whole article sections disappear from
 * ranking: `hidden="until-found"` is an enumerated attribute, so `node.hidden` - its IDL
 * reflection - is **truthy** for prose the engine keeps reachable. The traversal must branch on
 * `getAttribute('hidden')` instead.
 *
 * It asserts the shape of the guard, not its output, so a rewrite that keeps the semantics is free
 * to change spelling; only reintroducing the truthiness check fails.
 */
class WebViewPageReaderCorpusGuardTest {

    private val corpusJs: String by lazy {
        val relative = "app/src/main/java/me/rerere/rikkahub/browser/WebViewPageReader.kt"
        val file = listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull { it.isFile }
            ?: error("WebViewPageReader.kt not found from ${File(".").absolutePath}")
        val source = file.readText()

        val functionStart = source.indexOf("private suspend fun readResearchCorpus")
        assertTrue("readResearchCorpus must exist in the reader", functionStart >= 0)
        val literalStart = source.indexOf("val js = \"\"\"", functionStart)
        assertTrue("readResearchCorpus must embed its traversal as a raw string", literalStart >= 0)
        val payloadStart = literalStart + "val js = \"\"\"".length
        val payloadEnd = source.indexOf("\"\"\"", payloadStart)
        assertTrue("the raw string must be terminated", payloadEnd > payloadStart)

        // Comments are prose, not behaviour: drop them so a comment that explains the bug cannot
        // itself trip the assertions below.
        source.substring(payloadStart, payloadEnd)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    @Test
    fun theHiddenGuardReadsTheAttributeInsteadOfTheTruthyReflection() {
        assertFalse(
            "node.hidden is truthy for hidden=\"until-found\", so it must not be tested at all",
            corpusJs.contains("node.hidden"),
        )
        assertTrue(
            "the attribute value must be read explicitly",
            corpusJs.contains("node.hasAttribute('hidden')"),
        )
        assertTrue(
            "`until-found` must be recognised as a collapse, not a hide",
            corpusJs.contains("hiddenAttr === 'until-found'"),
        )
        assertTrue(
            "the refusal must happen when the attribute is present and is not that value",
            corpusJs.contains("hiddenAttr !== null"),
        )
    }

    @Test
    fun theUntilFoundExceptionIsScopedToSemanticContainersOnly() {
        assertTrue(
            "the exception must go through the existing semantic-container predicate",
            corpusJs.contains("isCollapsedSemanticContainer(node, tag, role)"),
        )
        assertTrue(
            "computed `content-visibility:hidden` must be judged by the same predicate",
            corpusJs.contains("computed.contentVisibility === 'hidden' && !semanticContainer"),
        )
        assertTrue(
            "inline `content-visibility:hidden` must be judged by the same predicate",
            corpusJs.contains("styleText.indexOf('content-visibility:hidden') >= 0 &&"),
        )
    }

    @Test
    fun ordinaryHidesAreStillHides() {
        assertTrue("`inert` must still exclude", corpusJs.contains("node.hasAttribute('inert')"))
        assertTrue("`aria-hidden=true` must still exclude", corpusJs.contains("ariaHidden === 'true'"))
        assertTrue(
            "inline `display:none` must still exclude",
            corpusJs.contains("styleText.indexOf('display:none') >= 0"),
        )
        assertTrue(
            "inline `visibility:hidden` must still exclude",
            corpusJs.contains("styleText.indexOf('visibility:hidden') >= 0"),
        )
        assertTrue(
            "computed `display:none` must still exclude outside a semantic container",
            corpusJs.contains("computed.display === 'none' && !semanticContainer"),
        )
        assertTrue(
            "computed `visibility:hidden` must still exclude",
            corpusJs.contains("computed.visibility === 'hidden'"),
        )
    }
}
