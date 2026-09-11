package com.tsuty.remoteagent.session

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

/**
 * Releases this window's duplicate-open locks (Ph2 §3) *before* the project is saved on close,
 * fixing a bug where the lock survived an IDE exit/restart.
 *
 * [com.tsuty.remoteagent.service.RemoteAgentService.dispose] already releases every lock it holds,
 * but that runs as part of disposing project services, which happens *after* the platform writes
 * `workspace.xml` on project close (and on IDE exit, which closes every open project the same
 * way). By the time `dispose()` unlocks [SessionMetadataStore], the stale "still locked" state is
 * already on disk, so the next IDE start sees the session as locked by an owner that no longer
 * exists. [projectClosingBeforeSave] fires early enough to beat that save.
 *
 * Registered as a stateless project listener (see `plugin.xml`), so it looks up the project's
 * [SessionLockRegistry] / [SessionMetadataStore] instead of holding any state of its own.
 */
class RemoteAgentProjectCloseListener : ProjectManagerListener {
    override fun projectClosingBeforeSave(project: Project) {
        SessionLockRegistry.getInstance(project).releaseAllBeforeSave(SessionMetadataStore.getInstance(project))
    }
}
