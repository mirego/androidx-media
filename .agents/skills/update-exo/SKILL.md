---
name: update-exo
description: Migrate the mirego androidx-media fork onto a new upstream release. Given a version (e.g. "1.10.1"), creates mirego-main-<version> from the upstream tag, replays mirego's fixes from the previous mirego branch via cherry-pick, resolves conflicts, stamps the version, folds compile fixes into their owning commits, verifies flagged items, and reports. Never pushes.
argument-hint: <upstream-version>  (e.g. 1.10.1)
---

# update-exo

Migrate the mirego fork onto a new upstream `androidx/media` release.

The fork's pattern: each `mirego-main-<tag>` branch is upstream tag `<tag>` plus mirego's
stack of fixes replayed on top. To move to a new upstream version you branch from the new
tag and replay the previous branch's mirego commits onto it.

**Input:** the target upstream version, e.g. `/update-exo 1.10.1`. Call it `NEW`.

## Golden rules

- **Never `git push`. Never change the GitHub default branch.** Stop and hand off to the human.
- **Never skip or disable a pre-commit hook.**
- The branch-name suffix **is** the upstream base tag: `mirego-main-1.9.4` is tag `1.9.4` + fixes.
- `origin` = mirego fork (`github.com/mirego/androidx-media`), `upstream` = `androidx/media`.
- Interactive rebase (`git rebase -i` with a real editor) is not available. Drive autosquash
  non-interactively with `GIT_SEQUENCE_EDITOR=true GIT_EDITOR=true`.
- Match surrounding code style. Mirego edits are marked with `// MIREGO` comments — preserve that.

## Step 1 — Preflight

1. Require a clean tree. Run `git status --porcelain`; if non-empty, **abort** and tell the dev
   to commit or stash first.
2. `git fetch upstream --tags` and `git fetch origin`.
3. Verify the upstream tag exists: `git rev-parse --verify refs/tags/<NEW>`. If missing, abort
   (the release may not be tagged yet).

## Step 2 — Resolve inputs (auto-detect source)

1. List candidates: `git branch -r --list 'origin/mirego-main-*'`.
2. Pick `PREV` = the highest **semver** suffix (compare numerically, not lexically — `1.10.x`
   sorts above `1.9.x`). `PREV_TAG` = that branch's suffix.
3. Cherry-pick range = `PREV_TAG..origin/mirego-main-<PREV_TAG>`.
4. Build the ordered, filtered commit list:
   ```
   git log --reverse --no-merges --format='%H%x09%s' \
     PREV_TAG..origin/mirego-main-<PREV_TAG> | grep -viP '\tIncrement version to'
   ```
   Keep this list; it drives Step 5.

## Step 3 — Compute the version stamp

From `NEW = X.Y.Z`:
- `releaseVersion` string = `"<X.Y.Z>.0001"` (mirego build number starts at `0001`).
- Integer code = `1_0XX_0ZZ_3_00` where `XX` = zero-padded minor `Y`, `ZZ` = zero-padded patch `Z`,
  `3` = stable cycle, `00` = cycle number. Format with underscores, e.g. `1.10.1` → `1_010_001_3_00`;
  `1.9.4` → `1_009_004_3_00`. (Major is assumed single-digit `1`, matching all releases to date;
  if major ever exceeds 1, stop and confirm the encoding with the human.)

## Step 4 — Confirm the plan

Print and **wait for the dev to say go** before creating any commit:
- New branch: `mirego-main-<NEW>` from tag `<NEW>`
- Source: `origin/mirego-main-<PREV_TAG>`, range `<PREV_TAG>..`
- Commit count to replay (from Step 2)
- Version stamp: `releaseVersion`, integer code

## Step 5 — Create branch and replay

1. `git switch -c mirego-main-<NEW> <NEW>`
2. Cherry-pick each commit from the Step 2 list **in order**. Prefer a driver so you can react
   per commit (e.g. `git cherry-pick <sha>`; on failure, resolve; then continue).
3. **Empty cherry-picks** (change already in upstream, e.g. a fix upstream later adopted): when a
   pick reports nothing to commit / becomes empty, run `git cherry-pick --skip` and record it under
   *skipped (already upstream)*.
4. **Conflict resolution heuristics** (auto-resolve; this is the judgment core):
   - **Take HEAD (upstream) as the base** when upstream refactored the surrounding code. Re-inject
     only the mirego intent that still applies.
   - **Logs:** re-inject a mirego `Log.v(...)`/`Log.e(...)` only if every variable it references
     still exists post-refactor. If a referenced field/method was removed upstream, **drop the log**
     and record it as a **casualty**.
   - **Stale reimplementations:** if the mirego commit reimplements a method upstream has since
     rewritten (renamed fields, changed signatures, moved logic), drop the mirego version and keep
     HEAD's, porting only the still-relevant behavioral delta.
   - **Orphaned fields/imports:** after dropping code, remove any now-unused fields/imports it left.
   - **Imports:** if HEAD uses `com.google.common.base.Preconditions` (checkNotNull/checkState) and
     the mirego hunk re-adds `androidx.media3.common.util.Assertions`, drop the duplicate Assertions import.
   - **Audio pipeline** is the highest-drift area historically (upstream replaced direct
     `audioTrack` with an `audioOutput`/`AudioOutputProvider` abstraction; `writeNonBlocking` →
     `audioOutput.write` + `WriteException`; `hasPendingAudioTrackReleases` →
     `hasPendingAudioOutputReleases`; `audioTrackPositionUs` → `audioOutputPositionUs`;
     `isFormatFunctionallySupported(format)` → `(context, format)`). Expect these; take HEAD and
     re-thread mirego intent through the new API.
5. **Flag, don't silently decide:** every time you make a *behavioral* call (a feature/log dropped,
   a partial port, a workaround whose call-site or API changed), record it as a **flagged item** with
   file + commit + what you did + why. Stop for the human **only** when a semantic port is genuinely
   ambiguous and you cannot justify a default.

## Step 6 — Version stamp

Edit and commit (subject: `Increment version to <NEW>.0001`):
- `constants.gradle`: `releaseVersion = '<NEW>.0001'` and `releaseVersionCode = <code>`.
- `libraries/common/src/main/java/androidx/media3/common/MediaLibraryInfo.java`:
  `VERSION = "<NEW>.0001"`, `VERSION_SLASHY = "AndroidXMedia3/<NEW>.0001"`, `VERSION_INT = <code>`.

## Step 7 — Build the tip

Compile `lib-exoplayer` plus every module whose files were touched by the replay
(derive from `git diff --name-only <NEW>..HEAD` → module roots under `libraries/`):

```
./gradlew :lib-exoplayer:compileReleaseJavaWithJavac [ :other-module:compile... ]
```

Requires network (mirego gradle-init plugin). Do **not** use `--offline`.

## Step 8 — Fold compile fixes into their owning commits

If Step 7 fails, do **not** leave a "fix compilation" commit on top. Fold each fix into the commit
that introduced the broken reference:

1. Fix each error in the working tree; confirm the tip builds.
2. `git branch backup-pre-fold-<NEW>` (safety ref, left for the dev).
3. For each fix, blame the broken line to find its owning commit:
   `git blame -L <line>,<line> HEAD -- <file>` → owning `<sha>`.
4. Reset the tip fix out (`git reset --hard HEAD^` if you committed a temp fix) or work from the
   working tree, then re-apply each hunk **separately** and commit as a fixup:
   `git add <file> && git commit --fixup=<owning-sha>` (one fixup per owning commit).
5. Autosquash non-interactively, base = parent of the earliest owning commit:
   `GIT_SEQUENCE_EDITOR=true GIT_EDITOR=true git rebase -i --autosquash <earliest-owning-sha>^`
6. Verify: no `fixup!` / "Fix compilation" commits remain; `git diff backup-pre-fold-<NEW> HEAD`
   is empty (pure history rewrite, identical tree); each fix now shows inside its owning commit
   (`git show <sha>`). Re-build the tip.

Apply the same backup-ref + fixup + autosquash flow for **any** later history rewrite (e.g. completing
a partially-ported fix — fold it into its owning commit, not on top).

## Step 9 — Verify flagged items

For each flagged item from Step 5, attempt verification and state findings:
- **Redundant with upstream?** `git merge-base --is-ancestor <upstream-fix-sha> <NEW>` → if yes, the
  mirego fix is already present; the cherry-pick residue is likely benign.
- **Call-site still exists?** `git grep -n '<method-or-field>'` — confirm the mirego code is actually
  reached (not dead). Confirm the enclosing method is the intended path (e.g. `seek()`).
- **Functionally wired?** Trace that the value the fix computes actually flows to where it matters
  (don't accept "field is set" — confirm it's *used*).
  Historical example: the tunneled audio-session fix must select the session id **at track-build
  time** (`getFormatConfig(...).setAudioSessionId(tunneling ? tunnelingAudioSessionId : audioSessionId)`),
  not merely track two ids.

## Step 10 — Report and stop

Do not push. Produce a concise report:
- Branch, base tag, commit count (`git rev-list --count <NEW>..HEAD`), version stamp.
- **Skipped** commits (already upstream) with reasons.
- **Casualties**: dropped logs/features and why.
- **Flagged items**: each with verification finding and whether it needs human/device validation.
- Backup refs left behind (for the dev to delete once satisfied).
- Explicit next steps for the human: review, run runtime test suite, then flip the default branch.
