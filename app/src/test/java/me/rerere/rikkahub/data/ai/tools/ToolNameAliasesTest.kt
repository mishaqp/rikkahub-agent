package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.DirectModeActionRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Tool Call Compatibility Layer (name + args).
 *
 * Two groups:
 *
 * 1. Contract against the shipped (EMPTY) table: a strict byte-level no-op for every
 * call the app can emit today, so shipping this layer cannot change behaviour.
 * 2. Synthetic rules: the exact behaviour a future merge PR will rely on — one-hop
 * name+args resolution, transform failure meaning the call is refused, one shared
 * canonical loop signature, HARDLINE judging transformed args, canonical lookup and
 * execution, and the approvalName seam that preserves a persisted call's grants.
 */
class ToolNameAliasesTest {

 private fun tool(name: String): Tool = Tool(
 name = name,
 description = "test tool $name",
 parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
 execute = { emptyList() },
 )

 private fun rulesOf(
 vararg pairs: Pair<String, ToolNameAliases.CompatibilityRule>,
 ): Map<String, ToolNameAliases.CompatibilityRule> = mapOf(*pairs)

 /** Synthetic: list_files {path} -> files {action:list, path}. */
 private val listFilesRule = ToolNameAliases.CompatibilityRule(
 canonicalName = "files",
 transformArgs = ToolNameAliases.ArgsTransform { legacy ->
 buildJsonObject {
 put("action", JsonPrimitive("list"))
 for ((key, value) in legacy.jsonObject) put(key, value)
 }
 },
 )

 // ---- 1. Shipped table: strict no-op ---------------------------------------

 @Test
 fun `production rules table is empty`() {
 assertTrue(
 "ToolNameAliases.RULES must ship empty — this PR is infrastructure only",
 ToolNameAliases.RULES.isEmpty(),
 )
 }

 @Test
 fun `shipped calls are strict byte-level no-ops`() {
 listOf(
 "get_battery_status" to "{}",
 "list_files" to "{\"path\":\"/sdcard\"}",
 "web_extract" to "{\"url\":\"https://example.com\"}",
 "shizuku_exec" to "{\"command\":\"id\"}",
 "termux_run_command" to "{\"command\":\"uname -m\"}",
 "" to "{}",
 ).forEach { (name, input) ->
 val resolved = ToolNameAliases.resolveCall(name, input)
 assertEquals(name, resolved.canonicalName)
 assertEquals("canonical input must be the untouched requested string", input, resolved.canonicalInput)
 assertEquals(name, resolved.approvalName)
 assertNull(resolved.resolutionError)
 assertFalse(resolved.aliased)
 }
 }

 @Test
 fun `json element entry point is a no-op on the production table`() {
 val args = buildJsonObject { put("path", JsonPrimitive("/x")) }
 val resolved = ToolNameAliases.resolveCall("list_files", args)
 assertEquals("list_files", resolved.canonicalName)
 assertEquals(args.toString(), resolved.canonicalInput)
 assertEquals("list_files", resolved.approvalName)
 assertNull(resolved.resolutionError)
 }

 // ---- 2. Synthetic rules: name + args transform -----------------------------

 @Test
 fun `legacy name and args resolve to canonical name and transformed args`() {
 val rules = rulesOf(
 "get_battery_status" to ToolNameAliases.CompatibilityRule(
 canonicalName = "device_info",
 transformArgs = ToolNameAliases.ArgsTransform {
 buildJsonObject { put("section", JsonPrimitive("battery")) }
 },
 ),
 )
 val resolved = ToolNameAliases.resolveRawCall("get_battery_status", "{}", rules)
 assertNull(resolved.resolutionError)
 assertEquals("device_info", resolved.canonicalName)
 assertEquals(
 buildJsonObject { put("section", JsonPrimitive("battery")) },
 Json.parseToJsonElement(resolved.canonicalInput).jsonObject,
 )
 assertEquals("device_info", resolved.approvalName)
 assertTrue(resolved.aliased)
 }

 @Test
 fun `canonical call stays unchanged even under a populated table`() {
 val rules = rulesOf("get_battery_status" to ToolNameAliases.CompatibilityRule(canonicalName = "device_info"))
 val resolved = ToolNameAliases.resolveRawCall("device_info", "{\"section\":\"battery\"}", rules)
 assertEquals("device_info", resolved.canonicalName)
 assertEquals("{\"section\":\"battery\"}", resolved.canonicalInput)
 assertEquals("device_info", resolved.approvalName)
 assertFalse(resolved.aliased)
 assertNull(resolved.resolutionError)
 }

 @Test
 fun `unknown name stays unknown and unchanged`() {
 val resolved = ToolNameAliases.resolveCall("no_such_tool", "{\"x\":1}")
 assertEquals("no_such_tool", resolved.canonicalName)
 assertEquals("{\"x\":1}", resolved.canonicalInput)
 assertNull(resolved.resolutionError)
 assertNull(ToolNameAliases.resolveTool(listOf(tool("files")), "no_such_tool"))
 }

 @Test
 fun `mcp names are never transformed even with a matching key`() {
 val rules = rulesOf("mcp__srv__do_thing" to ToolNameAliases.CompatibilityRule(canonicalName = "evil"))
 val resolved = ToolNameAliases.resolveRawCall("mcp__srv__do_thing", "{\"a\":1}", rules)
 assertEquals("mcp__srv__do_thing", resolved.canonicalName)
 assertEquals("{\"a\":1}", resolved.canonicalInput)
 assertNull(resolved.resolutionError)
 assertFalse(resolved.aliased)
 }

 // ---- 3. Failure semantics: refuse, never execute ---------------------------

 @Test
 fun `a throwing transform forbids execution`() {
 val rules = rulesOf(
 "list_files" to ToolNameAliases.CompatibilityRule(
 canonicalName = "files",
 transformArgs = ToolNameAliases.ArgsTransform { throw IllegalStateException("boom") },
 ),
 )
 val resolved = ToolNameAliases.resolveRawCall("list_files", "{}", rules)
 assertNotNull(resolved.resolutionError)
 assertTrue(resolved.resolutionError!!.startsWith("transform_failed"))
 // Caller contract: on failure the canonical form mirrors the requested form and
 // the caller MUST refuse execution — never run the legacy args instead.
 assertEquals("list_files", resolved.canonicalName)
 assertEquals("{}", resolved.canonicalInput)
 }

 @Test
 fun `unparseable legacy args with a matching rule forbid execution`() {
 val rules = rulesOf("list_files" to listFilesRule)
 val resolved = ToolNameAliases.resolveRawCall("list_files", "{", rules)
 assertNotNull(resolved.resolutionError)
 assertTrue(resolved.resolutionError!!.startsWith("args_unparseable"))
 }

 @Test
 fun `unparseable args without a rule pass through untouched`() {
 // Preserves today's invalid_tool_args path: with no rule the layer must not
 // swallow or rewrite malformed model output.
 val resolved = ToolNameAliases.resolveCall("list_files", "not-json")
 assertNull(resolved.resolutionError)
 assertEquals("not-json", resolved.canonicalInput)
 }

 // ---- 4. Loop signature -------------------------------------------------------

 @Test
 fun `legacy and canonical forms of one action share a single loop signature`() {
 val rules = rulesOf("list_files" to listFilesRule)
 val legacy = ToolNameAliases.resolveRawCall("list_files", "{\"path\":\"/x\"}", rules)
 assertNull(legacy.resolutionError)
 // The canonical call, emitted with the same canonical JSON shape:
 val canonical = ToolNameAliases.resolveRawCall("files", legacy.canonicalInput, rules)
 assertNull(canonical.resolutionError)
 assertEquals(legacy.signature, canonical.signature)
 }

 // ---- 5. HARDLINE over transformed args ----------------------------------------

 @Test
 fun `hardline checks the transformed canonical args, not the legacy args`() {
 val rules = rulesOf(
 "old_exec_legacy" to ToolNameAliases.CompatibilityRule(
 canonicalName = "termux_run_command",
 transformArgs = ToolNameAliases.ArgsTransform {
 buildJsonObject { put("command", JsonPrimitive("rm -rf /")) }
 },
 ),
 )
 val resolved = ToolNameAliases.resolveRawCall("old_exec_legacy", "{}", rules)
 assertNull(resolved.resolutionError)
 // The legacy args would slip through the guard...
 assertNull(HardlineCommandGuard.checkTool(resolved.canonicalName, resolved.requestedInput))
 // ...but the safety floor judges the transformed canonical args.
 assertNotNull(HardlineCommandGuard.checkTool(resolved.canonicalName, resolved.canonicalInput))
 }

 // ---- 6. Lookup + execution ------------------------------------------------------

 @Test
 fun `lookup finds the canonical tool`() {
 val tools = listOf(tool("files"), tool("web"))
 // Production table (empty): no rewrite today.
 assertNull(ToolNameAliases.resolveTool(tools, "list_files"))
 assertEquals("files", ToolNameAliases.resolveTool(tools, "files")?.name)
 // Synthetic: resolve, then find by canonical name (the runner pattern).
 val resolved = ToolNameAliases.resolveRawCall("list_files", "{}", rulesOf("list_files" to listFilesRule))
 assertEquals("files", tools.firstOrNull { it.name == resolved.canonicalName }?.name)
 }

 @Test
 fun `runner path executes the canonical tool with transformed args, never legacy args`() = runBlocking {
 val received = mutableListOf<String>()
 val composite = Tool(
 name = "files",
 description = "composite",
 parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
 execute = { input ->
 received += input.toString()
 listOf(UIMessagePart.Text("ok"))
 },
 )
 // The exact sequence DirectModeActionRunner.runOne / WorkflowActionRunner.run perform
 // per action: resolve -> HARDLINE -> canonical lookup -> execute(canonical args).
 val resolved = ToolNameAliases.resolveRawCall("list_files", "{\"path\":\"/x\"}", rulesOf("list_files" to listFilesRule))
 assertNull(resolved.resolutionError)
 assertNull(HardlineCommandGuard.checkTool(resolved.canonicalName, resolved.canonicalInput))
 val target = listOf(composite).firstOrNull { it.name == resolved.canonicalName }
 assertNotNull(target)
 target!!.execute(Json.parseToJsonElement(resolved.canonicalInput))
 assertEquals(listOf("{\"action\":\"list\",\"path\":\"/x\"}"), received)
 }

 @Test
 fun `direct mode runner still executes with the production no-op table`() = runBlocking {
 val received = mutableListOf<String>()
 val echo = Tool(
 name = "echo_tool",
 description = "d",
 parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
 execute = { input ->
 received += input.toString()
 listOf(UIMessagePart.Text("ok"))
 },
 )
 // Success path touches no android.util.Log, so it is JVM-safe.
 val runner = DirectModeActionRunner(Json)
 val actions = DirectModeActionRunner.parse("[{\"tool\":\"echo_tool\",\"args\":{\"a\":1}}]").getOrThrow()
 val result = runner.run(actions, listOf(echo))
 assertEquals("success", result.finalOutcome)
 assertEquals(listOf("{\"a\":1}"), received)
 }

 // ---- 7. Approval seam ------------------------------------------------------------

 @Test
 fun `approval name defaults to the canonical name`() {
 val rules = rulesOf("get_battery_status" to ToolNameAliases.CompatibilityRule(canonicalName = "device_info"))
 val resolved = ToolNameAliases.resolveRawCall("get_battery_status", "{}", rules)
 assertEquals("device_info", resolved.approvalName)
 }

 @Test
 fun `a rule may preserve a persisted calls approval grants via approvalName`() {
 // Approval grants are keyed by the REAL tool name string. When a legacy tool folds
 // into a composite, a merge PR can keep the stored call's existing grants working
 // by pinning approvalName to the legacy name.
 val rules = rulesOf(
 "get_volume" to ToolNameAliases.CompatibilityRule(
 canonicalName = "device_control",
 approvalName = "get_volume",
 ),
 )
 val resolved = ToolNameAliases.resolveRawCall("get_volume", "{}", rules)
 assertEquals("device_control", resolved.canonicalName)
 assertEquals("get_volume", resolved.approvalName)
 }

 // ---- 8. Structural validation -----------------------------------------------------

 @Test
 fun `validate flags self-alias chains blanks and mcp rules`() {
 val problems = ToolNameAliases.validate(
 rulesOf(
 "a" to ToolNameAliases.CompatibilityRule(canonicalName = "a"),
 "b" to ToolNameAliases.CompatibilityRule(canonicalName = "c"),
 "c" to ToolNameAliases.CompatibilityRule(canonicalName = "d"),
 "mcp__srv__x" to ToolNameAliases.CompatibilityRule(canonicalName = "y"),
 "" to ToolNameAliases.CompatibilityRule(canonicalName = "z"),
 ),
 )
 assertTrue(problems.any { it.contains("self-alias") })
 assertTrue(problems.any { it.contains("chain") })
 assertTrue(problems.any { it.contains("blank legacy") })
 assertTrue(problems.any { it.contains("mcp-prefixed") })
 }

 @Test
 fun `validate passes a sane table`() {
 assertTrue(ToolNameAliases.validate(rulesOf("list_files" to listFilesRule)).isEmpty())
 }
}
