# GabOS radio integration and device evidence

Device: the same Canon Rayo S1, preserved Linux 3.10.104 kernel, ARMv7/Binder32,
Android 12/API 31. All experiments use the recovery slot, an 800 MiB read-only
system image and the existing private 256 MiB data image. No promotion or
factory system/bootloader replacement is part of these tests.

## Integration

`prepare-android12-framework.py --local-launcher --radios-product-out <radio-product>`
checks the separate source radio audit and stages source Wi-Fi HIDL/supplicant,
Bluetooth HIDL and their Android 12 dependencies in the system namespace.
The original Bluetooth vendor C interface and old C++ closure remain isolated
in sphal. Firmware and UART configuration come from the checksum-verified private
v14 kit, not another board. The original adapter address is copied only from
this unit's old private Bluetooth profile into its new private data; it is
neither generated nor embedded in versioned source.

The kernel has no ambient capabilities. Radio services and wificond therefore
use verified revision-2 executable capabilities on the read-only system loop.
Wi-Fi/wificond/supplicant run as UID 1010 with NET_ADMIN/NET_RAW. Bluetooth runs
as UID 1002 with the upstream HAL's NET_ADMIN/SYS_NICE/BLOCK_SUSPEND set. Netd
remains the normal root daemon; its observed root capability set is not claimed
to be restricted by file capabilities alone. Debug/permissive security review
remains a separate release requirement.

The Bluetooth foreground CPU cgroup previously had zero real-time runtime and
the native stack aborted while requesting RT scheduling. The radio init profile
sets its budget to the already observed root CPU budget (950000 us per second).
The client can opt directly into this board's actual HIDL HCI 1.0 service using
`ro.rayo.bluetooth.legacy_hci`, avoiding a two-second lookup for absent 1.1.
The hardware firmware download, power/reset and settlement sequence still
exceeded the upstream 2900 ms startup limit. The existing Android property
`bluetooth.enable_timeout_ms=8000` permits that sequence to complete while
retaining a finite failure timeout and checking actual controller startup.

Wi-Fi supplicant uses the actual Android 12 executable type
`hal_wifi_supplicant_default_exec`; the old `wpa_exec` type was not in this
policy and init refused to start it. The low-level networking services need
the same legacy capability handling as the HAL.

Netd's DNS-over-TLS verifier initially aborted in the platform crypto path on
the pre-getrandom kernel. Preloading the already device-tested upstream
non-FIPS backend specifically for netd resolved that failure. Wi-Fi/supplicant,
software key services and zygote children have explicit analogous scopes.
Platform libcrypto and its FIPS module remain unchanged; no FIPS certification
or hardware-backed security is claimed, and certificate validation is not
disabled. Actual network validation passed with this setup.

## Observed device results

| Run | Result |
|---|---|
| `20261111-080249-63978af6` | Branded Home/Settings navigation passed five actual button/Back cycles; stable system_server; automatic v14 return and recovery restore passed |
| `20261111-082942-7ea16a21` | Radio daemons had expected non-root Wi-Fi/BT UIDs; found supplicant label and RT-group failures; v14 return/recovery restore passed |
| `20261111-084519-0e9a38e8` | Wi-Fi associated and obtained DHCP; DNS TLS crashed netd; BT needed the existing adapter identity; v14 return/recovery restore passed |
| `20261111-085658-4120f6cd` | Wi-Fi connected with validated internet and successful IP ping; netd/system_server remained stable; BT still exceeded its startup deadline; rollback passed |
| `20261111-091938-8fdf76a2` | Wi-Fi reconnected without manually re-entering its profile; setting the supported 8000 ms BT timeout produced ON and a completed discovery reporting five devices; rollback passed |

| `20261111-093343-b66a3207` | Immutable 8000 ms timeout: automatic Wi-Fi association and validated internet, BT ON with zero crashes, fresh completed discovery finding five devices, five Home/Settings/Back cycles, and Wi-Fi off/on reconnection while BT remained ON; normal v14 return and factory recovery readback passed |

Radio-04 included an initial BT failure before the runtime correction. Radio-05
passed with that correction packaged from startup, after a full kernel reboot.
This is not an owner-verified physical power-cycle test. Individual run RESULT
files and credential-free status reports remain local; historical failures are
not rewritten. Home was AOT-compiled with the speed filter, but graphics counters
still show substantial jank; this is not evidence of smooth animation or 60 FPS.

## Tools and privacy

- `gabos-wifi-profile.py --capture` privately reads only the connected supported
  WPA2 profile from normal v14; `--connect` uses it only inside isolated GabOS.
  Passphrases never enter versioned files or tool output. Android 12's CLI takes
  its passphrase positionally; the earlier `-p` attempt was rejected, not saved
  as a valid credential.
- `gabos-bluetooth-address.py` reuses and verifies this unit's actual prior
  adapter identity without printing it or modifying the old profile.
- `GabOS Diagnostics` is a small local app using public Bluetooth APIs, enabling
  and discovering through an actual app context. It reports states and counts,
  not nearby names or addresses. Its new runtime permissions are granted only
  to this development test package.
- `collect-gabos-radio-state.py` reports no credentials, SSIDs, adapter addresses
  or nearby device identities. Include `--diagnostics` only after a fresh helper run.

Pairing, HID reconnect, A2DP audio routing, BLE-specific behavior, suspend/resume,
range and prolonged stability still need accessory/physical tests. A functioning
scan is not proof of those features. Final power-off is requested only after
normal v14 and factory recovery have been verified again.

## End-of-session power state

After radio-05, normal Android 8.1 reported completed boot. Boot MD5 was
`7a3ac489b05f0f2e9cde16280551511a`; factory recovery MD5 was
`b32f971e7dfce13c708d264c6221844d`. The framework shutdown intent was issued
from that verified normal system. ADB subsequently disconnected. Lamp/fan state
was not physically observed because the owner was absent. The temporary host
Wi-Fi migration credential file was deleted; saved device profiles remain private.
