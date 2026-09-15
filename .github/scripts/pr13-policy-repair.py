from pathlib import Path


def replace_exact(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


path = Path("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt")
text = path.read_text(encoding="utf-8")

# Approval policy must inspect the canonical argument schema, not the persisted legacy one.
text = replace_exact(
    text,
    "toolDef?.needsApproval(tool.inputAsJson()) == true && tool.approvalState is ToolApprovalState.Auto -> {",
    '''toolDef?.needsApproval(
                            json.parseToJsonElement(resolvedCall.canonicalInput.ifBlank { "{}" })
                        ) == true && tool.approvalState is ToolApprovalState.Auto -> {''',
    "canonical approval args",
)

# LoopGuard's read-only/action reset logic consumes PriorToolCall.toolName too, so prior calls
# must carry the canonical name as well as the canonical signature.
old_prior = '''                                .map {
                                    PriorToolCall(
                                        it.toolName,
                                        ToolNameAliases.resolveCall(it.toolName, it.input).signature,
                                        epochMs,
                                    )
                                }
'''
new_prior = '''                                .map {
                                    val resolvedPrior = ToolNameAliases.resolveCall(it.toolName, it.input)
                                    PriorToolCall(
                                        resolvedPrior.canonicalName,
                                        resolvedPrior.signature,
                                        epochMs,
                                    )
                                }
'''
text = replace_exact(text, old_prior, new_prior, "canonical prior tool identity")

path.write_text(text, encoding="utf-8")
print("PR #13 policy repair applied successfully")
