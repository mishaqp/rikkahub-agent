package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Tool Name Compatibility Layer.
 *
 * Two groups:
 *
 *  1. **Contract against the shipped (empty) table.** These assert the layer is a strict no-op
 *     for every name the app registers today — the whole point of shipping it as infrastructure
 *     ahead of any merge. If one of these fails, the change altered production behaviour.
 *
 *  2. **Rules against synthetic tables.** [ToolNameAliases.resolve] and
 *     [ToolNameAliases.validate] take an explicit map so the exact behaviour a future merge PR
 *     will rely on (one-hop resolution, no self-alias, no chains, MCP pass-through) is pinned
 *     down *before* the first real entry is added.
 */
class ToolNameAliasesTest {

    private fun tool(name: String): Tool = Tool(
        name = name,
        description = "test tool $name",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { emptyList() },
    )

    // ---- 1. Shipped table is empty: the layer must not change anything ------------------

    @Test
    fun `production table is empty`() {
        assertTrue(
            "ToolNameAliases.ALIASES must ship empty — this PR is infrastructure only",
            ToolNameAliases.ALIASES.isEmpty(),
        )
    }

    @Test
    fun `unknown name is returned unchanged`() {
        // The task's own example: with the shipped table, a name that a FUTURE merge will map
        // (get_battery_status -> device_info) must still resolve to itself today.
        assertEquals("get_battery_status", ToolNameAliases.canonicalName("get_battery_status"))
        assertEquals("list_files", ToolNameAliases.canonicalName("list_files"))
        assertEquals("web_extract", ToolNameAliases.canonicalName("web_extract"))
        assertEquals("shizuku_exec", ToolNameAliases.canonicalName("shizuku_exec"))
    }

    @Test
    fun `already canonical name stays the same`() {
        assertEquals("read_file", ToolNameAliases.canonicalName("read_file"))
        assertEquals("web_fetch", ToolNameAliases.canonicalName("web_fetch"))
        assertEquals("device_info", ToolNameAliases.canonicalName("device_info"))
    }

    @Test
    fun `resolution reports not aliased for every shipped name`() {
        listOf("get_battery_status", "web_extract", "list_files", "termux_run_command", "tap")
            .forEach { name ->
                val resolution = ToolNameAliases.resolve(name)
                assertEquals(name, resolution.requestedName)
                assertEquals(name, resolution.canonicalName)
                assertFalse("$name must not report as aliased", resolution.aliased)
            }
    }

    @Test
    fun `empty name is returned unchanged`() {
        assertEquals("", ToolNameAliases.canonicalName(""))
    }

    // ---- 2. MCP names are never aliased -------------------------------------------------

    @Test
    fun `mcp names are passed through unchanged`() {
        val mcpName = "mcp__myserver__do_thing"
        assertEquals(mcpName, ToolNameAliases.canonicalName(mcpName))
        assertFalse(ToolNameAliases.resolve(mcpName).aliased)
    }

    @Test
    fun `mcp name stays unchanged even when the table would otherwise match`() {
        // Guards the rule, not just today's empty table: an MCP-relayed name is runtime-minted
        // and must never be redirected, even if a mis-configured table has a key for it.
        val mcpName = "mcp__server__tool"
        val table = mapOf(mcpName to "files", "legacy" to "files")
        assertEquals(mcpName, ToolNameAliases.resolve(mcpName, table).canonicalName)
        assertEquals("files", ToolNameAliases.resolve("legacy", table).canonicalName)
    }

    // ---- 3. Synthetic table: one-hop resolution -----------------------------------------

    @Test
    fun `synthetic alias resolves to its canonical target`() {
        val table = mapOf("old_tool" to "new_tool")
        val resolution = ToolNameAliases.resolve("old_tool", table)
        assertEquals("old_tool", resolution.requestedName)
        assertEquals("new_tool", resolution.canonicalName)
        assertTrue(resolution.aliased)
    }

    @Test
    fun `synthetic alias leaves unrelated names alone`() {
        val table = mapOf("old_tool" to "new_tool")
        assertEquals("other_tool", ToolNameAliases.resolve("other_tool", table).canonicalName)
        assertEquals("new_tool", ToolNameAliases.resolve("new_tool", table).canonicalName)
    }

    // ---- 4. Tool lookup through the resolver --------------------------------------------

    @Test
    fun `legacy name resolves to the canonical tool definition`() {
        val tools = listOf(tool("new_tool"), tool("gamma"))
        val table = mapOf("old_tool" to "new_tool")

        val canonical = ToolNameAliases.resolve("old_tool", table).canonicalName
        val found = tools.firstOrNull { it.name == canonical }

        assertNotNull("legacy name must resolve to the canonical Tool", found)
        assertEquals("new_tool", found!!.name)
    }

    @Test
    fun `unknown name does not resolve to a tool`() {
        val tools = listOf(tool("new_tool"))
        val table = mapOf("old_tool" to "new_tool")

        val canonical = ToolNameAliases.resolve("totally_unknown_tool", table).canonicalName
        assertNull(tools.firstOrNull { it.name == canonical })
    }

    @Test
    fun `resolveTool finds the canonical definition`() {
        val tools = listOf(tool("device_info"), tool("files"))
        // Production table is empty, so a canonical name resolves exactly as before.
        assertNotNull(ToolNameAliases.resolveTool(tools, "device_info"))
        assertEquals("files", ToolNameAliases.resolveTool(tools, "files")!!.name)
    }

    @Test
    fun `resolveTool returns null for an unregistered name`() {
        val tools = listOf(tool("device_info"), tool("files"))
        assertNull(
            "an unregistered name must still miss, so the caller's tool_not_found path stays in charge",
            ToolNameAliases.resolveTool(tools, "definitely_not_a_tool"),
        )
    }

    // ---- 5. Validation rules ------------------------------------------------------------

    @Test
    fun `validate accepts the shipped empty table`() {
        assertEquals(emptyList<String>(), ToolNameAliases.validate(ToolNameAliases.ALIASES))
    }

    @Test
    fun `validate accepts a well-formed one-hop table`() {
        val table = mapOf(
            "get_battery_status" to "device_info",
            "get_wifi_info" to "device_info",
            "list_files" to "files",
        )
        assertEquals(emptyList<String>(), ToolNameAliases.validate(table))
    }

    @Test
    fun `validate rejects a self-alias`() {
        val problems = ToolNameAliases.validate(mapOf("web_extract" to "web_extract"))
        assertTrue("self-alias must be rejected, got $problems", problems.any { "self-alias" in it })
    }

    @Test
    fun `validate rejects a chain`() {
        // A -> B -> C. Resolution is specified as exactly one hop, so a target that is itself a
        // key is a configuration bug, not an implicit second hop.
        val problems = ToolNameAliases.validate(mapOf("a_tool" to "b_tool", "b_tool" to "c_tool"))
        assertTrue("chain must be rejected, got $problems", problems.any { "chain" in it })
    }

    @Test
    fun `validate rejects a cycle`() {
        val problems = ToolNameAliases.validate(mapOf("a_tool" to "b_tool", "b_tool" to "a_tool"))
        assertTrue("cycle must be rejected, got $problems", problems.isNotEmpty())
    }

    @Test
    fun `validate rejects blank legacy names`() {
        val problems = ToolNameAliases.validate(mapOf("" to "device_info", "  " to "files"))
        assertEquals(2, problems.count { "blank legacy name" in it })
    }

    @Test
    fun `validate rejects a blank canonical target`() {
        val problems = ToolNameAliases.validate(mapOf("old_tool" to ""))
        assertTrue("blank target must be rejected, got $problems", problems.any { "blank canonical" in it })
    }

    @Test
    fun `validate rejects aliasing an mcp name`() {
        val problems = ToolNameAliases.validate(mapOf("mcp__server__tool" to "files"))
        assertTrue("mcp key must be rejected, got $problems", problems.any { "MCP-relayed" in it })
    }

    @Test
    fun `validate rejects an mcp target`() {
        val problems = ToolNameAliases.validate(mapOf("legacy_tool" to "mcp__server__tool"))
        assertTrue("mcp target must be rejected, got $problems", problems.any { "runtime-minted" in it })
    }

    // ---- 6. Loop-guard / policy canonicalisation contract -------------------------------

    @Test
    fun `legacy and canonical spellings produce the same loop signature`() {
        // The loop guard compares "<canonicalName>::args" strings. If the two spellings produced
        // different signatures, a model that alternated them would never trip the guard.
        val table = mapOf("old_tool" to "new_tool")
        val args = """{"a":1}"""

        val legacySignature = ToolNameAliases.resolve("old_tool", table).canonicalName + "::" + args
        val canonicalSignature = ToolNameAliases.resolve("new_tool", table).canonicalName + "::" + args

        assertEquals(canonicalSignature, legacySignature)
        assertEquals("new_tool::$args", legacySignature)
    }

    @Test
    fun `policy sees the canonical name while the requested name is preserved`() {
        // Documents the contract GenerationHandler relies on: policy (HARDLINE / approval)
        // reads canonicalName, the message part keeps requestedName.
        val table = mapOf("legacy_shell_tool" to "shizuku_exec")
        val resolution = ToolNameAliases.resolve("legacy_shell_tool", table)

        assertEquals("legacy_shell_tool", resolution.requestedName)  // history / envelope
        assertEquals("shizuku_exec", resolution.canonicalName)       // policy / dispatch
    }

    // ---- 7. HARDLINE is decided by the canonical name (real guard, no mocks) ------------

    @Test
    fun `hardline blocks the canonical shell tool`() {
        // Baseline for the two tests below: the shipped guard DOES match shizuku_exec.
        val reason = HardlineCommandGuard.checkTool("shizuku_exec", """{"command":"rm -rf /"}""")
        assertNotNull("guard must block rm -rf / on shizuku_exec", reason)
    }

    @Test
    fun `hardline does not recognise a legacy name on its own`() {
        // Why resolution must happen BEFORE the guard: HardlineCommandGuard matches on literal
        // tool names, so an unresolved legacy spelling sails straight past it. This test is the
        // negative control that proves the resolution step in GenerationHandler /
        // DirectModeActionRunner / WorkflowEngine / ChatService is load-bearing.
        val reason = HardlineCommandGuard.checkTool("legacy_shell_tool", """{"command":"rm -rf /"}""")
        assertNull("an unknown name is not covered by the guard's name-based arms", reason)
    }

    @Test
    fun `hardline applies to the canonical name a legacy alias resolves to`() {
        // The actual rule: resolve first, then check. A legacy alias pointing at a shell tool is
        // judged by the canonical tool's rules, so the alias cannot be used as a bypass.
        val table = mapOf("legacy_shell_tool" to "shizuku_exec")
        val args = """{"command":"rm -rf /"}"""

        val canonical = ToolNameAliases.resolve("legacy_shell_tool", table).canonicalName
        val reason = HardlineCommandGuard.checkTool(canonical, args)

        assertNotNull("resolving to shizuku_exec must make the guard fire", reason)
    }

    @Test
    fun `approval is decided by the canonical name`() {
        // Same shape for the approval tier: ToolApprovalDefaults.requiresApproval is a
        // name-membership test, so an unresolved legacy name would silently skip the prompt.
        assertTrue(ToolApprovalDefaults.requiresApproval("shizuku_exec"))

        val table = mapOf("legacy_shell_tool" to "shizuku_exec")
        val canonical = ToolNameAliases.resolve("legacy_shell_tool", table).canonicalName
        assertTrue(ToolApprovalDefaults.requiresApproval(canonical))
    }
}
