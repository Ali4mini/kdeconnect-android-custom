# Phone music queue protocol

The [desktop fork](https://github.com/Ali4mini/kdeconnect-kde-custom) sends
`kdeconnect.mpris.request` with the media session's `player` name and a unique
string `queueRequestId`. The Android fork replies on `kdeconnect.mpris`,
echoing those fields and returning a JSON-encoded string in `queueReply`.

| Operation | Additional request fields | Decoded reply |
| --- | --- | --- |
| Read queue | None | `token`, `available`, `canSelect`, `activeId`, and ordered `entries` with `id`, `title`, and `artist` |
| Select queue item | `queueToken` from the read and `queueItemId` from its entries | `accepted` boolean |

IDs are decimal strings to preserve Android's signed 64-bit queue IDs. Treat
the token as opaque. Android validates it and the item against the current
snapshot before dispatching playback. `activeId` can be null. A successful
dispatch does not confirm that the player started playback. Android exports
up to 1,000 entries. Existing media commands keep their current packet fields.
The desktop launcher should time out if the peer cannot reply.

When changing this contract, inspect Android's `mprisreceiver` plugin and the
desktop fork's `plugins/mprisremote` and `scripts/kdeconnect-rofi-queue`. Verify
ordinary media controls still work and test selection, cancel, duplicate
titles, and a changed queue with a paired phone and Arch PC. Record the
tested revision on each side.
