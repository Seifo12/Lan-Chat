# Troubleshooting build failures

Failures that are not about the code, and what to do about them. Every entry here
cost real time to diagnose, so check here before assuming a change broke something.

## `OutOfMemoryError: Java heap space` in `:app:testDebugUnitTest`

**The message is misleading.** It is almost always the Gradle *daemon*, not the
test worker, and the cause is stale results rather than a test that allocates too
much.

The stack looks like this:

```
at org.gradle.api.internal.tasks.testing.results.serializable.SerializableTestResult$Serializer.deserializeFailure
at org.gradle.api.internal.tasks.testing.Test.getPreviousFailedTestClasses(Test.java)
```

Gradle serialises every test result to disk. On the next run it reads those
results back so it can report which classes failed previously. After many failed
runs, especially under Robolectric where each failure carries a large stack trace,
that accumulated history is big enough to exhaust the daemon heap before a single
test executes.

The run then fails in seconds, which is the tell: a genuine memory problem in a
test takes minutes to develop.

Fix:

```bash
# Windows PowerShell
Remove-Item -Recurse -Force app\build\test-results, app\build\reports\tests
```

Both are regenerable build output. Do not raise the test worker's heap to fix
this; that was tried and it changed nothing, because the failing JVM is the
daemon.

## The disk fills up

`C:` running out of space will make Gradle fail in ways that look like code
problems, including the heap error above.

```bash
# what is taking the space
Get-ChildItem -Recurse app\build -File | Measure-Object Length -Sum
Get-ChildItem "$env:TEMP" -Directory | ForEach-Object {
  $s = (Get-ChildItem -Recurse $_.FullName -File -ErrorAction SilentlyContinue |
        Measure-Object Length -Sum).Sum
  [PSCustomObject]@{ Name = $_.Name; MB = [math]::Round($s/1MB) }
} | Sort-Object MB -Descending | Select-Object -First 10
```

Screenshot and rendering tests leave behind browser profile directories named
`HeadlessEdge*` in `%TEMP%`, one per run. They are abandoned once the run ends and
are safe to delete. `gradle-home/caches` is also large but deleting it forces a
re-download, so clear it last.

Keep at least a few GB free before starting a full verification run.