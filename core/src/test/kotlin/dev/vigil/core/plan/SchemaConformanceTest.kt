package dev.vigil.core.plan

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import dev.vigil.core.engine.BuiltinPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps spec/schema.json (what the AI planner reads) and [PlanParser] (what the app enforces) in agreement.
 *
 * Fixtures live in src/test/resources/fixtures, one problem each:
 * - invalid/: both must reject; "_expect_error_at" is the path the parser must report.
 * - valid-with-warnings/: both must accept; "_expect_warning_at" is the path the parser must warn about.
 * - valid/: both must accept, without warnings.
 */
class SchemaConformanceTest {
    private val repoRoot = File(System.getProperty("vigil.repoRoot") ?: error("vigil.repoRoot is not set"))
    private val mapper = ObjectMapper()
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)

    // Draft-07 validators treat "format" as an assertion by default; say so explicitly so a library update
    // cannot quietly stop checking "date".
    private val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()

    private val schemaFile = File(repoRoot, "spec/schema.json")
    private val schemaNode: JsonNode = mapper.readTree(schemaFile)
    private val schema: JsonSchema = factory.getSchema(schemaNode, config)

    private fun schemaErrors(text: String): List<String> =
        schema.validate(mapper.readTree(text)).map { "${it.instanceLocation}: ${it.message}" }.sorted()

    private fun fixtures(folder: String): List<File> {
        val url = javaClass.getResource("/fixtures/$folder") ?: error("Missing test resource folder fixtures/$folder")
        return File(url.toURI()).listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
    }

    /** Asserts both the schema and the parser accept [text] and returns the parser's warnings. */
    private fun assertAcceptedByBoth(name: String, text: String): List<Issue> {
        val errors = schemaErrors(text)
        assertTrue("$name: schema rejects it:\n${errors.joinToString("\n")}", errors.isEmpty())
        return when (val result = PlanParser.parse(text)) {
            is ParseResult.Success -> result.warnings
            is ParseResult.Failure -> {
                fail("$name: parser rejects it:\n${result.errors.joinToString("\n")}")
                emptyList()
            }
        }
    }

    @Test
    fun schemaIsAValidDraft07Schema() {
        val metaSchema = factory.getSchema(SchemaLocation.of("http://json-schema.org/draft-07/schema#"), config)
        val errors = metaSchema.validate(schemaNode)
        assertTrue("schema.json is not valid draft-07:\n${errors.joinToString("\n")}", errors.isEmpty())
        assertEquals("http://json-schema.org/draft-07/schema#", schemaNode["\$schema"].asText())
        assertTrue("schema.json needs an \$id", schemaNode["\$id"]?.asText().orEmpty().isNotBlank())
        assertTrue("schema.json needs a title", schemaNode["title"]?.asText().orEmpty().isNotBlank())
    }

    /**
     * The planner has no memory beyond this file, so every field must explain itself. Conditions (if/then/not)
     * only restate fields described elsewhere, so they are skipped.
     */
    @Test
    fun everyPropertyHasADescription() {
        val missing = mutableListOf<String>()
        fun walk(node: JsonNode, path: String) {
            if (node.isArray) node.forEachIndexed { i, v -> walk(v, "$path/$i") }
            if (!node.isObject) return
            for (group in listOf("properties", "definitions")) {
                node[group]?.fields()?.forEach { (name, sub) ->
                    if (sub["description"]?.asText().isNullOrBlank()) missing += "$path/$group/$name"
                }
            }
            node.fields().forEach { (k, v) -> if (k !in setOf("if", "then", "not")) walk(v, "$path/$k") }
        }
        walk(schemaNode, "#")
        assertTrue("These have no description:\n${missing.joinToString("\n")}", missing.isEmpty())
        assertTrue(schemaNode["description"].asText().isNotBlank())
    }

    @Test
    fun samplePlanConformsWithoutWarnings() {
        val text = File(repoRoot, "spec/sample-plan.json").readText()
        assertEquals(emptyList<Issue>(), assertAcceptedByBoth("spec/sample-plan.json", text))
    }

    @Test
    fun builtinPlanConformsWithoutWarnings() {
        assertEquals(emptyList<Issue>(), assertAcceptedByBoth("default-plan.json", BuiltinPlan.text))
    }

    /** The sample is what planners copy, so it must show every feature in the shape the task brief asks for. */
    @Test
    fun samplePlanShowsEveryFeature() {
        val file = File(repoRoot, "spec/sample-plan.json")
        val text = file.readText()
        val firstKey = mapper.readTree(text).fieldNames().next()
        assertEquals("sample-plan.json must start with _readme", "_readme", firstKey)

        val plan = (PlanParser.parse(text) as ParseResult.Success).plan
        assertTrue("8-14 directives", plan.directives.size in 8..14)
        assertEquals(setOf(1, 2, 3), plan.directives.map { it.tier }.toSet())
        assertEquals(Trigger.SUPPORTED_TYPES.toSet(), plan.directives.map { it.trigger.type }.toSet())
        val limits = plan.directives.mapNotNull { (it.trigger as? Trigger.AppUsage)?.limit }
        assertTrue("an opens trigger", limits.any { it is UsageLimit.Opens })
        assertTrue("a continuous trigger", limits.any { it is UsageLimit.Continuous })
        for (prayer in Prayer.entries) {
            val d = plan.directives.single { (it.trigger as? Trigger.Adhan)?.prayer == prayer }
            assertTrue("$prayer must be kept on a bad day", d.id in plan.badDay.keep)
        }
        val fajr = plan.directives.single { (it.trigger as? Trigger.Adhan)?.prayer == Prayer.FAJR }
        assertEquals(QuietPolicy.OVERRIDE, fajr.quiet)

        val fallback = plan.fallbackPlan?.directives ?: error("sample needs a fallback_plan")
        assertTrue("fallback_plan has 3-4 directives", fallback.size in 3..4)
        val mainIds = plan.directives.map { it.id }.toSet()
        assertTrue("fallback ids must differ from main ids", fallback.none { it.id in mainIds })
    }

    @Test
    fun invalidFixturesAreRejectedByBoth() {
        val files = fixtures("invalid")
        assertTrue("expected the invalid fixtures, found ${files.size}", files.size >= 20)
        val problems = mutableListOf<String>()
        for (file in files) {
            val text = file.readText()
            val expectAt = mapper.readTree(text)["_expect_error_at"]?.asText()
                ?: error("${file.name} has no _expect_error_at")
            if (schemaErrors(text).isEmpty()) problems += "${file.name}: schema accepts it"
            when (val result = PlanParser.parse(text)) {
                is ParseResult.Success -> problems += "${file.name}: parser accepts it"
                is ParseResult.Failure -> if (result.errors.none { it.path.startsWith(expectAt) }) {
                    problems += "${file.name}: parser reports no error at $expectAt, only ${result.errors}"
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun fixturesWithWarningsAreAcceptedByBoth() {
        val files = fixtures("valid-with-warnings")
        assertTrue("expected the warning fixtures, found ${files.size}", files.size >= 3)
        for (file in files) {
            val text = file.readText()
            val expectAt = mapper.readTree(text)["_expect_warning_at"]?.asText()
                ?: error("${file.name} has no _expect_warning_at")
            val warnings = assertAcceptedByBoth(file.name, text)
            assertTrue(
                "${file.name}: expected a warning at $expectAt, got $warnings",
                warnings.any { it.path.startsWith(expectAt) },
            )
        }
    }

    @Test
    fun validFixturesAreAcceptedByBothWithoutWarnings() {
        val files = fixtures("valid")
        assertTrue("expected the valid fixtures, found ${files.size}", files.size >= 3)
        for (file in files) {
            assertEquals(file.name, emptyList<Issue>(), assertAcceptedByBoth(file.name, file.readText()))
        }
    }

    /** Every reserved type, and any type a newer planner might invent, loads with a warning instead of failing. */
    @Test
    fun reservedAndUnknownTriggerTypesAreAcceptedByBoth() {
        val base = fixtures("valid").single { it.name == "base.json" }.readText()
        for (type in Trigger.RESERVED_TYPES + "something_new") {
            val text = base.replace("\"type\": \"time\", \"at\": \"10:30\"", "\"type\": \"$type\", \"at\": \"10:30\"")
            assertTrue("$type was not substituted", text != base)
            val warnings = assertAcceptedByBoth(type, text)
            assertTrue("$type must be warned about", warnings.any { it.path == "directives[1].trigger.type" })
        }
    }
}
