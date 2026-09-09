# Remote Agent Connector Local Installation Guide

This document provides step-by-step instructions for building the `RemoteAgentConnector` plugin from source and installing it into your local JetBrains IDE via "Install Plugin from Disk".

Publishing and signing for JetBrains Marketplace (`publishPlugin` / `signPlugin`) are not performed, as they are unnecessary for local installation.

---

## 1. Prerequisites

| Item | Requirement |
|---|---|
| JDK | JDK 21 (`javaVersion=21` in `../../gradle.properties` is applied via `kotlin { jvmToolchain(21) }`). Gradle must be able to resolve a JDK 21 toolchain (the JVM that runs Gradle itself only needs 17 or higher) |
| Build Tool | Bundled Gradle Wrapper (`./gradlew`) |
| Target IDE | JetBrains IDE with build number `242` (2024.2 branch) or later, with the Markdown plugin enabled (IntelliJ IDEA, GoLand, WebStorm, PyCharm, etc.) |
| Network | Required during the initial build to download the IntelliJ Platform and dependencies |

The plugin declares two mandatory dependencies (`com.intellij.modules.platform` and `org.intellij.plugins.markdown`) and one optional dependency (`org.jetbrains.plugins.terminal`).
The Markdown plugin is bundled with most JetBrains IDEs, so the plugin can also be used in IDEs tailored for specific languages, but it **cannot be installed or enabled in IDEs where the Markdown plugin is missing or disabled**.
The Terminal plugin is used for the "Open Sandbox Terminal" feature; if it is missing, that specific feature will be unavailable, but the rest of the plugin will function normally.

---

## 2. Building

Run the following command in the repository root:

```bash
./gradlew clean buildPlugin
```

Artifact:

```
build/distributions/RemoteAgentConnector-0.1.0.zip
```

The `0.1.0` part corresponds to `pluginVersion` in `../../gradle.properties`.

Internal structure of the ZIP (`unzip -l build/distributions/RemoteAgentConnector-0.1.0.zip`):

```
RemoteAgentConnector/lib/RemoteAgentConnector-0.1.0.jar
RemoteAgentConnector/lib/kotlin-stdlib-2.4.10.jar
RemoteAgentConnector/lib/kotlinx-serialization-core-jvm-1.7.3.jar
RemoteAgentConnector/lib/kotlinx-serialization-json-jvm-1.7.3.jar
RemoteAgentConnector/lib/annotations-13.0.jar
RemoteAgentConnector/lib/RemoteAgentConnector-0.1.0-searchableOptions.jar
```

> The initial clean build takes about 2 minutes because `buildSearchableOptions` launches a headless IDE.
> Subsequent builds reuse the Configuration Cache and complete in a few hundred milliseconds.
> (These timings are rough estimates and depend on your machine and network.)

### Optional: Pre-installation Verification

```bash
./gradlew verifyPluginProjectConfiguration   # Verify project configuration
./gradlew verifyPluginStructure              # Verify ZIP structure and plugin.xml
./gradlew verifyPlugin                       # Verify binary compatibility using Plugin Verifier
```

The report from `verifyPlugin` will be generated at `build/reports/pluginVerifier/<IDE build number>/report.html`.
Verification targets are explicitly configured in `../../build.gradle.kts` under `pluginVerification { ides { ... } }` (the baseline IntelliJ IDEA Community 2024.2.5 and the latest existing 2025.2.6).
As of version 0.1.0, there are **0 compatibility issues (Compatible)** for both IDEs.

---

## 3. Installation to IDE

1. Launch your target IDE.
2. Open `Settings/Preferences` (macOS: `⌘,` / Windows & Linux: `Ctrl+Alt+S`).
3. Select `Plugins` in the left sidebar.
4. Click the gear icon **⚙** at the top and select `Install Plugin from Disk...`.
5. In the file chooser dialog, select `../../build/distributions/RemoteAgentConnector-0.1.0.zip`.
   (Do not extract the ZIP file; select the ZIP file directly.)
6. When the `Restart IDE` button appears, click it to restart the IDE.

---

## 4. Verification (Smoke Check)

After restarting, verify the following points:

1. **Tool Window**
   - `Remote Agent` is displayed on the right sidebar of the IDE.
   - Clicking it opens the tool window.
   - If it is not displayed, you can open it via `View → Tool Windows → Remote Agent`.
2. **Settings Page**
   - `Settings/Preferences → Tools → Remote Agent Connector` exists.
   - You can configure the sandbox CLI path, agent execution command, etc.
3. **Plugin List**
   - `Remote Agent Connector` (version `0.1.0`) is listed and enabled under `Settings/Preferences → Plugins → Installed`.

---

## 5. Updating, Uninstalling, and Reinstalling

### Updating (Replacing with the same version number)

If you overwrite-install a ZIP when the same plugin ID (`com.tsuty.remoteagent`) is already installed, older JAR files may remain depending on the environment.
To ensure a clean replacement, uninstall the plugin first.

1. Select `Remote Agent Connector` under `Settings → Plugins → Installed`.
2. Click the gear icon **⚙** → `Uninstall`.
3. Restart the IDE.
4. Install the new ZIP via `Install Plugin from Disk...` and restart the IDE again.

### Distributing with an Incremented Version

Update `pluginVersion` in `../../gradle.properties`, run `./gradlew clean buildPlugin`, and install the newly named ZIP file.

### Uninstalling

Perform only steps 1 to 3 above. Configuration values (`RemoteAgentSettingsState`) remain in the IDE's configuration directory, so they will be preserved upon reinstallation.

---

## 6. Compatibility Policy

- `pluginSinceBuild=242` — Can be installed on IDE build `242` (2024.2 branch) and later.
- `until-build` is **intentionally not specified**.
  This ensures future IDE builds will not reject the plugin as "incompatible" and can be installed directly.
- The build platform is fixed to `platformVersion=2024.2.5` (`IC`).
  We prioritize compatibility with older IDEs and mechanically check for missing APIs in newer IDEs using Plugin Verifier.
- The mandatory dependencies are `com.intellij.modules.platform` and `org.intellij.plugins.markdown`, which are bundled with most IDEs.
  Therefore the plugin can be installed not only on IntelliJ IDEA, but also on GoLand, WebStorm, PyCharm, etc. — as long as the Markdown plugin is present and enabled.
- The optional dependency `org.jetbrains.plugins.terminal` is used to provide the sandbox terminal feature within the IDE.

---

## 7. Troubleshooting

### "Plugin is incompatible" is displayed

- The target IDE build number may be older than `242`.
  Check your build number under `Help → About`. Versions `241.x` and earlier are not supported.
- Check whether an extracted directory or a standalone JAR was selected instead of the ZIP file.
  Make sure to select `RemoteAgentConnector-<version>.zip`.

### Tool window is not displayed

- Check whether the plugin is disabled under `Settings → Plugins → Installed`.
- Check whether the required `Markdown` plugin is disabled; if it is, this plugin is automatically disabled as well.
- Try opening via `View → Tool Windows → Remote Agent`.
- If it still does not appear, check `idea.log` for exceptions (see below).

### Kotlin stdlib Exceptions (`NoSuchMethodError` / `ClassNotFoundException`, etc.)

This plugin **intentionally bundles the Kotlin standard library**
(`verifyPluginProjectConfiguration` reports this as a warning, but it is accepted as policy).
If runtime exceptions occur due to conflicts with the IDE's bundled Kotlin stdlib, you can avoid them by rebuilding without bundling it.

Add the following to `../../gradle.properties`:

```properties
kotlin.stdlib.default.dependency=false
```

Then perform a clean build:

```bash
./gradlew clean buildPlugin
```

Verify that `kotlin-stdlib-*.jar` is not included in `lib/` of the generated ZIP, then uninstall and reinstall the plugin.

Note that the official documentation (*Kotlin for Plugin Development*) recommends pinning `apiVersion` to the Kotlin version bundled with the target IDE.
It is currently not set, so consider adding something like the following to `../../build.gradle.kts` if needed:

```kotlin
kotlin {
    compilerOptions {
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9
    }
}
```

### Nothing happens after installation / Error dialog appears at startup

Check `idea.log`. The fastest way to open it from the IDE is `Help → Show Log in Finder/Explorer` (or `Help → Collect Logs and Diagnostic Data`).

Example paths (`<Product>` refers to the product directory such as `IntelliJIdea2025.2`):

| OS | Path |
|---|---|
| macOS | `~/Library/Logs/JetBrains/<Product>/idea.log` |
| Linux | `~/.cache/JetBrains/<Product>/log/idea.log` |
| Windows | `%LOCALAPPDATA%\JetBrains\<Product>\log\idea.log` |

Search for `com.tsuty.remoteagent` to filter stack traces originating from this plugin.

### Use the Sandbox for Development Verification

If you want to verify behavior without modifying your main IDE environment, launch the sandbox IDE:

```bash
./gradlew runIde
```

The plugin is placed in `build/idea-sandbox/<ide-build>/plugins/RemoteAgentConnector/lib/`, and settings/logs are isolated under the sandbox directory (`build/idea-sandbox/<ide-build>/log/idea.log`).

---

## 8. Final Verification Checklist on Actual IDE

Follow and check off each item below in order:

- [ ] `./gradlew clean buildPlugin` finishes with `BUILD SUCCESSFUL`
- [ ] `../../build/distributions/RemoteAgentConnector-0.1.0.zip` exists
- [ ] Target IDE's build number in `Help → About` is `242` or later
- [ ] The `Markdown` plugin is enabled under `Settings → Plugins` in the target IDE
- [ ] Successfully selected the ZIP via `Settings → Plugins → ⚙ → Install Plugin from Disk...`
- [ ] Clicked `Restart IDE` and the IDE restarted normally
- [ ] `Remote Agent Connector 0.1.0` appears in `Settings → Plugins → Installed` in an enabled state
- [ ] `Remote Agent` tool window appears on the right sidebar of the IDE and opens upon clicking
- [ ] The settings page at `Settings → Tools → Remote Agent Connector` opens and values can be saved
- [ ] `idea.log` has no ERROR / exceptions originating from `com.tsuty.remoteagent`
- [ ] (Optional) Start a session with the configured agent command and receive a response
