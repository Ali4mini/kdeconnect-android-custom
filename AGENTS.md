# Android fork coordination

The companion desktop fork is [Ali4mini/kdeconnect-kde-custom](https://github.com/Ali4mini/kdeconnect-kde-custom)
(`/home/dev/kdeconnect-kde-custom` on the shared host). It owns the Linux
MPRIS plugin, D-Bus interface, Rofi launcher, and Arch setup guide.

For phone queue export or track selection, read the [queue protocol](docs/music-queue-protocol.md)
and inspect the desktop fork's `plugins/mprisremote` counterpart. Carry wire
changes through both forks or preserve mixed-version compatibility. Cross-link
dependent changes and record the Android and desktop revisions used for any
live pairing test. Record Android changes and limitations in [the fork log](docs/fork.md).

## Fork commits

After every commit, push the current feature branch to this fork's `origin`.
Set the upstream to that feature branch on its first push.
