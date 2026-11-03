# Sandbox Agent Connector Release Procedure

This document describes how to raise the plugin version and publish a release on GitHub.

A release is a `v<version>` tag (e.g. `v0.0.2`). Pushing the tag runs `../../.github/workflows/release.yml`,
which builds the plugin with `-PpluginVersion=<version>` and creates a GitHub release with the plugin ZIP,
whose notes are the message of the tag (`gh release create --notes-from-tag`). The tag must therefore be an
annotated one; the workflow fails before the build when it is not. The workflow overrides `pluginVersion` with the tag, so nothing stops a release whose
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
2. Write the release notes to a file outside the repository (see below), tag the merge result with them
   and push the tag:

   ```bash
   git tag -a v<version> --cleanup=verbatim -F <notes file>
   git tag -l v<version> --format='%(contents)'   # check the message before pushing
   git push origin v<version>
   ```

3. Check that the `Release` workflow succeeded and that the release has
   `SandboxAgentConnector-<version>.zip` attached and the notes of the tag.

### Writing the tag message

- The whole message becomes the release notes, as GitHub Markdown. The release title is the tag already,
  so the message does not need to repeat the version.
- It says the same as the `<change-notes>` section of this version, converted from the HTML of
  `plugin.xml`: `<li>` items become `- ` items and `<code>` becomes backticks.
- `--cleanup=verbatim` keeps the lines that start with `#`, i.e. Markdown headings. Git's default cleanup
  takes them for comments and removes them, even from a message given with `-F`; without headings, `-m` or
  `-F` alone is enough.
- A tag made without `-a` (and without `-m` / `-F`) is lightweight and has no message: the workflow fails
  and no release is created. Replace it with an annotated one:

  ```bash
  git tag -d v<version>
  git push --delete origin v<version>
  git tag -a v<version> --cleanup=verbatim -F <notes file>
  git push origin v<version>
  ```

---

## 3. Checklist

- [ ] `pluginVersion` in `gradle.properties` is the new version
- [ ] `<change-notes>` starts with `<h3><version></h3>` and covers every user-facing change since the previous tag
- [ ] The tag is `v` + `pluginVersion`
- [ ] The tag is annotated and its message is the release notes, matching `<change-notes>`
- [ ] The release has `SandboxAgentConnector-<version>.zip` attached and shows the notes of the tag

The version in the examples of `LOCAL_INSTALL.md` is a fixed sample and is not updated on a release.
