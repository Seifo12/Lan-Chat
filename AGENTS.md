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

## Security rules

These are not style preferences. Violating them is a bug.

- **No silent fallbacks.** If a security step fails, fail the operation and say
  why. Never substitute a weaker mechanism and continue. Specifically forbidden:
  `encryptForPeer(...) ?: encryptWithSomethingElse()`, a keystore error that
  downgrades to software crypto, and regenerating a device identity because its
  stored key failed to load. An identity that fails to load must surface an error,
  not silently become a new key.
- **No trust-all TrustManager.** A custom `X509TrustManager` must never accept
  every certificate. Unknown peers are rejected; a pinned peer's key changing is a
  hard failure, never a prompt to trust whatever arrived.
- **Tests first for security fixes.** Write the failing test that demonstrates the
  weakness before changing the code. A security fix without a test that fails
  without it is not finished, because nothing proves the weakness is gone.
- **Security design choices need approval.** Do not choose an algorithm, key
  size, curve, pinning policy, or protocol version unilaterally. Propose the
  option with its source and trade-off, then wait. Sizes and primitives are
  load-bearing and expensive to change later.
- **State any `--no-verify`.** If a commit or push needed `--no-verify`,
  `--force`, or a hook bypass, say so explicitly in the report, with the reason
  and what the skipped check would have covered.

## Claims need evidence

Do not describe a library, an API, a store policy, or a security property as
working unless you have read it in this codebase or in an official source.
Security claims in `README.md` in particular are load-bearing: people may rely on
them in an emergency. If something is planned rather than implemented, say so
explicitly, and point at the phase that will implement it.

## Room database

`MessageStatus.status` is a `TEXT` column with no `TypeConverter`, so adding an
enum constant does **not** change the schema. The schema version changes only
when a column, index, or constraint changes. `MessageStatusSchemaTest` proves the
messages table is byte-identical between the exported `9.json` and `10.json`, so
run it before assuming a migration is needed, and before assuming one is not.

The version lives in `ChatDatabase.SCHEMA_VERSION`. Reference that constant
rather than writing the number again. When a migration is added, append it to
every test that hands Room an explicit migration list, and to
`ChatDatabase.addMigrations`.

## Current state

Security work is **not finished**. Silent encryption fallbacks are removed in
Phase 1.2 and transport trust in 1.10, and neither is done. The identity key is a
serialised software EC key, not a keystore-held key. Replay protection is bounded
and does not cover voice, and message packets still carry no counter, so the
sliding high-water mark from the design is not implemented. See the security
section of `README.md` for the authoritative list of gaps, and do not let a
change quietly contradict it.

- **One coherent change per commit.** Phase 1.3 needed three commits to stay
  reviewable: the signing surface, then the counters, then the wire format and
  enforcement. Do not squash a phase into one commit if it hides a broken
  intermediate state.
- **If the send and receive halves of a protocol disagree, nothing throws.** During
  1.3 the receive path was switched to demand a signature over the whole field set
  while the send path still signed the text alone. The app compiled, every test
  that did not exercise both halves passed, and every message was refused at
  runtime. `SignedMessageRoundTripTest` exists so that cannot happen unnoticed
  again; keep it green.

Phase scope and order live in `docs/PHASE1-DESIGN.md`. Do not restate or
renumber the phases here; reference that document instead.