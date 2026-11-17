# Fork changes and limitations

## Baseline

The fork started from KDE's upstream `master` at
`b5a1c1c72beb963b010598222e9ca01523068532` (version 1.37.3). The setup keeps
KDE Connect's existing Android behavior and desktop protocol.

## Change log

| Date | Change | Verification |
| --- | --- | --- |
| 2026-11-17 | Set up the development baseline and documented the fork workflow. | See the baseline results below. |

For each user-visible or compatibility change, record the affected behavior,
the issue or PR when available, and the build or device checks performed.

## Open decisions and limitations

- **App identity and branding.** The inherited release application ID is
  `org.kde.kdeconnect_tp`. Debug builds use `org.kde.kdeconnect_tp.debug`,
  which can coexist with the official release and shares its identity with
  upstream debug builds. Choose the fork's package ID, display name, and artwork
  before distributing it. A separate package ID starts with its own Android app
  data and pairing state.
- **Release signing and distribution.** The checked-in debug keystore is for
  development. Decide on signing key ownership and a distribution channel before
  preparing release APKs. Keep upstream license files and notices and follow
  the source-distribution requirements for the chosen release.
- **Compatibility evidence.** Desktop pairing and plugin behavior need the
  device checks described in [development setup](development.md). Track
  Android-version and permission limitations when reproduced.

## Baseline results

- `assembleDebug` produced `kdeconnect-android-debug.apk` (1.37.3-debug).
- `testDebugUnitTest` passed all 140 tests.
- `lintDebug` completed with 450 errors and 193 warnings in the baseline report.
  Upstream config makes lint findings non-blocking. See `build/reports/lint-results-debug.html` for
  findings. The upstream build also emits translation and deprecation warnings.
- No Android device was connected. Installation, discovery, pairing, and
  plugin smoke checks are pending.
