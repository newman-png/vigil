package dev.vigil.core.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The example plan in spec/HANDOFF.md is what planners copy, so it must stay valid and warning-free. */
class HandoffExampleTest {
    private val handoff = File(System.getProperty("vigil.repoRoot"), "spec/HANDOFF.md").readText()

    @Test
    fun examplePlanParsesWithoutWarnings() {
        val example = Regex("```json\\n(\\{\\n  \"_readme\".*?)\\n```", RegexOption.DOT_MATCHES_ALL)
            .find(handoff)?.groupValues?.get(1)
            ?: return fail("No full example plan found in HANDOFF.md")
        when (val result = PlanParser.parse(example)) {
            is ParseResult.Success -> assertEquals("warnings: ${result.warnings}", emptyList<Issue>(), result.warnings)
            is ParseResult.Failure -> fail("HANDOFF.md example is invalid: ${result.errors}")
        }
    }

    @Test
    fun triggerSnippetsUseOnlySupportedTypes() {
        val types = Regex("\"type\":\\s*\"([a-z_]+)\"").findAll(handoff).map { it.groupValues[1] }.toSet()
        assertTrue("HANDOFF.md mentions unsupported trigger types: $types", Trigger.SUPPORTED_TYPES.containsAll(types))
    }
}
