# Phone music queue on Arch Linux

The queue picker needs the Android fork, a patched Linux KDE Connect build,
Python 3, Rofi, and systemd's `busctl`. Pair the phone as usual and enable
Multimedia Control Receiver on Android with notification access. Enable the
corresponding remote media control plugin on the PC.

## Build the companion plugin

Apply [the patch](kdeconnect-music-queue.patch) to the KDE Connect desktop
source. It was authored against KDE's `kdeconnect-kde` commit
`ab3e5bb9041757b0df0b2c6302ed63e1dede24b4`. For an Arch package build, add
the patch to the packaging sources and apply it in `prepare()` against the
package's KDE Connect source tree. Verify the diff applies before building.

```sh
git -C /path/to/kdeconnect-kde apply --check /path/to/kdeconnect-music-queue.patch
git -C /path/to/kdeconnect-kde apply /path/to/kdeconnect-music-queue.patch
```

Build and install KDE Connect using your Arch packaging workflow, then restart
the desktop KDE Connect daemon so it loads the patched plugin.

## Launch the picker

Start playback on the phone. Get its ID using `kdeconnect-cli --list-available`,
then bind this command to your desktop shortcut or run it in a terminal:

```sh
/path/to/kdeconnect-rofi-queue DEVICE_ID
```

When multiple media sessions are active, Rofi asks which player to use. Pass
`--player 'Player name'` to skip that step. Press Enter to select a track and
Escape to cancel. The extension uses the existing paired KDE Connect connection
and the desktop session bus.

## Player compatibility

Try any player that publishes its playback queue through Android's
`MediaSession`. If it also advertises skip-to-queue-item, tracks are selectable.
A player that publishes a queue without that action appears read-only. If the
player publishes no queue, the launcher reports that explicitly. An accepted
selection means Android dispatched the command. Actual playback depends on
the music app.
