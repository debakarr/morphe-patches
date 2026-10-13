# Development guide

How a change goes from an edit to a verified patched app on a phone, and from there to a release that
Morphe Manager can install. Everything below is what we actually do; commands are copy-pasteable.

- [What lives where](#what-lives-where)
- [One-time setup](#one-time-setup)
- [The loop: build → test → patch → install → verify](#the-loop)
- [Per-app verification notes](#per-app-verification-notes)
- [Debugging](#debugging)
- [Using REA to debug and modify an app](#using-rea-to-debug-and-modify-an-app)
- [Deployment](#deployment)
- [Troubleshooting](#troubleshooting)

## What lives where

| Path | What it is |
|---|---|
| `extensions/extension/src/main/java/…` | Java that runs **inside the patched app**: response rewriters (`FlipkartAtlas…`, `MeeshoListing`, `MyntraListing`), sort-menu injectors (`*SortMenu`), the Amazon page script (`AmazonSortScript`), the overlay (`SortOverlay`, `SortState`) and the ranking logic (`Ranker`, `RankedStore`). |
| `patches/src/main/kotlin/…/<app>/` | Per app: `Fingerprints.kt` (how to find the methods to hook) and `SortByRatingsCountPatch.kt` (what to inject). |
| `patches/src/main/kotlin/…/shared/` | `Constants.kt` (supported apps and versions), `StringResultHook.kt` (inject after a call), `InstallAlongsidePatch.kt` (the rename patch). |
| `extensions/extension/src/test/…` | JVM unit tests for everything above (real `org.json`, no device needed). |
| `patches-bundle.json`, `patches-list.json`, `CHANGELOG.md`, `gradle.properties` version | Written by the release workflow. Don't edit by hand. |

A patch only injects a few `invoke-static` calls into the app; the real work happens in the extension, which is
plain Java and unit-testable. Keep it that way: put logic in the extension, keep patches thin.

## One-time setup

You need: JDK 21 (full JDK), the Android SDK, `adb`, `gh`, and a phone with USB debugging.

```bash
# Gradle needs the SDK and a token that can read GitHub Packages (the Morphe Gradle plugin lives there).
gh auth refresh -h github.com -s read:packages
export ANDROID_HOME=~/android-sdk            # build-tools 36 provides aapt2 + apksigner
export GITHUB_ACTOR=<your-github-user> GITHUB_TOKEN=$(gh auth token)

# Tools used outside Gradle (any directory; the examples use ~/.local/opt):
#  - Morphe CLI (patches an APK):  https://github.com/MorpheApp/morphe-desktop/releases  (morphe-desktop-<ver>-all.jar)
#  - apkeep (downloads a specific app version):  https://github.com/EFForg/apkeep/releases
#  - jadx (read an app's code):  https://github.com/skylot/jadx/releases

# A throwaway key to sign your test builds. Any password; never reuse a real key.
keytool -genkeypair -storetype PKCS12 -keystore ~/test.p12 -storepass changeit -keypass changeit \
  -alias test -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Test"
```

Keep large files (stock APKs, decompiled sources) on disk, not in `/tmp`: `/tmp` is often a small RAM disk, and
when it fills even command output is lost.

## The loop

### 1. Build

```bash
./gradlew patches:build
ls patches/build/libs/patches-*.mpp       # the bundle; its version is the last *released* one (gradle.properties)
```

The first build compiles the extension (`extension.mpe`) and the patches together. If it fails resolving
`app.morphe.patches`, the token is missing `read:packages`.

### 2. Unit tests

```bash
./gradlew :extensions:extension:testDebugUnitTest
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' extensions/extension/build/test-results/testDebugUnitTest/*.xml
```

Every rewriter has tests built from the **real response shape** of the app (copy a dumped response, trim it).
The Amazon page script is a Java text block, so a test also writes it out as
`extensions/extension/build/amazon-sort.compiled.js`; check it parses and run it against a real page:

```bash
node --check extensions/extension/build/amazon-sort.compiled.js
# optional DOM check: load a saved amazon.in search page in jsdom (npm i jsdom), eval the file, click the controls
```

### 3. Get the *original* APK of the exact version you target

Morphe patches the unpatched app. Two ways to get it:

```bash
# a) A specific version from the web
apkeep -a com.flipkart.android@9.13 out/        # -> out/com.flipkart.android@9.13.xapk

# b) The copy installed on your phone (pull the base APK *and* every split)
adb shell pm path com.myntra.android            # lists base.apk + split_*.apk
adb pull <each path> out/
```

Apps shipped as splits must be handed to the Morphe CLI as one **`.xapk`** (a zip with a `manifest.json` listing
the splits, see the example below); the CLI merges the splits and writes a single patched APK. Patching a lone
`base.apk` of a split app produces something that cannot be installed alone.

```bash
# build an .xapk from a base + splits
python3 - <<'EOF'
import zipfile, json, os
files = [('base','base.apk'), ('config.arm64_v8a','split_config.arm64_v8a.apk'), ('config.xhdpi','split_config.xhdpi.apk')]
manifest = {"xapk_version": 2, "package_name": "com.myntra.android", "name": "Myntra",
            "version_code": "80110582", "version_name": "4.2609.30",
            "split_apks": [{"file": f, "id": i} for i, f in files]}
with zipfile.ZipFile('myntra.xapk', 'w', zipfile.ZIP_STORED) as z:
    z.writestr('manifest.json', json.dumps(manifest))
    for _, f in files: z.write(f)
EOF
```

### 4. Patch

```bash
rm -f patched.apk                                 # a stale output hides a failed patch run
java -jar morphe-desktop-<ver>-all.jar patch \
  -p patches/build/libs/patches-<ver>.mpp \
  -e "Sort by number of ratings" -e "Install alongside original" --exclusive \
  --keystore ~/test.p12 --keystore-password changeit \
  --keystore-entry-alias test --keystore-entry-password changeit \
  -t ./tmp -o patched.apk myntra.xapk
```

Read the output: every patch must say `Applied:`. `FAILED:` means a fingerprint did not match this version or
an injected smali snippet is invalid. Typical causes:

- **`Collection is empty`** from `addInstructions`: the smali did not compile. Methods with many registers
  need the `/range` form (`invoke-static/range {p1 .. p1}, …`).
- **Fingerprint not found**: the app changed. Decompile the new version and update `Fingerprints.kt`.

Patcher log lines from your own patch code (`Logger.info/severe`) are **not shown** by the CLI. To prove an
edit landed, check the output instead:

```bash
unzip -p patched.apk 'classes*.dex' | grep -ac 'yourHelperMethodName'
~/android-sdk/build-tools/36.0.0/aapt2 dump badging patched.apk | head -3     # package, label
~/android-sdk/build-tools/36.0.0/aapt2 dump xmltree --file res/layout/some.xml patched.apk
```

Leave **Install alongside original** enabled for device tests: the copy gets its own package name
(`<package>.sorted`) so it never needs the original uninstalled.

### 5. Install on the device

```bash
adb devices                                       # the phone must show "device"
adb install -r patched.apk                        # the renamed copy; -r keeps its data
adb install-multiple base.apk split_*.apk         # a stock app (all splits, signed by the same key)
adb uninstall com.myntra.android.sorted           # only needed to downgrade or to start from nothing
adb shell pm clear com.flipkart.android.sorted    # fresh first-run state (see below)
```

- Do not try to install a patched APK over the **stock** app: the signature differs and Android refuses. That is
  exactly why the rename patch exists.
- Re-signing: a stock app installed from separate splits needs all splits signed with **one** key
  (`apksigner sign --ks ~/test.p12 --ks-pass pass:changeit --ks-key-alias test <apk>` for each) or you get `signatures are inconsistent`.
- USB on slow phones drops during large installs. If `adb` says "no devices": `adb wait-for-device`, then retry,
  and put a `timeout` on every adb call so a hang does not stall the session.
- Apps cache pages. After changing what a rewriter does to a page, run `adb shell pm clear <package>` (this
  signs you out of the renamed test copy only) or you will keep seeing the cached, unrewritten page.

### 6. Verify on the phone

Always look at the screen and the log, not only at "it installed".

```bash
adb logcat -c                                              # clear, then act in the app
adb shell monkey -p com.flipkart.android.sorted -c android.intent.category.LAUNCHER 1
adb exec-out screencap -p > shot.png                       # look at it
adb logcat -d -v time | grep -E 'MorpheSort|FATAL EXCEPTION'   # our log tag, and any crash
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml   # text/ids of native screens
```

A change is verified when you have seen, on a real phone:

1. The app **launches** and reaches its home screen (no `FATAL EXCEPTION`).
2. The floating buttons appear on a listing screen, and each one does what it says
   (Sort: Off → Most rated → Top rated, 4★+, Hide ads, Ranked).
3. The app's **own Sort menu** lists *Most rated* and *Top rated*, and choosing one switches the mode.
4. Results are really reordered (compare counts on screen), sponsored items are gone with Hide ads on, and a
   second page keeps working.
5. The renamed copy behaves like the stock app (login screens, product pages) apart from the known limits.

The `README.md` "Verified on a device" paragraph lists what has been checked; update it when you check
another app version.

## Per-app verification notes

| App | How we reach a listing | Gotchas |
|---|---|---|
| Flipkart | `adb shell am start -a android.intent.action.VIEW -d "https://www.flipkart.com/search?q=wireless+earbuds" com.flipkart.android.sorted` | First run shows language/login/notification/location prompts. Sort lives in **Sort & Filter → Sort**. The Filters page is **cached**, so after an update clear the app's data to see new menu entries. |
| Myntra | Type in the search box (the web gateway is bot-blocked, so there are no sample responses to download). | Search results come through the layout engine; the Sort sheet is the bottom bar. Start-up pop-ups swallow taps: take a screenshot between steps. |
| Meesho | Tap a search suggestion (the keyboard's Enter does not always submit). | Skip onboarding with *English → Skip*. |
| Amazon | Launch the renamed copy and use the search box (the renamed app does not resolve `https://` deep links). | The page is a WebView with a scrim you cannot remove: it is Amazon's own, also present in the stock app. Sort entries are under **Filters → Sort by**. |

## Debugging

- **Dump real responses.** In `Diag.java` set `ENABLED = true` (and loosen the size/keyword filters if needed),
  rebuild, patch, install, use the app, then:
  `adb pull /storage/emulated/0/Android/data/<package>/files/morphe-dump/`. Turn it **off again before
  committing**; the release build must never write dumps.
- **Read an app's code.** `jadx -j 3 --no-res -d out app.apk` (cap memory with `JAVA_OPTS=-Xmx3g` and never run
  several at once). Class names are obfuscated and change between versions; fingerprints should key on stable
  strings, signatures and framework names.
- **React Native apps** (Flipkart, Myntra) keep their screens in a Hermes bundle you cannot decompile; hook the
  native bridge (network modules, layout engine) and read the **JSON** instead, using the dumps.
- **REA** (device and code analysis through an MCP server or CLI): see
  [Using REA to debug and modify an app](#using-rea-to-debug-and-modify-an-app).
- **When a sort looks wrong,** check the order of the data, not the pixels: write a unit test with the dumped
  response first.

## Using REA to debug and modify an app

[REA](https://github.com/morluto/rea) reads apps and devices and returns digested, citable results. Here it is the
fastest way to answer "which method do I hook?" and "what did the app just do?". It **never runs the APK
itself**; it reads the file, or asks `adb` about the phone.

### Set it up once

REA is an MCP server for your agent and also a CLI (`npx -y rea-agents@6.4.0 <command>`; below `rea` means that).
Reading app code needs two extra things, which REA does not install for you:

```bash
mkdir -p ~/.local/opt/rea && cd ~/.local/opt/rea
V=0.7.1                                            # the release REA audits
curl -sLO https://github.com/1013503897/jadx-headless-mcp/releases/download/v$V/jadx-headless-mcp-$V-all.jar
curl -sLO https://github.com/1013503897/jadx-headless-mcp/releases/download/v$V/jadx-headless-mcp.sha256
sha256sum -c jadx-headless-mcp.sha256              # must say OK (the file lists the jar's name)
export REA_JADX_MCP_JAR=$PWD/jadx-headless-mcp-$V-all.jar
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64    # a full JDK 17+, not just a JRE
rea doctor                                         # see the note below: it audits native engines too
```

`rea doctor` audits every REA engine (Hopper, Ghidra, …) and the host OS, so it can say `healthy: false` (for example on Debian, which REA does not list as supported) while the Android code tools work. What matters here is only that `rea search-android-classes <apk> <text>` returns results; a missing `REA_JADX_MCP_JAR` shows up as `capability_unavailable` on the first code command.

For the **MCP server**, put the same two variables in the server's `env` (Claude Code keeps it in
`~/.claude.json` under `mcpServers.rea`), then reconnect the server (`/mcp`). It only reads its environment when it
starts.

```json
"rea": { "command": "npx", "args": ["-y", "rea-agents@6.4.0", "mcp"],
         "env": { "REA_JADX_MCP_JAR": "/home/you/.local/opt/rea/jadx-headless-mcp-0.7.1-all.jar",
                  "JAVA_HOME": "/usr/lib/jvm/java-21-openjdk-amd64" } }
```

Device commands (anything `adb`) need no JAR. Code commands need a **standalone APK** (the app's `base.apk`);
for a split app the code is in `base.apk`.

### Debug: what is the app doing on the phone?

| Question | CLI | MCP tool |
|---|---|---|
| Is the phone attached? | `rea list-adb-devices` | `list_adb_devices` |
| Which package / version? | `rea resolve-adb-packages <serial> flipkart` then `rea inspect-adb-package <serial> <package>` | `resolve_adb_packages`, `inspect_adb_package` |
| What is on screen? | `rea capture-adb-screen <serial> --output-directory ./shots` (writes `screen.png`) | `capture_adb_screen` |
| Which window has focus? | `rea inspect-adb-window <serial>` | `inspect_adb_window` |
| The app's log | `rea read-adb-logcat <serial> --count 400 [--pid <pid>]` | `read_adb_logcat` |
| Start / stop the app | `rea start-adb-app <serial> <package>`, `rea stop-adb-app <serial> <package>` | `start_adb_app`, `stop_adb_app` |
| Install / replace / remove | `rea install-adb-package <serial> <apk> [--replace]`, `rea uninstall-adb-package <serial> <package>` | `install_adb_package`, `uninstall_adb_package` |
| Get the stock APKs off the phone | `rea pull-adb-package <serial> <package> --output-directory ./out` | `pull_adb_package` |

`<serial>` is the value `list-adb-devices` prints (for example `79c4c40b7d43`). `--replace` keeps the app's data
(it is `adb install -r`); `uninstall-adb-package` deletes the app's data, so use it only on test copies.
`pull-adb-package` creates `./out/<package>/` and refuses to run if it already exists.

A typical debugging pass: `start-adb-app` → do the action in the app → `capture-adb-screen` (look at the PNG)
→ `read-adb-logcat` and look for `MorpheSort` or a crash.

### Modify: find the method to hook

1. **Pull the stock APK** (`pull-adb-package`, or use the one you patch from) and note its sha256.
2. **Look for the class.** Class names are obfuscated, so search by the *stable* part, a framework or feature
   name, or by a string you saw in a response:
   ```bash
   rea search-android-classes base.apk <text-in-the-class-name> --filter-output normalized_result.matches --format json
   ```
   (If it fails with `JADX class inventory is duplicated…`, see *What REA cannot do here*.)
3. **Read it.** List members, then decompile one method (give `--overload-index` when the name repeats):
   ```bash
   rea inspect-android-class  base.apk com.myntra.android.react.nativemodules.LayoutEngine.LayoutEngineModule
   rea inspect-android-method base.apk com.myntra.android.react.nativemodules.LayoutEngine.LayoutEngineModule bridgePageData \
       --filter-output normalized_result.source.text --format json
   ```
4. **Find who calls it** to confirm it is on the path you care about:
   ```bash
   rea trace-android-references base.apk <class> --method-name <unique method>
   ```
5. **Write the fingerprint** (`Fingerprints.kt`): match on the class and method name when they are stable, else on
   the signature, a distinctive string, or a custom predicate. Then add the `invoke-static` in the patch. Registers:
   plain `{p1}` only works for low registers, so use `invoke-static/range {p1 .. p1}` for big methods.
6. **Confirm what the data looks like** before parsing it: turn on `Diag` (see Debugging), run the app, and
   build the unit test from the dumped JSON.
7. Then follow *The loop*: build, test, patch, install, verify. If a hook silently does nothing, check the
   *output* APK (`unzip -p patched.apk 'classes*.dex' | grep -ac <helper method>`), not the patch log.

`--filter-output <key.path>` and `--format json` keep results small; results over the client's size limit are
otherwise saved to a file (the tool tells you where), and you read them with `jq` (`jq '.normalized_result | keys' file`).

### What REA cannot do here

- **Hermes (React Native) apps** (Flipkart, Myntra-style apps): the screens' logic is bytecode in
  `assets/index.android.bundle` that JADX cannot decompile. REA sees only the Java/Kotlin native modules. Read the
  *data* the modules hand to JavaScript instead (dump it with `Diag`).
- **`search-android-classes` can fail** on some APKs with `JADX class inventory is duplicated or differs from the
  loaded class count` (seen on Myntra). `inspect-android-class` and `inspect-android-method` with the exact name
  still work; for broad searches use plain jadx (`jadx -j 3 --no-res -d out base.apk`, then `grep -r`).
- **Runtime behaviour** is not observed: it reads code and the phone's state. Confirm on the device.
- It does not give you anyone's server rules. Limits a service enforces on its side (plans, quotas) are not
  something a client patch should work around.

## Deployment

Releases are cut by GitHub Actions with semantic-release; there is nothing to build or upload by hand.

1. **Commit with a conventional message.** The first line decides the version bump:
   `feat:` → minor, `fix:` / `perf:` → patch, `docs:` / `chore:` / `test:` → no release.
   Scope is optional (`feat(flipkart): …`). End AI-assisted commits with the `Co-Authored-By` line.
2. **Pre-flight**: tests pass, `Diag.ENABLED` is `false`, `git status` is clean, the change was verified on a
   device (above).
3. **Push.** The release workflow runs on `main` (and publishes pre-releases from `dev`):
   ```bash
   git fetch origin && git rebase origin/main     # the workflow adds a "Release vX.Y.Z" commit, so you are often behind
   git push origin main
   ```
4. **Release.**
   ```bash
   gh workflow run release.yml --ref main
   gh run list --workflow release.yml --limit 1             # copy the run id
   gh run watch <run-id> --exit-status
   ```
   The workflow computes the next version, builds the bundle, writes `CHANGELOG.md`, `patches-bundle.json`,
   `patches-list.json` and `SHA256SUMS.txt`, commits them as `chore: Release vX.Y.Z [skip ci]`, and attaches
   `patches-X.Y.Z.mpp` to a GitHub release.
5. **Verify the release.**
   ```bash
   git pull --rebase origin main && git log --oneline -3
   gh release view --json tagName,assets -q '.tagName, (.assets[].name)'
   curl -sL https://raw.githubusercontent.com/debakarr/morphe-patches/main/patches-bundle.json   # version + download_url
   ```
   A docs-only push does not release; running the workflow then simply reports no new version.
6. **Smoke-test the released bundle**, not just your local build:
   ```bash
   gh release download vX.Y.Z -p 'patches-X.Y.Z.mpp' -D .
   # patch an APK with that file (step 4 of the loop) and install it
   ```

### Installing through Morphe Manager

1. In Morphe Manager: **Sources → +**, add `github.com/debakarr/morphe-patches` (it reports "Valid repository").
2. Open the app, pick **I already have an APK** and choose the **`.xapk`** of the *original* app (the Manager
   cannot read an installed app directly for these split apps).
3. Leave **Install alongside original** on, enable **Sort by number of ratings**, patch, install.
4. Android asks once to **allow installs from Morphe**: allow it in Settings.
5. If the Manager reports a package conflict, the patch run did not include *Install alongside original*.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Gradle: plugin `app.morphe.patches` not found | Token lacks `read:packages`, or `GITHUB_ACTOR`/`GITHUB_TOKEN` not exported. |
| Gradle: SDK location not found | Set `ANDROID_HOME`. |
| Patch: `Collection is empty` | Invalid smali; use the `/range` invoke form. |
| Patch: `FAILED` for one patch, older APK still installed | You installed a stale output. Delete the output before patching. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / signatures inconsistent | Different signing key; uninstall the copy or re-sign all splits with one key. |
| Renamed app crashes with `ClassNotFoundException …sorted…` | A layout or manifest names a class relative to the package; the rename patch must write out the original package. |
| Renamed app crashes with `SecurityException` on a provider | A hard-coded provider authority; add it to the rename patch's rewrite. |
| New menu entries missing after an update | The app serves a cached page; `pm clear` the test copy. |
| `adb: no devices/emulators found` mid-install | USB dropped; `adb wait-for-device`, retry, always use timeouts. |
| Everything silently fails / no output | `/tmp` is full; move work to disk. |
| Release run is green but nothing new | No `feat`/`fix` commits since the last release. |
