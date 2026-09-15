from pathlib import Path


def replace_exact(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# GenerationHandler: start from the clean name-only PR13 version, then upgrade
# policy/execution to resolve the whole call (name + args).
# ---------------------------------------------------------------------------
p = Path("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt")
s = p.read_text(encoding="utf-8")

s = replace_exact(
    s,
    '''                    // Tool-name compatibility layer: a tool name persisted by an older build
                    // (or by a pre-merge model emission) resolves to the canonical current name
                    // BEFORE anything policy-relevant looks at it. Resolving later would let a
                    // legacy alias to a shell tool slip past the HARDLINE arm, which matches on
                    // the name it is handed. The requested name is deliberately NOT rewritten —
                    // it still travels in the message part for history and error envelopes.
                    val canonicalToolName = ToolNameAliases.canonicalName(tool.toolName)
                    val toolDef = toolsInternal.find { it.name == canonicalToolName }
''',
    '''                    // Resolve the whole persisted call BEFORE policy: a future composite
                    // migration may change both the tool name and its argument schema. Keep the
                    // requested name/input in history, but let policy inspect the canonical call.
                    val resolvedCall = ToolNameAliases.resolveCall(tool.toolName, tool.input)
                    val canonicalToolName = resolvedCall.canonicalName
                    val toolDef = toolsInternal.find { it.name == canonicalToolName }
''',
    "GenerationHandler approval resolver",
)
s = replace_exact(
    s,
    '''                    val hardlineReason = me.rerere.rikkahub.data.ai.tools
                        .HardlineCommandGuard.checkTool(canonicalToolName, tool.input)
                    val transformed = when {
''',
    '''                    val hardlineReason = me.rerere.rikkahub.data.ai.tools
                        .HardlineCommandGuard.checkTool(canonicalToolName, resolvedCall.canonicalInput)
                    val transformed = when {
                        resolvedCall.resolutionError != null && tool.approvalState is ToolApprovalState.Auto -> {
                            Log.w(TAG, "compat-resolution failed for ${tool.toolName}: ${resolvedCall.resolutionError}")
                            tool.copy(approvalState = ToolApprovalState.Denied(
                                "compatibility error: ${resolvedCall.resolutionError}. " +
                                    "The stored call could not be adapted to the current tool schema and was not executed."
                            ))
                        }
''',
    "GenerationHandler approval hardline/error",
)
s = replace_exact(
    s,
    '''                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
''',
    '''                        toolDef?.needsApproval(
                            json.parseToJsonElement(resolvedCall.canonicalInput.ifBlank { "{}" })
                        ) == true && tool.approvalState is ToolApprovalState.Auto -> {
''',
    "GenerationHandler canonical approval args",
)
s = replace_exact(
    s,
    "if (isToolAutoApproved(canonicalToolName)) {",
    "if (isToolAutoApproved(resolvedCall.approvalName)) {",
    "GenerationHandler canonical approval identity",
)

s = replace_exact(
    s,
    '''                        // Tool-name compatibility layer (execution path). This loop is reached
                        // both from the approval loop above and from the resume path, which
                        // rebuilds its list from persisted message parts — so the canonical name
                        // is resolved here again rather than inherited from the earlier scope.
                        val canonicalToolName = ToolNameAliases.canonicalName(tool.toolName)
                        //
''',
    '''                        // Resolve again on the execution/resume path because persisted
                        // message parts keep the requested call rather than the canonical one.
                        val resolvedCall = ToolNameAliases.resolveCall(tool.toolName, tool.input)
                        val canonicalToolName = resolvedCall.canonicalName
                        if (resolvedCall.resolutionError != null) {
                            Log.w(TAG, "generateText: compat-resolution failed for ${tool.toolName}: ${resolvedCall.resolutionError}")
                            executedTools += tool.copy(
                                output = listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                                    put("error", JsonPrimitive("compat_resolution_failed"))
                                    put("detail", JsonPrimitive(resolvedCall.resolutionError))
                                    put("recovery", JsonPrimitive(
                                        "The stored call could not be adapted to the current tool schema, so it was NOT executed."
                                    ))
                                })))
                            )
                            return@forEach
                        }
                        //
''',
    "GenerationHandler execution resolver",
)
s = replace_exact(
    s,
    ".HardlineCommandGuard.checkTool(canonicalToolName, tool.input)",
    ".HardlineCommandGuard.checkTool(canonicalToolName, resolvedCall.canonicalInput)",
    "GenerationHandler resume hardline args",
)
s = replace_exact(
    s,
    '''                        val signature = canonicalToolName + "::" + tool.input
''',
    '''                        val signature = resolvedCall.signature
''',
    "GenerationHandler canonical signature",
)
s = replace_exact(
    s,
    '''                                .map { PriorToolCall(
                                    it.toolName,
                                    ToolNameAliases.canonicalName(it.toolName) + "::" + it.input,
                                    epochMs,
                                ) }
''',
    '''                                .map {
                                    val resolvedPrior = ToolNameAliases.resolveCall(it.toolName, it.input)
                                    PriorToolCall(
                                        resolvedPrior.canonicalName,
                                        resolvedPrior.signature,
                                        epochMs,
                                    )
                                }
''',
    "GenerationHandler canonical prior calls",
)
s = replace_exact(
    s,
    '''                        val parsedArgs = runCatching {
                            json.parseToJsonElement(tool.input.ifBlank { "{}" })
                        }
''',
    '''                        val parsedArgs = runCatching {
                            json.parseToJsonElement(resolvedCall.canonicalInput.ifBlank { "{}" })
                        }
''',
    "GenerationHandler canonical execute args",
)
p.write_text(s, encoding="utf-8")


# ---------------------------------------------------------------------------
# DirectModeActionRunner
# ---------------------------------------------------------------------------
p = Path("app/src/main/java/me/rerere/rikkahub/service/DirectModeActionRunner.kt")
s = p.read_text(encoding="utf-8")
s = replace_exact(
    s,
    '''        data class HardlineBlocked(val reason: String) : StepResult()
        /** The action's tool is not in the available-tools list at fire time (never
''',
    '''        data class HardlineBlocked(val reason: String) : StepResult()
        data class ResolutionError(val reason: String) : StepResult()
        /** The action's tool is not in the available-tools list at fire time (never
''',
    "DirectMode ResolutionError type",
)
s = replace_exact(
    s,
    '''                is StepResult.HardlineBlocked-> return SequenceResult("failed", "action $idx: hardline:${result.reason}")
                // A direct-mode job validates its tool list at creation time, but the
''',
    '''                is StepResult.HardlineBlocked-> return SequenceResult("failed", "action $idx: hardline:${result.reason}")
                is StepResult.ResolutionError -> return SequenceResult("failed", "action $idx: compat_error:${result.reason}")
                // A direct-mode job validates its tool list at creation time, but the
''',
    "DirectMode ResolutionError handling",
)
s = replace_exact(
    s,
    '''        // Tool-name compatibility layer: a direct-mode job persists its action list at creation
        // time, so an action naming a tool that has since been renamed resolves through the
        // canonical table here. Resolution happens BEFORE the HARDLINE arm so a legacy alias to
        // a shell tool is still judged by the canonical name's rules.
        val canonicalToolName = ToolNameAliases.canonicalName(action.tool)
        val hardlineReason = HardlineCommandGuard.checkTool(canonicalToolName, action.args.toString())
        if (hardlineReason != null) {
            Log.w(TAG, "direct-mode hardline-blocked action $idx tool=${action.tool}: $hardlineReason")
            return StepResult.HardlineBlocked(hardlineReason)
        }
        val tool = ToolNameAliases.resolveTool(availableTools, action.tool)
            ?: return StepResult.UnknownTool(action.tool)
        return try {
            val out = withTimeoutOrNull(60_000L) { tool.execute(action.args) }
''',
    '''        // Resolve the whole persisted call before policy, lookup and execution.
        val resolved = ToolNameAliases.resolveCall(action.tool, action.args)
        if (resolved.resolutionError != null) {
            Log.w(TAG, "direct-mode compat-resolution failed action $idx tool=${action.tool}: ${resolved.resolutionError}")
            return StepResult.ResolutionError(resolved.resolutionError)
        }
        val hardlineReason = HardlineCommandGuard.checkTool(resolved.canonicalName, resolved.canonicalInput)
        if (hardlineReason != null) {
            Log.w(TAG, "direct-mode hardline-blocked action $idx tool=${action.tool}: $hardlineReason")
            return StepResult.HardlineBlocked(hardlineReason)
        }
        val tool = availableTools.firstOrNull { it.name == resolved.canonicalName }
            ?: return StepResult.UnknownTool(action.tool)
        val canonicalArgs = runCatching { json.parseToJsonElement(resolved.canonicalInput) }.getOrElse {
            return StepResult.ResolutionError("canonical args unparseable: ${it.message}")
        }
        return try {
            val out = withTimeoutOrNull(60_000L) { tool.execute(canonicalArgs) }
''',
    "DirectMode call-level execution",
)
p.write_text(s, encoding="utf-8")


# ---------------------------------------------------------------------------
# Scheduled-job validation
# ---------------------------------------------------------------------------
p = Path("app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/CronJobTool.kt")
s = p.read_text(encoding="utf-8")
s = replace_exact(
    s,
    '''                    // Validation accepts a legacy name when it resolves to a currently-registered
                    // canonical one, so a job authored before a tool merge keeps validating. An
                    // arbitrary unknown name is still rejected — this is a one-hop alias lookup,
                    // not a widening of the accepted set.
                    val canonicalToolName = ToolNameAliases.canonicalName(toolName)
                    if (canonicalToolName !in knownToolNames)
                        return ValidationError("unknown_tool", "tool '$toolName' not registered for assistant")
                    val hardline = HardlineCommandGuard.checkTool(canonicalToolName, args.toString())
                    if (hardline != null)
                        return ValidationError("hardline_blocked", "action $idx: $hardline")
''',
    '''                    // Validate the canonical call a future composite tool would receive.
                    val resolved = ToolNameAliases.resolveCall(toolName, args)
                    if (resolved.resolutionError != null)
                        return ValidationError("compat_error", "action $idx: ${resolved.resolutionError}")
                    if (resolved.canonicalName !in knownToolNames)
                        return ValidationError("unknown_tool", "tool '$toolName' not registered for assistant")
                    val hardline = HardlineCommandGuard.checkTool(resolved.canonicalName, resolved.canonicalInput)
                    if (hardline != null)
                        return ValidationError("hardline_blocked", "action $idx: $hardline")
''',
    "Cron call-level validation",
)
p.write_text(s, encoding="utf-8")


# ---------------------------------------------------------------------------
# Workflow definition validation
# ---------------------------------------------------------------------------
p = Path("app/src/main/java/me/rerere/rikkahub/workflow/model/WorkflowJson.kt")
s = p.read_text(encoding="utf-8")
s = replace_exact(
    s,
    '''            val toolName = ao["tool"]?.jsonPrimitive?.contentOrNull
                ?: return ParseResult.Err("missing_tool", "action $idx missing 'tool'")
            // knownToolNames is the assistant's currently-registered tool surface. Empty set is
            // a sentinel meaning "skip the check" — used when reading stored definitions back
            // from disk where we trust that what was persisted was already validated.
            // A legacy name that resolves to a currently-registered canonical name is accepted,
            // so a workflow authored before a tool merge keeps validating; an arbitrary unknown
            // name is still rejected. This is a one-hop alias lookup, not a widening of the set.
            val canonicalToolName = ToolNameAliases.canonicalName(toolName)
            if (knownToolNames.isNotEmpty() && canonicalToolName !in knownToolNames) {
''',
    '''            val toolName = ao["tool"]?.jsonPrimitive?.contentOrNull
                ?: return ParseResult.Err("missing_tool", "action $idx missing 'tool'")
            val args = ao["args"] as? JsonObject ?: buildJsonObject { }
            // Resolve name and args before validating the current tool surface.
            val resolved = ToolNameAliases.resolveCall(toolName, args)
            if (resolved.resolutionError != null) {
                return ParseResult.Err("compat_error", "action $idx: ${resolved.resolutionError}")
            }
            val canonicalToolName = resolved.canonicalName
            if (knownToolNames.isNotEmpty() && canonicalToolName !in knownToolNames) {
''',
    "WorkflowJson call-level validation",
)
s = replace_exact(
    s,
    '''            val args = ao["args"] as? JsonObject ?: buildJsonObject { }
            val timeout = ao["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60
''',
    '''            val timeout = ao["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60
''',
    "WorkflowJson duplicate args removal",
)
p.write_text(s, encoding="utf-8")


# ---------------------------------------------------------------------------
# Workflow action execution
# ---------------------------------------------------------------------------
p = Path("app/src/main/java/me/rerere/rikkahub/workflow/execution/WorkflowEngine.kt")
s = p.read_text(encoding="utf-8")
s = replace_exact(
    s,
    '''            val argsJson = action.args.toString()
            // Tool-name compatibility layer: a stored workflow definition keeps the tool name it
            // was authored with. Resolve it to the canonical current name before the HARDLINE arm
            // and before the lookup, so a merged/renamed tool stays fireable and the alias cannot
            // stand in for a name the safety floor would otherwise have matched.
            val canonicalToolName = ToolNameAliases.canonicalName(action.tool)
            val hardlineReason = HardlineCommandGuard.checkTool(canonicalToolName, argsJson)
            if (hardlineReason != null) {
                logSafe("workflow hardline-blocked action $idx tool=${action.tool}: $hardlineReason")
                return RunResult(success = false,
                    error = "action $idx: hardline:$hardlineReason",
                    summary = outputs.joinToString("\\n"))
            }
            val tool = ToolNameAliases.resolveTool(availableTools, action.tool)
                ?: return RunResult(false, "action $idx: unknown_tool:${action.tool}", outputs.joinToString("\\n"))
            val out = try {
                withTimeoutOrNull(action.timeoutSeconds * 1000L) { tool.execute(action.args) }
''',
    '''            val resolved = ToolNameAliases.resolveCall(action.tool, action.args)
            if (resolved.resolutionError != null) {
                logSafe("workflow compat-resolution failed action $idx tool=${action.tool}: ${resolved.resolutionError}")
                return RunResult(false, "action $idx: compat_error:${resolved.resolutionError}", outputs.joinToString("\\n"))
            }
            val hardlineReason = HardlineCommandGuard.checkTool(resolved.canonicalName, resolved.canonicalInput)
            if (hardlineReason != null) {
                logSafe("workflow hardline-blocked action $idx tool=${action.tool}: $hardlineReason")
                return RunResult(success = false,
                    error = "action $idx: hardline:$hardlineReason",
                    summary = outputs.joinToString("\\n"))
            }
            val tool = availableTools.firstOrNull { it.name == resolved.canonicalName }
                ?: return RunResult(false, "action $idx: unknown_tool:${action.tool}", outputs.joinToString("\\n"))
            val canonicalArgs = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(resolved.canonicalInput) }.getOrElse {
                return RunResult(false, "action $idx: compat_error:canonical args unparseable", outputs.joinToString("\\n"))
            }
            val out = try {
                withTimeoutOrNull(action.timeoutSeconds * 1000L) { tool.execute(canonicalArgs) }
''',
    "WorkflowActionRunner call-level execution",
)
p.write_text(s, encoding="utf-8")

print("clean PR13 call-level patches reapplied")
