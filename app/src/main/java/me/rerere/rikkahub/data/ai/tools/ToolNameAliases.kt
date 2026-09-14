package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool

/**
 * Tool Name Compatibility Layer.
 *
 * ## Why this exists
 *
 * Tool names are persisted outside the running process: scheduled jobs (`mode='direct'`
 * action lists), workflow definitions, `UIMessagePart.Tool.toolName` inside stored
 * conversations, and Telegram/headless call sites all keep the *string* a model emitted
 * at the time the row was written. A later refactor that renames or folds an exposed tool
 * into a composite would therefore break every already-saved reference with
 * `tool_not_found` / `unknown_tool` — for rows the user cannot even edit any more.
 *
 * This object is the single, deliberately tiny indirection that lets such a rename happen:
 * a stored **legacy** name is mapped to the **canonical current** name before anything that
 * cares about the real action (HARDLINE, approval, loop guard, tool lookup, execution) runs.
 *
 * ## Contract
 *
 *  - **Flat, one hop.** `legacy -> canonical`. A target must not itself be a key of the map;
 *    chains are rejected by [validate]. That keeps resolution O(1) and impossible to
 *    mis-order.
 *  - **Unknown names pass through unchanged.** No entry means "this is already canonical",
 *    so the helper is a no-op on every name the app ships today.
 *  - **MCP names are never aliased.** Anything starting with `mcp__` is returned verbatim —
 *    those are namespaced per-server names minted at runtime by
 *    [me.rerere.rikkahub.data.ai.mcp.buildMcpToolName], not part of this table.
 *  - **Policy sees the canonical name.** Callers must resolve *before* consulting
 *    [HardlineCommandGuard] / [ToolApprovalDefaults], never after: resolving afterwards would
 *    let a legacy alias to a shell tool skip the safety floor that the canonical name trips.
 *  - **The requested name is never overwritten.** History, debugging output and error
 *    envelopes keep the name the model actually used; only internal policy/dispatch lookups
 *    switch to the canonical name.
 *
 * ## Current state of the table
 *
 * [ALIASES] is intentionally **empty**. This file is pure infrastructure: it prepares the
 * rename path so a future "merge N tools into one composite" PR only has to add entries
 * here. Until such a PR lands, [canonicalName] returns its argument unchanged for every
 * input and the app behaves exactly as it did before this layer existed.
 */
object ToolNameAliases {

    /** Namespace prefix minted at runtime for MCP-relayed tools. Never aliased. */
    private const val MCP_TOOL_PREFIX = "mcp__"

    /**
     * Production alias table: `legacyName -> canonicalCurrentName`.
     *
     * MUST stay empty until the corresponding composite tool actually exists. Adding an entry
     * here is the *last* step of a merge PR, never the first: the canonical target has to be a
     * registered tool name, or resolution would turn a working legacy call into a lookup miss.
     *
     * Invariants (enforced by [validate] in tests):
     *  - no blank key or value;
     *  - no self-alias (`A -> A`);
     *  - no chain (`A -> B` where `B` is itself a key);
     *  - no `mcp__` key.
     */
    val ALIASES: Map<String, String> = emptyMap()

    /**
     * Outcome of resolving one requested name. Carries both spellings so a caller can keep the
     * original for history/telemetry while routing policy and dispatch through the canonical one.
     */
    data class Resolution(
        val requestedName: String,
        val canonicalName: String,
    ) {
        /** True when [requestedName] was a legacy name that [ALIASES] redirected. */
        val aliased: Boolean get() = requestedName != canonicalName
    }

    /**
     * Resolve [requested] against the production table. Returns a [Resolution] whose
     * [Resolution.canonicalName] equals [requested] when nothing matched — the helper is a
     * no-op for every currently-shipped tool name.
     */
    fun resolve(requested: String): Resolution = resolve(requested, ALIASES)

    /** Convenience for call sites that only need the canonical spelling. */
    fun canonicalName(requested: String): String = resolve(requested).canonicalName

    /**
     * Find the [Tool] that should execute [requestedName], resolving a legacy name first.
     *
     * Returns null exactly when the canonical name is not among [tools] — i.e. the caller's
     * existing "tool_not_found" path stays in charge of the error envelope.
     */
    fun resolveTool(tools: List<Tool>, requestedName: String): Tool? {
        val canonical = canonicalName(requestedName)
        return tools.firstOrNull { it.name == canonical }
    }

    /**
     * Test/inspection seam: resolve against an explicit table instead of [ALIASES]. Production
     * call sites must use the single-argument overload; this exists so the rules above can be
     * exercised with synthetic mappings.
     */
    internal fun resolve(requested: String, aliases: Map<String, String>): Resolution {
        if (requested.isEmpty()) return Resolution(requested, requested)
        // MCP-relayed tools are namespaced at runtime and are not part of this table. Skip the
        // lookup entirely rather than relying on the table happening to have no `mcp__` key.
        if (requested.startsWith(MCP_TOOL_PREFIX)) return Resolution(requested, requested)
        return Resolution(requested, aliases[requested] ?: requested)
    }

    /**
     * Structural validation of an alias table. Returns a list of human-readable problems; an
     * empty list means the table satisfies every invariant in [ALIASES]'s documentation.
     *
     * Called by unit tests today and by the future merge PR before it ships a non-empty table —
     * a cyclic or chained table would make resolution non-deterministic in a way that is very
     * hard to debug from a `tool_not_found` seen only on the user's device.
     */
    internal fun validate(aliases: Map<String, String>): List<String> {
        val problems = mutableListOf<String>()
        for ((legacy, canonical) in aliases) {
            if (legacy.isBlank()) {
                problems += "blank legacy name"
                continue
            }
            if (canonical.isBlank()) {
                problems += "'$legacy' maps to a blank canonical name"
                continue
            }
            if (legacy == canonical) {
                problems += "'$legacy' is a self-alias"
            }
            if (legacy.startsWith(MCP_TOOL_PREFIX)) {
                problems += "'$legacy' is an MCP-relayed name and must not be aliased"
            }
            if (canonical.startsWith(MCP_TOOL_PREFIX)) {
                problems += "'$legacy' targets an MCP-relayed name, which is runtime-minted"
            }
            if (canonical in aliases) {
                problems += "'$legacy' -> '$canonical' is a chain: '$canonical' is itself a legacy name"
            }
        }
        return problems
    }
}
