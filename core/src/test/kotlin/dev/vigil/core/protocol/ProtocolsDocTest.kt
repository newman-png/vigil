package dev.vigil.core.protocol

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * spec/PROTOCOLS.md is how Claude Code learns the data file, so every field, kind, param, step type, lock type,
 * intensity and trigger the parser accepts must be documented there (the build passes the repo root and reruns this
 * test when the spec changes).
 */
class ProtocolsDocTest {
    private val doc: String by lazy {
        val root = System.getProperty("vigil.repoRoot") ?: File("..").absolutePath
        File(root, "spec/PROTOCOLS.md").readText()
    }

    private fun assertDocumented(what: String, names: Collection<String>) {
        val missing = names.filter { "`$it`" !in doc && "`$it:" !in doc && "`$it " !in doc }
        assertTrue("spec/PROTOCOLS.md does not document these $what: $missing", missing.isEmpty())
    }

    @Test
    fun everyFieldIsDocumented() {
        val objects = listOf(
            ProtocolFile.serializer().descriptor, PoolConfig.serializer().descriptor, HeavyUseConfig.serializer().descriptor,
            BailOutConfig.serializer().descriptor, AppsButtonEntry.serializer().descriptor,
            DayConfig.serializer().descriptor, TakeoverConfig.serializer().descriptor, StagesConfig.serializer().descriptor,
            Protocol.serializer().descriptor, Lock.serializer().descriptor, Step.serializer().descriptor,
        )
        assertDocumented("fields", objects.flatMap { d -> (0 until d.elementsCount).map { d.getElementName(it) } }.toSet())
    }

    @Test
    fun everyKindParamAndValueIsDocumented() {
        assertDocumented("kinds", ProtocolKind.entries.map { it.json })
        assertDocumented("params", ProtocolKind.entries.flatMap { it.params.keys }.toSet())
        assertDocumented("step types", StepType.entries.map { it.json })
        assertDocumented("lock types", LockType.entries.map { it.json })
        assertDocumented("intensities", Intensity.entries.map { it.json })
        assertDocumented("versions", Version.entries.map { it.json })
        assertDocumented("prayers", Prayer.entries.map { it.json })
    }

    @Test
    fun everyTriggerAndAnchorIsDocumented() {
        assertDocumented(
            "triggers",
            listOf("event:wake_alarm", "event:after:<protocol-id>", "event:misses:<n>", "time:<expr>", "manual") +
                SlotType.entries.map { "slot:${it.json}" } + listOf("toggle:walking", "toggle:friends"),
        )
        assertTrue("event:adhan:<prayer>" in doc)
        assertDocumented("time bases", listOf("wake", "bedtime", "meal:breakfast", "meal:lunch", "meal:dinner", "class_start", "class_end"))
    }
}
