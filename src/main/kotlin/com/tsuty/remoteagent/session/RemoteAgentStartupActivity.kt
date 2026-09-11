package com.tsuty.remoteagent.session

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Wipes every persisted duplicate-open lock (Ph2 §3) once, right after the project is opened.
 *
 * We deliberately trust that the same sandbox is never driven from two different IDE processes at
 * once (good faith, not multi-process safety) — supporting that case is out of scope. Under that
 * assumption a lock still on disk at the *next* IDE start can only be stale (left behind by a
 * crash, a `kill -9`, or any other exit that [RemoteAgentProjectCloseListener] did not get to run
 * for), so there is no reason to make the next start wait for its TTL: [SessionMetadataStore] is
 * simply cleared unconditionally. This is a coarser, startup-time safety net on top of
 * [RemoteAgentProjectCloseListener], not a replacement for it — that listener still matters for
 * releasing a lock immediately when one project window closes while another one (in the very same,
 * still running IDE) wants the session right away, well before the next restart.
 */
class RemoteAgentStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        SessionMetadataStore.getInstance(project).clearAllLocks()
    }
}
