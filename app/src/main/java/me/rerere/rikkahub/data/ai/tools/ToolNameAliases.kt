package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool

/**
 * Tool Call Compatibility Layer (name + args).
 *
 * ## Why this exists
 *
 * Tool calls are persisted outside the running process: scheduled jobs (mode='direct'
 * action lists), workflow definitions, UIMessagePart.Tool (toolName + input) inside
 * stored conversations, and Telegram/headless call sites all keep the exact strings a
 * model emitted at the time the row was written. A later refactor that renames a tool —
 * or folds several tools into one composite — would break every saved reference with
 * tool_not_found, for rows the user cannot even edit any more.
 *
 * A composite merge changes MORE than the name: the persisted call also carries args in
 * the OLD tool's schema, e.g. get_battery_status {} -> device_info with section=battery,
 * or list_files with path -> files with action=list plus path. This layer therefore
 * resolves the whole CALL, not just the name:
 *
 * requestedName + requestedInput -> canonicalName + canonicalInput
 *
 * ## Contract
 *
 * - One hop. legacy -> canonical. A canonical name must not itself be a key of the
 * table; chains are rejected by [validate].
 * - Strict no-op without a rule. Unknown/current canonical names are returned untouched:
 * the requested input string is
 * handed back byte-identical, never parse-then-reserialized.
 * - MCP names are never transformed. Anything starting with the mcp prefix is returned
 * verbatim — those are runtime-minted per-server names, not part of this table.
 * - Policy sees the canonical call. HARDLINE, approval, loop signature, tool lookup
 * and execute() all consume [ResolvedToolCall.canonicalName] /
 * [ResolvedToolCall.canonicalInput]. Resolving later would let a legacy alias to a
 * shell tool slip past the safety floor, or let a transformed args string smuggle a
 * command the guard never inspected.
 * - History keeps the requested form. [ResolvedToolCall.requestedName] /
 * [ResolvedToolCall.requestedInput] are never rewritten — logs, error envelopes and
 * stored message parts keep what the model (or the old build) actually emitted.
 * - Failure means refuse, never execute. When a rule matches but its args cannot be
 * parsed or its transform throws, [ResolvedToolCall.resolutionError] is non-null and
 * the caller MUST surface a controlled error instead of executing anything — and must
 * NOT silently fall back to the legacy args.
 *
 * ## Approval semantics across a composite merge
 *
 * Approval grants (ToolApprovalAllowList / ToolApprovalPreferences) are keyed by the real
 * tool NAME string. When several legacy tools fold into one composite they may carry
 * different approval expectations (a read tool vs a write tool). The seam for that
 * is [CompatibilityRule.approvalName]: by default policy keys off the canonical name;
 * a merge PR that needs to preserve a persisted call's existing grants may set
 * approvalName to the legacy name so the stored call keeps the approval entry it
 * already had. Action-level approval of a NEW composite (read/write/delete distinctions
 * inside one tool) is deliberately NOT solved here — that belongs to the concrete
 * composite-tool PR, once its real action schema exists.
 *
 * ## Current state of the table
 *
 * [RULES] now contains the first production composite migration: the six legacy
 * read-only device-info names map to device_info(section=...). Other names remain strict no-op.
 */
object ToolNameAliases {

 /** Namespace prefix minted at runtime for MCP-relayed tools. Never transformed. */
 private const val MCP_TOOL_PREFIX = "mcp__"

 /**
 * Transforms the args of a persisted legacy call into the args the canonical
 * (composite) tool expects. Works on [JsonElement] because Tool.execute takes a
 * JsonElement — an object is the common case, but the layer must not narrow the
 * contract. Pure and total by contract: no I/O, no global state.
 */
 fun interface ArgsTransform {
 fun transform(legacyArgs: JsonElement): JsonElement

 companion object {
 /** Pass-through for rules that rename only. */
 val IDENTITY: ArgsTransform = ArgsTransform { it }
 }
 }

 /**
 * One compatibility rule: legacy name -> canonical name + args transform.
 *
 * [approvalName] — the name approval policy should look up. Null means the canonical
 * name (today's behaviour for everything). See the class-level docs for when a merge
 * PR would pin this to the legacy name instead.
 */
 data class CompatibilityRule(
 val canonicalName: String,
 val transformArgs: ArgsTransform = ArgsTransform.IDENTITY,
 val approvalName: String? = null,
 )

 private fun deviceInfoSection(section: String): ArgsTransform = ArgsTransform { legacyArgs ->
 require(legacyArgs is JsonObject) { "legacy device-info args must be a JSON object" }
 buildJsonObject { put("section", section) }
 }

 /**
 * Production rule table: legacyName -> rule.
 * Invariants (enforced by [validate]): no blank names, no self-alias, no chains,
 * no mcp-prefixed keys or targets.
 */
 val RULES: Map<String, CompatibilityRule> = mapOf(
 "get_battery_status" to CompatibilityRule("device_info", deviceInfoSection("battery")),
 "get_audio_info" to CompatibilityRule("device_info", deviceInfoSection("audio")),
 "get_telephony_info" to CompatibilityRule("device_info", deviceInfoSection("telephony")),
 "get_wifi_info" to CompatibilityRule("device_info", deviceInfoSection("wifi")),
 "get_storage_info" to CompatibilityRule("device_info", deviceInfoSection("storage")),
 "list_sensors" to CompatibilityRule("device_info", deviceInfoSection("sensors")),
 )

 /**
 * The outcome of resolving one requested call.
 *
 * [resolutionError] non-null means the caller must refuse execution and surface the
 * error; [canonicalName]/[canonicalInput] then simply mirror the requested values.
 */
 data class ResolvedToolCall(
 val requestedName: String,
 val requestedInput: String,
 val canonicalName: String,
 val canonicalInput: String,
 val approvalName: String,
 val resolutionError: String? = null,
 ) {
 /** True when a rule rewrote the call (name and/or args differ). */
 val aliased: Boolean
 get() = requestedName != canonicalName || requestedInput != canonicalInput

 /**
 * Loop-guard signature over the CANONICAL form: a legacy call and the same
 * action emitted canonically collide into one signature instead of two.
 */
 val signature: String
 get() = canonicalName + "::" + canonicalInput
 }

 /**
 * Resolve a call whose args are a raw JSON string (message parts, persisted rows).
 * Without a matching rule this is a strict byte-level no-op — the requested string
 * is returned untouched.
 */
 fun resolveCall(requestedName: String, requestedInput: String): ResolvedToolCall =
 resolveRawCall(requestedName, requestedInput, RULES)

 /**
 * Resolve a call whose args are already a [JsonElement] (workflow / direct-mode
 * actions, cron-job validation).
 */
 fun resolveCall(requestedName: String, requestedInput: JsonElement): ResolvedToolCall =
 resolveCallWithRules(requestedName, requestedInput, RULES)

 /** Test/inspection seam: raw-args resolution against an explicit table. */
 internal fun resolveRawCall(
 requestedName: String,
 requestedInput: String,
 rules: Map<String, CompatibilityRule>,
 ): ResolvedToolCall {
 if (requestedName.isEmpty()) return passthrough(requestedName, requestedInput)
 if (requestedName.startsWith(MCP_TOOL_PREFIX)) return passthrough(requestedName, requestedInput)
 val rule = rules[requestedName] ?: return passthrough(requestedName, requestedInput)
 val parsed = runCatching { Json.parseToJsonElement(requestedInput) }.getOrElse {
 return failure(requestedName, requestedInput, "args_unparseable: " + (it.message ?: "json_parse_failed"))
 }
 return applyRule(requestedName, requestedInput, parsed, rule)
 }

 /** Test/inspection seam: element-args resolution against an explicit table. */
 internal fun resolveCallWithRules(
 requestedName: String,
 requestedInput: JsonElement,
 rules: Map<String, CompatibilityRule>,
 ): ResolvedToolCall {
 if (requestedName.isEmpty()) return passthrough(requestedName, requestedInput.toString())
 if (requestedName.startsWith(MCP_TOOL_PREFIX)) return passthrough(requestedName, requestedInput.toString())
 val rule = rules[requestedName] ?: return passthrough(requestedName, requestedInput.toString())
 return applyRule(requestedName, requestedInput.toString(), requestedInput, rule)
 }

 private fun applyRule(
 requestedName: String,
 requestedInput: String,
 parsedArgs: JsonElement,
 rule: CompatibilityRule,
 ): ResolvedToolCall {
 val canonicalArgs = try {
 rule.transformArgs.transform(parsedArgs)
 } catch (e: Exception) {
 // Exception, not Throwable: JVM-fatal errors (OOM, StackOverflow) propagate.
 return failure(requestedName, requestedInput, "transform_failed: " + (e.message ?: e.javaClass.simpleName))
 }
 return ResolvedToolCall(
 requestedName = requestedName,
 requestedInput = requestedInput,
 canonicalName = rule.canonicalName,
 canonicalInput = canonicalArgs.toString(),
 approvalName = rule.approvalName ?: rule.canonicalName,
 )
 }

 private fun passthrough(name: String, input: String): ResolvedToolCall =
 ResolvedToolCall(name, input, name, input, name, null)

 private fun failure(name: String, input: String, why: String): ResolvedToolCall =
 ResolvedToolCall(name, input, name, input, name, why)

 /**
 * Name-only convenience retained for call sites that genuinely have no args.
 * New code should resolve the whole call via [resolveCall].
 */
 fun canonicalName(requested: String): String {
 if (requested.isEmpty()) return requested
 if (requested.startsWith(MCP_TOOL_PREFIX)) return requested
 return RULES[requested]?.canonicalName ?: requested
 }

 /**
 * Find the [Tool] that should execute [requestedName], resolving a legacy name first.
 * Returns null exactly when the canonical name is not among [tools] — the caller's
 * existing tool_not_found path stays in charge of the error envelope.
 */
 fun resolveTool(tools: List<Tool>, requestedName: String): Tool? {
 val canonical = canonicalName(requestedName)
 return tools.firstOrNull { it.name == canonical }
 }

 /**
 * Structural validation of a rule table. Returns human-readable problems; an empty
 * list means every invariant holds. Exercised by unit tests today and required before
 * any future merge PR ships a non-empty table.
 */
 internal fun validate(rules: Map<String, CompatibilityRule>): List<String> {
 val problems = mutableListOf<String>()
 for ((legacy, rule) in rules) {
 if (legacy.isBlank()) {
 problems += "blank legacy name"
 continue
 }
 val canonical = rule.canonicalName
 if (canonical.isBlank()) {
 problems += "maps to a blank canonical name: " + legacy
 continue
 }
 if (legacy == canonical) problems += "self-alias: " + legacy
 if (legacy.startsWith(MCP_TOOL_PREFIX)) {
 problems += "mcp-prefixed legacy name must not be aliased: " + legacy
 }
 if (canonical.startsWith(MCP_TOOL_PREFIX)) {
 problems += "targets an mcp-prefixed runtime-minted name: " + legacy
 }
 if (canonical in rules) {
 problems += "chain: " + legacy + " -> " + canonical + " which is itself a legacy name"
 }
 if (rule.approvalName != null && rule.approvalName.isBlank()) {
 problems += "blank approvalName: " + legacy
 }
 }
 return problems
 }
}
