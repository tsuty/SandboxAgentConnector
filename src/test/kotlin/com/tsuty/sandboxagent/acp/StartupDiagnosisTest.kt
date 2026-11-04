package com.tsuty.sandboxagent.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Messages of a failed start, built from the checks every sandbox CLI and agent share. */
class StartupDiagnosisTest {

    @Test
    fun `a missing sandbox command is named`() {
        assertEquals(
            "Sandbox command `sbx` was not found on the PATH. " +
                "Install it, or choose another sandbox type in the settings.",
            StartupDiagnosis.sandboxCommandMissing("sbx"),
        )
    }

    @Test
    fun `an unreachable sandbox quotes the output of the cli`() {
        val probe = ProbeResult(1, listOf("Error response from daemon: No such container: my-sbx"))

        assertEquals(
            "Cannot reach sandbox \"my-sbx\": `docker exec` exited with code 1. " +
                "Check that a sandbox with this name exists and is running.\n" +
                "Output of `docker`:\n" +
                "Error response from daemon: No such container: my-sbx",
            StartupDiagnosis.sandboxUnreachable("docker", "my-sbx", probe, 60),
        )
    }

    @Test
    fun `a health check that never ends points at a start or a sign-in`() {
        val message = StartupDiagnosis.sandboxUnreachable("sbx", "my-sbx", ProbeResult(null, emptyList()), 60)

        assertEquals(
            "Cannot reach sandbox \"my-sbx\": `sbx exec` did not finish within 60s. " +
                "The sandbox may still be starting, or `sbx` may be waiting for a sign-in.",
            message,
        )
    }

    @Test
    fun `a launcher is missing on a not found exit code or message`() {
        assertTrue(StartupDiagnosis.launcherMissing(ProbeResult(127, emptyList())))
        assertTrue(StartupDiagnosis.launcherMissing(ProbeResult(126, emptyList())))
        assertTrue(StartupDiagnosis.launcherMissing(ProbeResult(1, listOf("sh: 1: npx: not found"))))
        assertTrue(
            StartupDiagnosis.launcherMissing(
                ProbeResult(1, listOf("OCI runtime exec failed: exec: \"uvx\": executable file not found in \$PATH")),
            ),
        )
    }

    @Test
    fun `a launcher probe that proves nothing is not a missing launcher`() {
        assertFalse(StartupDiagnosis.launcherMissing(ProbeResult(0, listOf("Usage: npx not found anywhere"))))
        assertFalse(StartupDiagnosis.launcherMissing(ProbeResult(null, emptyList())))
        assertFalse(StartupDiagnosis.launcherMissing(ProbeResult(2, listOf("unknown option --help"))))
    }

    @Test
    fun `an agent that exited quotes its last lines`() {
        val output = (1..7).map { "line $it" }

        assertEquals(
            "The agent exited with code 1 before the connection was established.\n" +
                "Last agent output:\nline 3\nline 4\nline 5\nline 6\nline 7",
            StartupDiagnosis.agentExited("my-sbx", "npx", 1, ProbeResult(0, emptyList()), output),
        )
    }

    @Test
    fun `an agent whose launcher is missing says so first`() {
        val message = StartupDiagnosis.agentExited(
            "my-sbx",
            "npx",
            127,
            ProbeResult(127, listOf("sh: npx: not found")),
            listOf("sh: npx: not found"),
        )

        assertEquals(
            "Cannot start the agent: `npx` was not found in sandbox \"my-sbx\". " +
                "Install it in the sandbox, or change the agent command.\n" +
                "The agent exited with code 127 before the connection was established.\n" +
                "Last agent output:\nsh: npx: not found",
            message,
        )
    }

    @Test
    fun `a missing agent bin behind a working launcher is left to the agent output`() {
        // cline through `npx` on Linux: npm links no bin, the shell reports it, npx itself is fine.
        val message = StartupDiagnosis.agentExited(
            "my-sbx",
            "npx",
            127,
            ProbeResult(0, listOf("Usage: npx [options] <command>")),
            listOf("sh: 1: cline: not found"),
        )

        assertEquals(
            "The agent exited with code 127 before the connection was established.\n" +
                "Last agent output:\nsh: 1: cline: not found",
            message,
        )
    }

    @Test
    fun `a silent agent keeps the advice about the download`() {
        val message = StartupDiagnosis.agentSilent("ACP request 'initialize' timed out", emptyList())

        assertEquals(
            "ACP request 'initialize' timed out. The agent may not have finished downloading: retry " +
                "(the download is cached), pre-install the agent in the VM, or raise " +
                "\"Agent startup idle timeout\" in the settings.",
            message,
        )
    }
}
