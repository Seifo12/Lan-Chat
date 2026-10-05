# Working agreements

Instructions for anyone working on LAN Chat, human or AI agent.

## Commit and push after every completed change

When you finish a change or a bug fix, commit it and push it. Do not batch a
day of work into one commit, and do not leave finished work uncommitted.

```bash
git add <files>
git commit      # message must explain the change and why, not just list files
git push
```

`main` tracks `origin/main`, so a plain `git push` is enough. The point is that
other developers and agents can always see the latest working code.

Rules that matter:

- **One coherent change per commit.** Do not mix a refactor with a bug fix.
- **Explain the why, not the what.** The diff already shows what changed.
- **Report the real verification output**, not a claim that it passed. If a check
  failed or was skipped, say so.
- **Never commit secrets.** Signing keys, `local.properties`, and credentials
  stay out of the repository. See `.gitignore`.

## Before claiming something works

Run it and paste the output:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
powershell -File scripts/verify.ps1
```

## Documentation

- `docs/` and all Markdown are **English**. This includes code comments in
  `.kt` files.
- **Arabic belongs only in** `app/src/main/res/values-ar/strings.xml`. Never
  hardcode a user-facing string in Kotlin; use `stringResource`.
- The repository must contain no CJK characters and no `U+FFFD`. Pasted text
  has mixed scripts before, so `scripts/verify.ps1` fails the build on this.

## Claims need evidence

Do not describe a library, an API, a store policy, or a security property as
working unless you have read it in this codebase or in an official source.
Security claims in `README.md` in particular are load-bearing: people may rely on
them in an emergency. If something is planned rather than implemented, say so
explicitly, and point at the phase that will implement it.

## Room database

`MessageStatus.status` is a `TEXT` column with no `TypeConverter`, so adding an
enum constant does **not** change the schema. The schema version changes only
when a column, index, or constraint changes. `MessageStatusSchemaTest` exists to
prove that, so run it before assuming a migration is needed.

## Current state

Security work is **not finished**. The silent encryption fallbacks are removed in
Phase 1.2 and transport pinning in 1.3. See the security section of `README.md`
for the authoritative list of gaps, and do not let a change quietly contradict it.