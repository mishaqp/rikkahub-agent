"""Reconcile the pinned upstream release with the fork; run only during its merge."""
import pathlib, re, subprocess

ROOT = pathlib.Path(".")
BASE = "da2936b7187f8cb0031121b8f8f908f65143f38b"
def original(path):
    return subprocess.check_output(["git", "show", f"{BASE}:{path}"]).decode()
def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)
def replace(text, old, new, count=1):
    assert text.count(old) == count, (old[:100], text.count(old), count)
    return text.replace(old, new)
def resolve(path, choices):
    p = ROOT / path
    n = 0
    def choose(m):
        nonlocal n
        c = choices[n]; n += 1
        a, b = m.group(1, 2)
        return a+b if c == "both" else a if c == "ours" else b
    value = re.sub(r"^<<<<<<< .*\n([\s\S]*?)^=======\n([\s\S]*?)^>>>>>>> .*\n", choose, p.read_text(), flags=re.M)
    assert n == len(choices), (path, n, choices)
    write(path, value)
    return value

# Keep the fork's tool factory and generation runtime. Upstream's rename/extraction
# is structural; copying its factory would remove invocation context and tool grants.
for path in ["app/src/main/java/me/rerere/rikkahub/di/AppModule.kt",
             "app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
             "app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt"]:
    write(path, original(path))
for path in ["app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt",
             "app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt",
             "README_ZH_CN.md", "README_ZH_TW.md"]:
    (ROOT / path).unlink(missing_ok=True)
write("README.md", original("README.md") + "\n## Upstream 2.5.0 integration\n\nIncludes voice conversations, editable message queues, custom Responses API paths, Exa search updates, workspace image thumbnails and terminal improvements. Retains the fork's agent runtime, tool grants, compaction, local models and integrations.\n")

resolve("ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt", ["both"])
p = "ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt"
s = resolve(p, ["both", "theirs"])
s = replace(s, '            Log.i(TAG, "generateText: $bodyStr")',
'''            if (Logging.isDebugLoggingEnabled()) {
                Log.i(TAG, "generateText: \u0024{redactSecrets(json.parseToJsonElement(bodyStr))}")
            }''')
write(p, s)

resolve("app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt", ["theirs", "theirs"])
resolve("app/src/main/java/me/rerere/rikkahub/ui/hooks/ChatInputState.kt", ["theirs"])
resolve("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt", ["both"])
resolve("app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt", ["ours"])
resolve("gradle/libs.versions.toml", ["theirs"])
for p in ROOT.glob("app/src/main/res/values*/strings.xml"):
    if "<<<<<<< " in p.read_text():
        resolve(str(p), ["both"])

p = "app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailPage.kt"
s = (ROOT/p).read_text()
blocks = list(re.finditer(r"^<<<<<<< .*\n([\s\S]*?)^=======\n([\s\S]*?)^>>>>>>> .*\n", s, re.M))
replacements = [
    blocks[0].group(1)+blocks[0].group(2),
    blocks[1].group(1)+blocks[1].group(2),
    blocks[2].group(1) + "                area = state.area,\n                onResolveImage = { onResolveImage(row.entry, state.area) },\n",
    blocks[3].group(1)+blocks[3].group(2),
    blocks[4].group(1).split("            Icon(\n")[0] + blocks[4].group(2),
]
for m, value in reversed(list(zip(blocks, replacements))):
    s = s[:m.start()]+value+s[m.end():]
write(p, s)

p = "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt"
s = resolve(p, ["both","ours","ours","both","ours","ours","theirs","theirs","ours","both","ours","ours","ours","ours","ours","ours","ours","both","ours"])
s = s.replace("private val generationLoop: GenerationLoop", "private val generationHandler: GenerationHandler")
s = replace(s, "private val chatToolFactory: ChatToolFactory", "private val localTools: LocalTools")
# Preserve the complete fork tool set. Memory tools remain in GenerationHandler.
start = s.index("            val tools = try {\n")
end = s.index("            // start generating\n", start)
s = s[:start]+s[end:]
old = original(p)
start = old.index("    private suspend fun createWorkspaceToolsIfReady(")
end = old.index("\n    // ----", start)
helper = old[start:end]
s = replace(s, "    // ---- 检查无效消息 ----", helper + "\n\n    // ---- 检查无效消息 ----")
if "import me.rerere.workspace.WorkspaceShellStatus\n" not in s:
    s = s.replace("import java.time.Instant\n", "import me.rerere.workspace.WorkspaceShellStatus\nimport java.time.Instant\n")
# Use the fork's foreground tracker; lazy jobs are registered before execution.
start = s.index("    private fun launchGenerationJob(")
end = s.index("    // ---- 初始化", start)
s = s[:start]+'''    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job = appScope.launch(start = CoroutineStart.LAZY) {
        val release = if (keepAliveInBackground) foregroundWorkTracker.acquire() else ({})
        try {
            if (keepAliveInBackground) awaitForegroundWorkReady()
            block()
        } finally {
            release()
        }
    }

'''+s[end:]
start = s.index("    private fun sendQueuedMessage(")
end = s.index("    private suspend fun tryFastPathRoute(", start)
segment = s[start:end]
segment = replace(segment, "                saveConversation(conversationId, withUser)\n", "                saveConversation(conversationId, withUser)\n                session.submittingMessage = null\n")
segment = replace(segment, "            } finally {\n                releaseForegroundWork()\n", "")
s = s[:start]+segment+s[end:]

# Approval decisions form a non-cancelling chain; keep Once/Chat/Always grants,
# hydration and the fork's per-conversation mutation lock.
start = s.index("    fun handleToolApproval(")
end = s.index("    /** Always-scope grant", start)
segment = s[start:end]
segment = replace(segment, "    ) {\n", "    ) = synchronized(getOrCreateSession(conversationId)) {\n")
segment = replace(segment, "        val job = appScope.launch {\n", "        val job = appScope.launch(start = CoroutineStart.LAZY) {\n")
segment = replace(segment, "                convMutex.withLock {\n", "                afterPreviousGeneration(priorGenerationJob) {\n                var shouldResume = false\n                convMutex.withLock {\n")
segment = replace(segment, "                    priorGenerationJob?.let { runCatching { it.cancelAndJoin() } }\n", "")
segment = replace(segment, "                    val conversation = session.state.value\n", """                    val conversation = session.state.value
                    if (conversation.currentMessages.none { message ->
                            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                        }) return@withLock
""")
segment = replace(segment, "                    if (!hasPendingTools) {\n", "                    shouldResume = !hasPendingTools\n                    if (!hasPendingTools) {\n")
segment = replace(segment, "                if (!pendingNow) {\n", "                if (shouldResume && !pendingNow) {\n")
segment = replace(segment, "                _generationDoneFlow.emit(conversationId)\n", "                _generationDoneFlow.emit(conversationId)\n                }\n")
s = s[:start]+segment+s[end:]
# Preserve stop-after-restart hydration, but cancel every queued approval job.
s = replace(s, "        sessions[conversationId]?.getJob()?.let { runCatching { it.cancelAndJoin() } }\n", """        sessions[conversationId]?.let { session ->
            val jobs = synchronized(session) {
                session.messageQueue.pause()
                session.cancelJobs()
            }
            jobs.forEach { it.join() }
        }
""")
s = replace(s, "        return true\n\n        // 删除消息", "        // 删除消息")
s = replace(s, "        dispatchNextQueuedMessage(conversationId)\n    }\n\n    // ---- 翻译", "        dispatchNextQueuedMessage(conversationId)\n        return true\n    }\n\n    // ---- 翻译")
# Do not advance queued turns after a generation error or an MCP naming failure.
s = replace(s, "        generationResult.onFailure {\n", "        generationResult.onFailure {\n            if (it is CancellationException) throw it\n            sessions[conversationId]?.messageQueue?.pause()\n")
s = replace(s, "                        if (invalidNames.isNotEmpty()) {\n", "                        if (invalidNames.isNotEmpty()) {\n                            session.messageQueue.pause()\n")
# Duplicate imports can arise when both sides add Coroutine/Android imports.
seen = set()
lines = []
for line in s.splitlines(keepends=True):
    if line.startswith("import "):
        if line in seen: continue
        seen.add(line)
    lines.append(line)
write(p, "".join(lines))

# Release code/name follow upstream, while application ID and signing stay fork-specific.
p = "app/build.gradle.kts"
s = (ROOT/p).read_text()
s = re.sub(r'versionCode = \d+', 'versionCode = 184', s, count=1)
s = re.sub(r'versionName = "[^"]+"', 'versionName = "2.5.0-agent.1"', s, count=1)
s = replace(s, 'signingConfig = signingConfigs.getByName("release")',
            'signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }')
write(p, s)
# Fail closed on incomplete reconciliation.
for p in ROOT.rglob("*"):
    if p.is_file() and ".git" not in p.parts and p.suffix in {".kt", ".kts", ".xml", ".toml"}:
        assert not re.search(r"^(<<<<<<< |=======\s*$|>>>>>>> )", p.read_text(errors="replace"), re.M), str(p)
subprocess.run(["git", "add", "-A"], check=True)
assert not subprocess.check_output(["git", "ls-files", "-u"])
