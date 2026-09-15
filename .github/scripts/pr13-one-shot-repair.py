from pathlib import Path


def replace_exact(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


def replace_count(text: str, old: str, new: str, expected: int, label: str) -> str:
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{label}: expected exactly {expected} matches, got {count}")
    return text.replace(old, new)


# 1) Repair the generic types lost while GenerationHandler.kt was reconstructed.
# The two conversation-set parameters occur in both generateText() and generateInternal().
gh_path = Path("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt")
gh = gh_path.read_text(encoding="utf-8")
gh = replace_count(
    gh,
    "conversationModeInjectionIds: Set<String> = emptySet(),",
    "conversationModeInjectionIds: Set<Uuid> = emptySet(),",
    2,
    "GenerationHandler modeInjectionIds type",
)
gh = replace_count(
    gh,
    "conversationLorebookIds: Set<String> = emptySet(),",
    "conversationLorebookIds: Set<Uuid> = emptySet(),",
    2,
    "GenerationHandler lorebookIds type",
)
gh = replace_exact(
    gh,
    "providerImpl: Provider,",
    "providerImpl: Provider<ProviderSetting>,",
    "GenerationHandler Provider generic",
)
gh_path.write_text(gh, encoding="utf-8")


# 2) Complete the last persisted-call path: ChatService.rerunTool.
cs_path = Path("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
cs = cs_path.read_text(encoding="utf-8")

# Json is needed only to materialize the canonical transformed args for Tool.execute().
if "import kotlinx.serialization.json.Json\n" not in cs:
    cs = replace_exact(
        cs,
        "import kotlinx.serialization.json.jsonObject\n",
        "import kotlinx.serialization.json.Json\nimport kotlinx.serialization.json.jsonObject\n",
        "ChatService Json import",
    )

cs = replace_exact(
    cs,
    "val (toolPart, tool) = mutexFor(conversationId).withLock {",
    "val (toolPart, tool, resolvedCall) = mutexFor(conversationId).withLock {",
    "ChatService rerun destructuring",
)

old_policy = '''                // Tool-name compatibility layer: resolve a legacy name to its canonical current
                // form BEFORE the HARDLINE arm and the lookup, so a renamed tool stays runnable
                // from an old conversation row without weakening the safety floor. This path
                // never sends schemas to a model; it only re-runs one already-executed call.
                val canonicalToolName = ToolNameAliases.canonicalName(toolPart.toolName)
                val hardlineReason = me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
                    .checkTool(canonicalToolName, toolPart.input)
'''
new_policy = '''                // Resolve the whole persisted call BEFORE policy and lookup. A future
                // composite-tool migration can change both the tool name and its argument schema;
                // HARDLINE must inspect the exact canonical call that would execute.
                val resolvedCall = ToolNameAliases.resolveCall(toolPart.toolName, toolPart.input)
                if (resolvedCall.resolutionError != null) {
                    return RerunToolResult.Failure(
                        "compatibility resolution failed: ${resolvedCall.resolutionError}"
                    )
                }
                val hardlineReason = me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
                    .checkTool(resolvedCall.canonicalName, resolvedCall.canonicalInput)
'''
cs = replace_exact(cs, old_policy, new_policy, "ChatService rerun policy block")

cs = replace_exact(
    cs,
    "val tool = ToolNameAliases.resolveTool(tools, toolPart.toolName)",
    "val tool = tools.firstOrNull { it.name == resolvedCall.canonicalName }",
    "ChatService rerun canonical lookup",
)
cs = replace_exact(
    cs,
    "                toolPart to tool\n",
    "                Triple(toolPart, tool, resolvedCall)\n",
    "ChatService rerun return tuple",
)
cs = replace_exact(
    cs,
    "withTimeoutOrNull(60_000L) { tool.execute(toolPart.inputAsJson()) }",
    "withTimeoutOrNull(60_000L) {\n                    tool.execute(Json.parseToJsonElement(resolvedCall.canonicalInput))\n                }",
    "ChatService rerun canonical execute args",
)
cs_path.write_text(cs, encoding="utf-8")

print("PR #13 one-shot repair applied successfully")
