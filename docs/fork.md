# Fork changes and limitations

## Baseline

The fork started from KDE's upstream `master` at
`b5a1c1c72beb963b010598222e9ca01523068532` (version 1.37.3). The setup keeps
KDE Connect's existing Android behavior and desktop protocol.

## Change log

| Date | Change | Verification |
| --- | --- | --- |
| 2026-11-17 | Set up the development baseline and documented the fork workflow. | See the baseline results below. |
| 2026-11-17 | Added Android queue export and selection, Linux companion support, and a Rofi picker. | Debug build and 140 unit tests passed. Mocked picker selection and cancellation passed. Live Arch and phone testing is pending. |

For each user-visible or compatibility change, record the affected behavior,
the issue or PR when available, and the build or device checks performed.

## First feature — phone music queue in Rofi

On Arch Linux, launch a Rofi picker for the paired phone's active music player.
Show the queue in playback order with title and artist where available. Mark
the active item when the player reports it. Selecting a row requests playback
of that queue entry on the phone.

- Android reads queue entries and selection capability from the active
  `MediaSession` and passes them through the paired KDE Connect connection.
  Keep entry IDs distinct even when the same song appears twice. Validate
  selections against the current session and queue.
- The Linux KDE Connect MPRIS proxy needs a queue extension. It currently
  exports basic player controls without the MPRIS `TrackList` interface.
  The [desktop fork](https://github.com/Ali4mini/kdeconnect-kde-custom) adds
  queue requests and selection to the per-device D-Bus interface and owns
  the Rofi launcher and Arch setup guide. See the [queue protocol](music-queue-protocol.md).
- The launcher displays an explicit unavailable state when the music app
  provides no queue and a read-only queue when the app supports listing only.
  Canceling the picker sends no playback command.

Verify on Arch Linux with the intended Android music player. Check duplicate
track titles, a queue change while the picker is open, cancellation, and
selecting a track while playback is paused. The phone player determines which
queue entries it exposes and how it responds to selection. The picker lists
the first 1,000 entries and rejects selections if that part of the queue
changes while the menu is open.

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
