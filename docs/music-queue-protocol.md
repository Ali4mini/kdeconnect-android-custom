# Phone music queue and library protocol

The [desktop fork](https://github.com/Ali4mini/kdeconnect-kde-custom) sends
`kdeconnect.mpris.request` and receives `kdeconnect.mpris` replies over the
paired KDE Connect connection.

## Playback queue (`queueRequestId`)

Send `player` and a unique string `queueRequestId`. Android echoes those fields
and returns a JSON-encoded string in `queueReply`.

| Operation | Additional request fields | Decoded reply |
| --- | --- | --- |
| Read queue | None | `token`, `available`, `canSelect`, `activeId`, and ordered `entries` with `id`, `title`, and `artist` |
| Select queue item | `queueToken` from the read and `queueItemId` from its entries | `accepted` boolean |

## Media library browsing (`libraryRequestId`)

Send a unique string `libraryRequestId` and `libraryAction`. Android echoes
`libraryRequestId` (and `player` when supplied) and returns a JSON-encoded
string in `libraryReply`. Browse and play requests bind to the player's
exported Android `MediaBrowserService` (`PackageManager` discovery), so they
work even when the player has no active `MediaSession` yet.

| `libraryAction` | Additional request fields | Decoded reply |
| --- | --- | --- |
| `players` | None | `players` array of `{name, packageName, active, canQueue, canBrowse}` |
| `browse` | `player`, optional `parentId` (omit or empty for root) | `available`, `player`, `rootId`, `parentId`, `searchSupported`, `truncated`, `totalCount`, `error`, and ordered `items` with `{id, title, subtitle, browsable, playable}` |
| `play` | `player`, `mediaId` | `accepted` boolean and `error` |

IDs are decimal strings to preserve Android's signed 64-bit queue IDs, and
`mediaId` strings are opaque IDs from `MediaBrowser.MediaItem`. Treat queue
`token` as opaque. Android validates queue tokens and item IDs against the
current snapshot before dispatching playback. `activeId` and `error` can be
null. A successful dispatch does not confirm that the player started playback.
Android exports up to 1,000 entries per queue read or browse request. Existing
media commands keep their current packet fields. The desktop launcher should
time out if the peer cannot reply.

When changing this contract, inspect Android's `mprisreceiver` plugin and the
desktop fork's `plugins/mprisremote` and `scripts/kdeconnect-rofi-queue`. Verify
ordinary media controls still work and test selection, library browsing, cold
player launch via `MediaBrowser`, cancel, duplicate titles, and a changed queue
with a paired phone and Arch PC. Record the tested revision on each side.
