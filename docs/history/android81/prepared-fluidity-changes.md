# Prepared Android 8.1 responsiveness changes

Historical candidate v0.1, November 9, 2026. At preparation it was not installed/physically tested and the projector was unchanged. Current STATUS.md records later results.

ekt2101 input snapshot had empty keymap paths. Actual 8.1 validator rejected stock Generic.kl at PRJ_TEMP_WARNING and other OEM-only labels; button capture was still required. Working adapter allocated/freed lists each prepare, even with unchanged count. Physical density once read approximately 2,147,483 dpi while logical density stayed 160. Both systems already used 1280×720; approximately 648 MiB available including reclaimable cache did not identify RAM as main cause.

Prepared compatible Generic/rk29-keypad maps (both passed actual parser), HWC reusable lists (ARM32 build/ABI checks), invalid-physical-density fallback to 160 dpi (no resolution/refresh change), reversible pointer speed 0/2 and animation 1/0.5 profiles (syntax checked), and event/CPU/RAM/frame capture scripts (not run then).

Pointer sensitivity does not establish latency; shorter animations do not establish higher FPS; list-reuse benefit had not been measured. Planned order: measure known trial; verify loaded maps/capture actual buttons; compare pointer independently; physically test candidate adapter with stock driver; separately test animations and second boot. No automatic installer/flashing image; never overwrite stock with candidate. Profiles require 8.1 and ro.rayo.trial=1 and save prior values.

Unsupported OEM codes remained documented without guessed actions; projector warnings/power functions still need integration. No clocks/voltages/thermal/resolution/memory-system changes; no swap proposal without pressure. Wi-Fi/Bluetooth/audio/video remained pending. Launcher status timer runs every 30 seconds, not the primary cursor-lag suspect. Future TV-style UI should favor static imagery, bounded icons, few simultaneous animations.

Library hwcomposer.rayo_fluidity.so SHA-256 4f620e1b4f068e0abbe14a06dc42eb257653fce6087459b927a4e3f52f075439. Sources, adapter differences, validator records, reversible profiles, checksums retained. No Google services; original firmware backup separate.
