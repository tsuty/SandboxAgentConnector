# Sandbox Agent Connector Release Procedure

This document describes how to raise the plugin version and publish a release on GitHub.

A release is a `v<version>` tag (e.g. `v0.0.2`). Pushing the tag runs `../../.github/workflows/release.yml`,
which builds the plugin with `-PpluginVersion=<version>` and creates a GitHub release with the plugin ZIP
and generated notes. The workflow overrides `pluginVersion` with the tag, so nothing stops a release whose
sources still carry the previous version or lack its change notes; the steps below keep them in line.

Publishing and signing for JetBrains Marketplace (`publishPlugin` / `signPlugin`) are not performed.

---

## 1. Raising the version

Do both of the following in the same commit:

1. Set `pluginVersion` in `../../gradle.properties` to the new version (without the leading `v`).
2. Add a section for the new version to `<change-notes>` in `../../src/main/resources/META-INF/plugin.xml`.

### Writing the change notes

- Put the new section **first**: the sections are ordered from the newest version to the oldest.
- The heading is the bare version, the same string as `pluginVersion`: `<h3>0.0.3</h3>`.
- Describe the changes since the previous release from the user's point of view: new features, changed
  behavior, removed settings, fixes. Leave out refactorings, tests, documentation and development tools.
- Name UI elements and actions as they appear in the IDE, inside `<code>`.
- Never rewrite the sections of versions already released.

The commits to go through are listed with:

```bash
git log --oneline v<previous version>..HEAD
```

---

## 2. Releasing

1. Merge the version commit into `main`.
2. Tag the merge result and push the tag:

   ```bash
   git tag v<version>
   git push origin v<version>
   ```

3. Check that the `Release` workflow succeeded and that the release has
   `SandboxAgentConnector-<version>.zip` attached.

---

## 3. Checklist

- [ ] `pluginVersion` in `gradle.properties` is the new version
- [ ] `<change-notes>` starts with `<h3><version></h3>` and covers every user-facing change since the previous tag
- [ ] The tag is `v` + `pluginVersion`
- [ ] The release has `SandboxAgentConnector-<version>.zip` attached

The version in the examples of `LOCAL_INSTALL.md` is a fixed sample and is not updated on a release.
