# Android 12 continuation checklist

## Current state

The owner confirmed projected TV Settings navigation, then the lightweight
local Rayo Home and Settings. The selected design is a simplified TV interface
without Google services, now named GabOS. Offline identity is `GabOS 0.1-dev`;
the launcher is GabOS Home 0.4. See [the OS/application plan](GABOS.md).
It is not a stable replacement ROM yet. Keep normal
v14 and the preserved factory system until the acceptance checks pass.

The latest run is `20261111-093343-b66a3207` (radio-05). Wi-Fi automatically
connected with validated internet and reconnected after an off/on cycle while
Bluetooth stayed ON. Bluetooth had zero crashes and a fresh completed discovery
found five devices. Five Home/Settings/Back cycles preserved core process IDs.
Normal v14 returned and factory recovery was restored and hash verified.
The earlier interrupted run was repaired separately; its historical failed RESULT
was not rewritten. See [radio bring-up](GABOS_RADIO_BRINGUP.md).

## Next owner-assisted session

Identify Android version, completed boot and boot/recovery hashes before testing.
Keep the tested v14 boot and factory recovery. Check projected colors and input,
Bluetooth accessory pairing/reconnection and audio routing, speaker/video decoding,
actual power cycles, suspend, thermals and extended stability. Scanning does not
prove Bluetooth audio: the current primary-only audio policy still needs routing
work. Graphics counters still show substantial jank; no smoothness claim is made.
Physical MaskROM entry remains untested. Development SELinux/permissive and root
service exposure require review before a release. Do not promote or remove v14.

## Offline preparation

- Local UI source: `work/rayo-tv`; build with `tools/build-lightweight-tv.ps1`.
  Development signing credentials remain private under `local/secrets`.
- Prepared candidate: `candidate-t320pzw3` under the WSL framework-candidates
  directory. It includes local Home and panel/remote keymaps. It is untested.
- Newer branded candidate: `candidate-874zpwfy`, with GabOS identity and Home
  0.4; Android 12/API 31 are unchanged. Its properties and signed APK metadata
  were checked offline; it has not been installed or validated on the device.
- `prepare-android12-framework.py --local-launcher` reproduces that integration
  with a checksum-checked APK; no Google components are added.
- `prepare-android12-radio-assets.py` verifies the v14 archive and stages only
  private firmware/Bluetooth libraries, with hashes and ELF dependencies.
- `build-android12-radios.sh` uses a separate `rayos1_tv12_radios` product and
  `~/android/rayo12-radios-out`, preserving the passing TV output. It compiles
  Broadcom NL80211 Wi-Fi/supplicant and Bluetooth HIDL services. It does not
  prepare an installable image or establish hardware operation.
- Bluetooth's original C++ dependencies require an isolated legacy namespace.
  The opt-in loader adaptation and source-built null-timer compatibility helper
  compiled successfully. Six source ARM32 artifacts passed the offline audit;
  private firmware/vendor assets also passed archive/checksum/ELF inventory.
  Final init, permissions, VINTF and legacy dependency integration is pending.
- Source radio services depending on BoringSSL need the same explicit old-kernel
  entropy/backend review as Keystore. Do not silently substitute entropy or
  label the non-FIPS diagnostic backend as platform FIPS support.
- The archived hardware media implementation includes Rockchip OMX/VPU
  libraries and codec XML. These still need a separately checked Android 12
  service/namespace/allocator integration; software playback is not proof of
  hardware decoding or adequate performance.

## Acceptance before promotion

| Area | Required device evidence |
|---|---|
| Recovery | Normal v14, factory recovery readback and a tested rollback path |
| Input/Home | Every panel/mando button, pointer response, app launch, Back/Home return |
| Graphics | Repeated app/settings transitions, solid colors/alpha, no composer crashes, correct orientation |
| Wi-Fi | Enable, scan, connect, validated internet, reboot reconnect and suspend/resume |
| Bluetooth | Enable, scan, pair, reconnect; test actual HID/audio accessory profiles |
| Media | Speaker sound and real AVC/AAC playback, identify decoder, seek and prolonged playback |
| Memory/power | Sustained use on 1 GB RAM, no restart loops, temperature, battery reporting and suspend |
| Persistence | Repeated cold boots with the same working configuration and no manual repairs |

Keep a tested rollback image and private original backups after any future
promotion. The owner's request to remove the older active installation is
conditional on Android 12 becoming stable; it is not authorization to erase
the preserved factory partitions or the only recovery copies now.
