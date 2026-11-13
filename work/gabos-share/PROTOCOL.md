# GabOS Share protocol (version 1)

GabOS Share lets a phone, tablet or computer send photos and videos to a GabOS
projector. The projector runs the receiver (`local.gabos.share`, this folder);
Android phones use the sender app (`local.gabos.share.sender`,
`work/gabos-share-sender`); any other device can use the web page the receiver
serves at `/`.

## Transport and discovery

- Everything travels over the local network: plain HTTP/1.1 on TCP port
  **8765**, one request per connection (`Connection: close`).
- The receiver advertises itself with DNS-SD/mDNS (Android `NsdManager`) as
  service type **`_gabosshare._tcp`**. The service name is the projector's name
  (`Settings.Global` `device_name`, or "GabOS projector" / "Proyector GabOS").
- Both devices must be on the **same Wi-Fi network** (or the same LAN). Guest
  networks with client isolation block both discovery and transfers; in that
  case nothing can be sent. The sender also accepts a typed address
  (`192.168.1.20` or `192.168.1.20:8765`), which the projector shows in the
  GabOS Share app together with `http://<address>:8765` for browsers.
- **Bluetooth is not used.** Classic Bluetooth tops out at about 2-3 Mbit/s
  (EDR) in theory and less in practice, far too slow for videos, and pairing
  adds steps. Wi-Fi
  discovery already finds a projector that is "nearby" in the sense that
  matters (same home network) without pairing.

## Security model

- Only peers with loopback, link-local, private IPv4 (RFC 1918) or unique
  local IPv6 (`fc00::/7`) addresses are served; others get `403`.
- Against DNS rebinding and cross-site requests from web pages opened on the
  LAN, the `Host` header must be an IP literal, `localhost` or a `.local`
  name, and an `Origin` header, when present, must equal `http://<Host>`.
  Anything else gets `403`.
- Nothing is accepted without approval on the projector: every offer shows a
  prompt (Accept focused, Decline, Back = Decline) that times out after 60 s.
- One transfer at a time. While a transfer is waiting, uploading or being
  shown, new offers get `409 {"accepted":false,"reason":"busy"}`.
- Only `image/*` and `video/*` items, at most 200 per offer and 2 GiB per file.
  The offer is refused with `507` when the files would leave less than
  400 MiB free on the projector's data partition.
- Upload bodies must have an exact `Content-Length` equal to the offered size;
  chunked transfer encoding is refused with `411`.
- File names are reduced to a plain name (no paths or control characters,
  at most 100 characters).
- Transfers that stop making progress for 10 minutes are dropped and their
  files deleted.

## Endpoints

All JSON is UTF-8. Errors use `{"error": "<English message>"}`.

### `GET /info`

```json
{"name": "Living room", "version": "1.0", "api": 1, "busy": false}
```

### `POST /offer`

Request body (`Content-Length` required, at most 256 KiB):

```json
{
  "sender": "Pixel 8",
  "mode": "show",
  "items": [
    {"name": "IMG_0001.jpg", "mime": "image/jpeg", "size": 2483921},
    {"name": "VID_0002.mp4", "mime": "video/mp4", "size": 48211933}
  ]
}
```

`mode` is `"show"` (display now, keep nothing) or `"save"` (store on the
projector). The request stays open until the projector user answers or 60 s
pass, so clients should use a read timeout of at least 75 s.

Response `200`:

```json
{"id": "6f1c0e...", "accepted": true}
{"id": "6f1c0e...", "accepted": false, "reason": "declined"}
```

`reason` is `declined`, `timeout` or `cancelled`. Refusals before the prompt:
`409 {"accepted":false,"reason":"busy"}`, `507 {"accepted":false,"reason":"no space"}`,
`400`/`413`/`415` with `error` for invalid offers.

### `POST /upload?id=<id>&index=<n>`

The body is the raw bytes of item `n` (0-based), with `Content-Length` equal
to the offered `size`. Items are sent one at a time, in any order. Response:

```json
{"ok": true, "received": 2, "total": 2, "complete": true}
```

Errors: `404` unknown or closed transfer, `409` item already received or
another upload in progress, `400` wrong length or index, `500` the file could
not be saved.

When the last item arrives:

- **Show**: the projector opens a full-screen viewer. Photos are decoded to at
  most 1280x720 (EXIF rotation applied) and fitted to the screen; videos play
  with the platform decoders. Left/right (D-pad, on-screen arrows or wheel)
  move between items, OK pauses a video, Back or the close button ends the
  show. Leaving the viewer deletes the received files.
- **Save**: each file is stored as soon as it arrives, in `Pictures/GabOS`
  (photos) or `Movies/GabOS` (videos), through MediaStore, and the cached copy
  is deleted. A short confirmation appears on the projector when all are saved.

### `POST /cancel?id=<id>`

Drops a transfer that is waiting or uploading and deletes its files. Returns
`{"ok": true}`. A Show transfer that is already on screen is closed by the
projector user instead.

### `GET /`

A small bilingual (English/Spanish, from `Accept-Language`) web page that
performs the same offer/upload sequence from a browser, for iPhones, laptops
and other devices without the Android sender.

## Sequence

```
phone                          projector
  | -- mDNS browse _gabosshare._tcp -->|
  | <------- name, address, 8765 ------|
  | -- POST /offer -------------------->|  prompt: "Pixel 8 wants to show 2 photos"
  | <------ {id, accepted:true} -------|  (user pressed Accept)
  | -- POST /upload?id&index=0 -------->|
  | <------ {received:1, total:2} -----|
  | -- POST /upload?id&index=1 -------->|
  | <------ {complete:true} -----------|  viewer opens (Show) / saved (Save)
```
