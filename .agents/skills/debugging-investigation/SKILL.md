---
name: debugging-investigation
description: 'Step-by-step incident diagnosis for production issues — log retrieval, trace filtering, crash analysis — and for failing or hanging tests: diagnostics bundle, thread dumps, frozen frame clock, headless DI-graph probe, DebugProbes for silent coroutine death.'
---

# Debugging Investigation

## When to use

- A production incident is reported (crash, sync failure, data loss, UI freeze)
- An AI agent is asked to investigate a failing feature
- You need to reproduce a reported issue

## Step-by-step

### Step 1 — Gather context

Ask or determine:
1. Which profile was affected (Profile A, Profile B, all profiles)
2. When did the issue first appear (timestamp or version)
3. Is the issue reproducible (yes/no/sometimes)
4. What is the expected behavior vs actual behavior

### Step 2 — Retrieve relevant logs

```bash
# Android: pull logs from device
adb shell "logcat -d -t 1000" | grep "traceId=XXXX" > /tmp/incident-log.txt

# Desktop: find log file
ls ~/.local/share/singularity/logs/
cat ~/.local/share/singularity/logs/singularity.log | grep "traceId=XXXX"
```

### Step 3 — Filter by trace ID

Every operation chain has a `traceId`. Use it to find all related log entries:

```bash
grep "traceId=abc123" /tmp/incident-log.txt | sort
```

### Step 4 — Identify the failure point

Look for:
- `ERROR` level entries — these are the actual failures
- `WARN` entries before the error — these are the root cause or contributing factors
- State dumps at ERROR — often show the variable values that caused the failure

### Step 5 — Check crash reporting

Crashes and non-fatals are reported to **AppTracer** (`ru.ok.tracer`) on Android. Desktop
has no crash reporting; use its local log file instead.

1. Find the crash group for the affected version
2. Note the exception type and stack trace
3. Check `issueKey` to see the grouping — an `AppError` code like `error.not_found` means
   the app named the failure; a call-site label means it was a raw exception
4. Check if the same crash has occurred before
5. Read the **Keys** and **Data** tabs: the startup breadcrumb carries device/build context,
   and each ViewModel's call-site label names the operation that failed

**History.** This step used to point at Firebase Crashlytics, which was never configured —
the project had no crash SDK at all. It also told you to filter logs by `traceId`, a field
that has never existed. Both were aspirational notes that read as instructions.

### Step 6 — Reproduce if possible

If the issue is reproducible:
1. Enable DEBUG logging: Settings → Developer → Log level → DEBUG
2. Reproduce the exact steps
3. Pull logs and filter by traceId

## Common patterns

### Sync failures

```
WARN  [SyncEngine] sync() failed: Conflict detected for TaskId(...)
  → Resolution: check ConflictResolver for the task
  → If data loss: file ADR retro + recovery plan
```

### Crash on startup

```
FATAL [App] onCreate() threw
  → Check: database migration, SecureStorage decryption, profile initialization
  → Fix: adb shell "dumpsys activity a" for stack at crash time
```

### UI freeze / ANR

```
ANR: Input dispatching timed out
  → Check: main thread blocking call (DB read, network, SharedPreferences)
  → Fix: move to background thread, use缓/async
```

### Data loss

```
WARN  [TaskRepository] delete() returned 0 affected rows
  → Check: was the task actually in the DB? Was profile isolation violated?
  → Fix: restore from backup if available
```

## Decision tree: what to do with each finding

```
LOG SHOWS ERROR
  │
  ├─── Is it a sync conflict?
  │         YES → Check ConflictResolver, file incident report
  │         NO  → Continue
  │
  ├─── Is it a crash?
  │         YES → Check AppTracer for the crash group, inspect the stack trace
  │         NO  → Continue
  │
  ├─── Is it a data loss?
  │         YES → Check backup restoration, file retro ADR
  │         NO  → Continue
  │
  └─── Unknown → escalate to senior engineer
```

## Writing it up: incident report / retro ADR

Once you have the root cause, write it down — an investigation that lives only in the
terminal is gone by the next session. The decision tree above says "file incident report"
and "file retro ADR"; this is what that looks like.

**Investigation in progress** (cause known, fix not yet landed) — an incident report:

```markdown
---
date: YYYY-MM-DD
status: open
tags: [incident, <area>]
---

# <Symptom as the user saw it>

## Timeline
- <when it started, and what preceded it — deploy, config change, data migration>
- <what was already ruled out>

## Root cause
<The mechanism, not the symptom. "The debounced write loop re-triggered on its own
emission, so the title field never settled" — not "the title was wrong".>

Evidence: <log excerpt, traceId, failing test, or the query that showed it.>

## Blast radius
<Which profiles, which data, whether it self-heals, whether a backup is needed.>

## Fix or workaround
- Fix: <what actually changes, and where>
- Workaround: <what unblocks users now, if the fix is not ready>
```

**After the fix** — flip `status: open` to `accepted` and add `## Prevention`: what now
stops this class of bug. Usually one of:

- a test that fails without the fix (the strongest kind)
- a detekt rule or Konsist test (`singularity-todo-detekt-rules-authoring`)
- a pattern note in the relevant skill, so the next agent does not rediscover it
- nothing — a genuine one-off, in which case say so rather than inventing prevention

**Worth keeping:** "why did this pass review and tests" is a more useful question than
"how did we fix it", because it points at the gap rather than the symptom. Write that
answer down while it is fresh.

## Common pitfalls

1. **Ignoring WARN before ERROR** — the root cause often appears as a WARN before the ERROR
2. **Filtering by timestamp alone** — timestamp-based filtering misses concurrent operations; scope the window with the AppTracer event timestamp and the local log's own sequence
3. **Not checking profile isolation** — many "data loss" reports are actually profile isolation working correctly
4. **Reproducing in DEBUG vs release** — some issues only appear in release (ProGuard, stripped logs)

## Prerequisites

- Log access (ADB for Android, log file for Desktop)
- AppTracer access for Android crash and non-fatal reports (not required for Desktop)
- Profile ID of the affected user
- Version number of the affected release

---

# Debugging a failing test

The section above assumes a production incident. This one is for "the test is red
and I do not know why" — where there are no logs, because the failure is inside
the test JVM. The protocol below is ordered by cost: each step either resolves
the class of failure or rules it out, so a hang is diagnosed in ~3 steps instead
of by trial and error.

## Step 0 — classify: fail, hang, or flaky

- **Red with an assertion message** → go to step 1.
- **Gradle never returns; you kill it by timeout** → it is a HANG, go to step 4.
  A hang is NOT an `awaitTag` timeout (`awaitTag` fails after 5 s with a tag
  explainer) and NOT a retry loop (the harness reports attempts). A hang means
  the test JVM itself is stuck — get a thread dump before touching any code.
- **Passes alone, fails in the suite** → shared state; step 6.

## Step 1 — read the assertion, then the diagnostics bundle

Compose failures are precise once you have the node list: "found '2' nodes that
satisfy…" means an ambiguity, "could not find any node" means the selector or the
screen is wrong, a timeout means the value never arrived. Three different responses.

The desktop harness already bundles diagnostics on every failure into
`desktopApp/build/diagnostics/<TestClass>/attempt-N/`:
`screenshot.png` (last composed frame), `db-state.txt` (FakeAppDatabase dump),
`kermit.log`. Read those before reading source — a screenshot showing Settings
where the agenda should be answers in one glance what a source review takes an
hour to find.

## Step 2 — dump the tree; never guess a selector

Guessed selectors are the single largest source of wasted turns. Every desktop
flow honours `-Dsingularity.ui.dumpTree=true`; the tree lands in
`build/test-results/test/TEST-*.xml` between `=== SEMANTICS TREE ===` markers.
`-Dsingularity.test.log=true` routes Kermit to stdout at verbose severity, where
repository and ViewModel tracing lives. Anything you "know" is on screen from
reading source is a guess.

## Step 3 — bisect across the layer boundary, headlessly

When the UI disagrees with the data, FIRST split "did not happen" from "did not
render" — in a plain test, without Compose:

```kotlin
// Same graph the flow harness builds: domainModule() + testPlatformModule()
val app = koinApplication { modules(coreLoggingModule(), *domainModule().toTypedArray(), testPlatformModule()) }
runTest {
    withContext(Dispatchers.Default) {          // REAL time, not the test scheduler's
        seed(koin)
        val vm = TheCoordinator(deps = koin.get(), id = …)  // same wiring as the DI factory
        withTimeout(10.seconds) { vm.state.first { it is Loaded } }  // or dump every emission
    }
}
```

"The VM never leaves Loading in this graph" and "the VM is fine, the screen is
wrong" are different investigations, and this one probe decides between them.
It also survives when the UI path hangs — the failure class that resists every
UI-level tool.

## Step 4 — hangs: thread dump, then freeze the clock

While the run is stuck, `jstack <worker-pid>` the Gradle test worker. The two
signatures that covered every hang seen so far:

- **Test thread WAITING in `EventQueue.invokeAndWait` ← `captureToImage` /
  `waitForIdle`; `AWT-EventQueue-0` RUNNABLE at ~100% CPU in
  `RenderNode_nDrawInto`** — a never-idle composition: something invalidates
  every frame (an indeterminate spinner on an unresolved state, a Koin error
  caught inside composition and retried per frame, an infinite animation).
  Fix the root cause; to unblock diagnostics, freeze the frame clock —
  `mainClock.autoAdvance = false` stops scheduling frame-clock callbacks, so
  "wait for idle" returns immediately. Compose test code can do this in a
  probe; the failure bundle now does it automatically before screenshots.
- **Test thread and EDT each WAITING on each other's lock** — a genuine
  deadlock; the dump shows both stacks, read them together.

Two rules that cost real time to learn:

- **`withTimeout` cannot cancel a blocking call.** If the test thread sits in
  `EventQueue.invokeAndWait` (not a suspension point), a coroutine timeout never
  fires. Skip the operation (e.g. the screenshot) instead of bounding it.
- **`withTimeout` inside `runTest` runs on the VIRTUAL clock** and can expire
  instantly while real workers are still moving. Wrap real-time waits in
  `withContext(Dispatchers.Default)`.

## Step 5 — silent coroutine death: coroutines.txt

Symptom: a VM/slot stays on its initial state, emits NO events, throws nothing
observable, and the headless probe (step 3) reproduces it. A coroutine that dies
BEFORE its first emission leaves no trace in state or events.

The `kotlinx-coroutines-debug` Java agent is attached automatically to all
`desktopApp:test` and `shared:jvmTest` JVM forks. On failure, the harness writes
`build/diagnostics/<TestClass>/coroutines.txt` as part of the FailureBundle — look
there first. The dump shows every active coroutine's state, context, job hierarchy,
creation stack trace, and last observed stack trace. Read it before adding manual
`DebugProbes` calls.

Common patterns in the dump:
- `state=SUSPENDED` + `lastObservedStackTrace` pointing at a blocking call —
  the coroutine is waiting; check if the wait is bounded.
- `state=RUNNING` on a `DefaultDispatcher` thread at a `delay()` call —
  normal during a `runTest` auto-advance.
- A coroutine with no `lastObservedStackTrace` died before it last suspended;
  the `creationStackTrace` shows where it was created and
  `Exception in thread …` from stdout is the killing throw.

Manual `DebugProbes` invocation is no longer needed for diagnostics — the agent
captures everything automatically. It remains useful for REPL-style exploration
in a live test session.

Concrete example of silent death: an `init` block launched a `combine` over a
property declared AFTER it; Kotlin initialises properties in declaration order, the
init block saw null, the coroutine died with `NullPointerException: parameter f8
is null`. Rule: **a property read from `init` must be declared before it** (see
`singularity-todo-testable-vm`).

## Step 6 — shared state and the remaining traps

- **Passes alone, fails in the suite** — process-global mutation before selector
  review; find what mutates it (`user.home`, statics, Kermit writers).
- **`-D` on the Gradle CLI configures the daemon, not the test JVM.** Opt-in
  switches are forwarded by `desktopApp/build.gradle.kts` via
  `providers.systemProperty` — provider reads are configuration-cache inputs, so
  a plain `System.getProperty` snapshot there would silently freeze the flags
  at first-configuration values. If a forwarded flag "does nothing", suspect a
  stale configuration-cache entry before the flag itself.
- **Koin duplicate definitions resolve last-wins.** If a fake seems ignored, the
  production module was probably loaded after it.
- **"Repository is empty" can mean the write was scoped to a different user.**
  `ProfileAwareCurrentUser.scopedUserId` is not stable at startup — compare write
  time and read time before concluding anything.
- **A disabled button makes `performClick` a silent no-op.** Assert the enabled
  state before blaming the handler.
- **A `try`/`finally` with no `catch` swallows exceptions.** A control the user
  pressed that quietly does nothing usually means a throw escaped into the
  coroutine scope.
- **`kotlin.test.assertTrue` takes a String message, not a lambda.** The lambda
  form compiles away as an overload mismatch and costs a build cycle.
- **A Koin error thrown inside composition is retried EVERY FRAME** — it presents
  as a hang or an endless-redraw CPU burn, not as a stack trace in the report.
  If the app "boots into the wrong screen", dump the tree before blaming
  navigation.
