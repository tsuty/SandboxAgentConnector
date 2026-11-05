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

---

## 4. Raising the supported IDE baseline (`pluginSinceBuild`)

Do this when the oldest supported IDE line turns one year old (see "Compatibility Policy" in `LOCAL_INSTALL.md`).
`pluginSinceBuild`, `platformVersion`, `javaVersion`, the verification targets and Kotlin `apiVersion` move together, so change them in one commit.

`javaVersion` sets the bytecode target of the plugin as well as the build JDK. The plugin runs on the JBR bundled with the user's IDE, and a class compiled for a newer Java fails there with `UnsupportedClassVersionError`. `javaVersion` must therefore not exceed the Java version of the JBR of the **oldest** supported IDE. A newer LTS is not a reason to raise it.

1. **Choose the new baseline.** Find the release dates of the IDE lines in the Build Number Ranges page of the IntelliJ Platform Plugin SDK, and drop every line older than one year.
2. **Check the JBR of the new baseline.** Run `./gradlew verifyPlugin` (or `runIde`) once so that the IDE is extracted, then read its JBR:
   ```bash
   find ~/.gradle/caches -path '*/jbr/release' -exec grep -H -E 'JAVA_VERSION=|IMPLEMENTOR_VERSION' {} \;
   ```
   The same is visible in the IDE under Help | About (Runtime version).
3. **Decide `javaVersion`.** Keep it, or raise it up to the `JAVA_VERSION` found in step 2, never above.
4. **Update `gradle.properties`:** `pluginSinceBuild` (e.g. `261`), `platformVersion` (the latest patch of the new baseline) and, if step 3 allows, `javaVersion`.
5. **Update `build.gradle.kts`:** the lower `create("IU", ...)` of `pluginVerification.ides` to the new `platformVersion`, and check that the upper one is still the latest release.
6. **Review Kotlin `apiVersion`** in `build.gradle.kts`. The Kotlin stdlib at runtime comes from the IDE; check which Kotlin the new baseline bundles and raise `apiVersion` to it if possible. See the comment there and "Compatibility Policy" in `LOCAL_INSTALL.md`.
7. **Remove workarounds for the old baseline** that are no longer needed, e.g. the `junit:junit` `testRuntimeOnly` dependency and comments that mention 2025.3.
8. **Verify:** `./gradlew test`, `./gradlew verifyPlugin` and `./gradlew runIde`. If tests fail with `NoSuchMethodError`, rerun with `--no-build-cache --rerun-tasks`.
9. **Update the documents:** the baseline in `Specification.md` (Target Platform) and `LOCAL_INSTALL.md` (Compatibility Policy), and mention the raised minimum IDE in the `<change-notes>` of the next release (see section 1).
10. **Clean up** the extracted IDEs of the dropped lines in the Gradle cache, which are large.

### Checklist

- [ ] `javaVersion` is not above the Java version of the JBR of the new baseline
- [ ] `pluginSinceBuild`, `platformVersion` and the lower verification target name the same IDE line
- [ ] Kotlin `apiVersion` matches the Kotlin bundled with the new baseline
- [ ] `test`, `verifyPlugin` and `runIde` pass
- [ ] `Specification.md`, `LOCAL_INSTALL.md` and the change notes name the new baseline
