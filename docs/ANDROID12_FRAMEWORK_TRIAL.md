# Android 12 framework bringup

Normal boot remains Android 8.1 v14. These are temporary recovery-slot trials,
not a validated Android 12 ROM or an original Google TV Home installation.

## Protected baseline and trial storage

- Keep the factory kernel, normal boot, original system, bootloader, trust and
  resource partitions unchanged.
- Read and verify a fresh factory recovery backup before writing the trial.
- Temporarily write only the 32 MiB recovery slot, with full readback verification.
- Mount the 800 MiB trial system file readonly through loop7.
- Identify physical userdata by its measured Linux start LBA and exact unit UUID
  before mounting it. Framework trials mount it read/write to host the private
  data file; earlier native diagnostics used readonly `noload` mounts.
- Expose only a dedicated 256 MiB ext4 file as Android 12 `/data` through loop6.
  The original Android data tree is not bound into this trial. Verify the file's
  UUID, size, owner, link count, permissions and filesystem feature mask.
- Use a separate static child watchdog requesting normal reboot after 600
  seconds. The timed return passed in the second full-root trial; the first
  trial's early service-failure reboot alone did not establish that result.
- Restore factory recovery only after independently verifying completed normal
  Android 8.1 boot and its unchanged boot partition. Verify recovery readback.

The controller keeps at least 768 MiB of physical userdata space reserved. It
refuses unknown existing artifacts. Explicit reuse requires a checksum-known,
unmapped earlier system file, an earlier verified normal return/recovery
restoration, and a matching isolated data UUID. It retains mutable trial data;
it does not reset or overwrite the original Android data.

Current candidates keep the framework boot dispatch behind an explicit host
trigger. The host verifies root USB, the source init hash, PID 1 and loop-backed
data/readonly system mounts before starting it. A bounded rotating logcat file
is retained in private trial data, independently of USB. The host retrieves
that private image after normal return and recovery restoration. The newer
bootstrap also protects its timer child against low-memory killing; its normal
Android execution guard still exits 64 without performing mounts or reboots.

Physical emergency/MaskROM entry remains untested. Do not describe recovery
from arbitrary bootloader or storage damage as proven.

## Offline preparation

Build the pinned Cortex-A7/Binder32 product with `tools/build-android12-tv.sh files`
and the static framework bootstrap with `tools/build-android12-native-lab.sh
rayo12_framework_init_bootstrap`. Run the packager under native Linux/WSL ext4:

```sh
python3 tools/prepare-android12-framework.py \
  --source "$HOME/android/rayo12" \
  --product-out "$HOME/android/rayo12-tv-out/target/product/rayos1_tv12" \
  --native-product-out "$HOME/android/rayo12-out/target/product/rayos1_12_lab"
```

Copy the reported manifest and three image files into a new ignored
`outputs/android12-framework/<candidate>` directory on the host. The packager
does not access a device. Run the controller without `--execute` for a plan.
With the already-authorized device attached, `--execute` performs the guarded
trial and restoration. Do not run competing reboot/partition tools during it.

The source-built full system retains normal flattened APEX/runtime layout and
core Java/framework components. Software Keymaster/Gatekeeper support is not
hardware-backed security or attestation. Original Google TV packages are a
later, separate integration stage.

## Compatibility changes

The verified legacy graphics closure remains separated from Android 12 C++ in
an opt-in sphal namespace. New allocator/mapper implementations live in the new
system namespace. ION client-target usage preserves the previously verified
physical scanout. Graphics device-node permissions come from the verified v14
board rules, limited to the named graphics nodes.

The measured kernel has cgroup v1 memory support and no cgroup2 mount. The
opt-in process-group adaptation uses real `memory/apps` cgroups for process
creation, membership, killing and removal, avoiding duplicate parallel memory
groups. It does not claim cgroup2/freezer support or replace membership with
ordinary directories. Upstream behavior remains the default.

Normal Android 12 boot properties must be installed in canonical
`/system/etc/build.prop`, preserving the complete source product properties.
The earlier `prop.default` staging was not consumed by normal second-stage
init, so the legacy USB/graphics/cgroup flags were not activated. The packager
now checks the effective canonical property values and SDK identity. Its
linker metadata uses the source `system_linker_config` protobuf and the actual
Soong stub-library inventory through the standard `systemprovide` command,
including networking, stats and other APEX contracts.

SELinux file contexts expand the combined `/vendor`/`/system/vendor` aliases
into literal paths and sort by the pinned AOSP specificity rules before image
labeling. Runtime context files receive the same normalization. The packager
checks actual image inode labels for init, zygote, the vendor BoringSSL test,
composer, Keymaster and software Gatekeeper. Trial recovery-install services
are excluded; service failure reboot targets select normal boot.

New graphics and Health binder services execute from the system namespace,
with correctly labeled executable inodes. Their new passthrough implementations
also live in system/hw. Legacy graphics blobs stay isolated in the old closure.
The default Health service requires its separately packaged implementation.

## Results

`20261110-172030-9ab5508e`: the first full root mounted the private data image,
verified the system hash, loaded policy 30 and entered Android 12 second-stage
init. The source BoringSSL system test passed, and the unchanged guarded HwBinder
module loaded. The vendor BoringSSL executable had a generic `system_file`
label, preventing its domain transition and triggering the normal failure
reboot before USB/framework startup. The owner reported no projected image.
Normal v14 returned; factory recovery restoration and boot hashes passed.

This exposed an image-labeling defect rather than proving a framework, ART or
graphics regression. The next candidate normalizes context specificity and
adds cgroup-v1 process groups. Its device outcome must be recorded separately.

`20261110-173242-16c0042b`: labeling advanced past the first failure, but the
canonical property error left the compatibility flags inactive. The USB briefly
answered a version read before going offline. The framework and native services
restarted repeatedly; no UI appeared. The independent timer returned to v14 at
approximately ten minutes, with factory recovery restoration verified. The
private data image and native tombstones were retrieved after restoration.
They identified missing stats APEX linkage in zygote, an absent device VINTF
manifest in keystore, and a missing Health passthrough implementation. The
audio HAL lookup also walked past its null version sentinel when no service
was found; the source iteration now stops at the sentinel, without claiming
that a working audio HAL has been provided.

The next candidate fixes those packaging inputs, supplies the verified graphics
device manifest alongside software HAL fragments, and gates framework startup
behind USB/storage preflight. Its result is not implied by these corrections.

Framework/SystemServer, projected UI, app input, radios, audio/video and Google
TV Home remain unvalidated until a later trial demonstrates them. Native
projected colors and the core Java probe are separate earlier passing gates.

## Full-root graphics and framework checkpoints

`20261110-212952-62af9cdf`: canonical compatibility properties and gated root
USB/storage preflight passed. The device is pre-Treble, so its generated legacy
linker section did not contain sphal: factory graphics initially tried the new
default libion and failed resolving `ion_get_phys`. A temporary RAM bind of the
complete generated config plus the isolated sphal namespace made composer,
allocator, SurfaceFlinger, zygote and system_server stay alive. The owner
confirmed the Android boot animation through the lens. The source linker
adaptation now adds sphal to the legacy section as well as configuring it.
The Java trace showed system_server waiting for installd before entering its
main method. Since C had already mounted data, no mount_all dispatched the
stock `nonencrypted` event. The trial RC now dispatches it after post-fs-data.
Normal v14 and verified factory recovery returned at the timer deadline.

`20261110-220041-aec4178d`: automatic graphics startup and installd succeeded;
system_server reached package manager and core service startup. Source HIDL 7
audio loaded the original primary module and opened 48 kHz stereo output with
a 512-frame HAL buffer. Physical sound has not been verified. The old stream
struct lacks later optional event/metadata slots; pointer non-null checks do
not establish their presence because they read adjacent legacy memory. The
adapter now avoids those slots for the selected legacy stream ABI. A timed
normal return and factory recovery restoration passed again.

The software key services aborted in platform FIPS RNG initialization because
this measured kernel lacks getrandom. A separate library built from upstream
non-FIPS `libcrypto_static` passed isolated device tests: FIPS_mode zero, kernel
entropy acquisition, SHA-256 known answer, P-256 generation/sign/verify, and
rejection of an altered digest. `run-android12-crypto-probe.py` verifies the
normal boot/recovery baseline before and after that bounded process; it does
not change a service, mount or partition. No fake randomness, synthetic
getrandom syscall or modified platform FIPS binary is used.

The image accepts that backend only when its exact hash matches the device
validation record. Software Keymaster and keystore2 use a scoped preload.
system_server also required the backend; a temporary RAM-bind zygote wrapper
advanced past package manager. The next image supplies the normal zygote
service environment directly, including its forked children. This backend is
explicitly non-FIPS and does not provide hardware-backed attestation or FIPS
certification. The platform self-test executables are not bypassed or replaced.

A subsequent system_server crash occurred in JIT code-cache allocation
(`mspace_malloc` / `JitMemoryRegion::AllocateCode`). The next diagnostic disables
JIT and JIT profile saving while retaining source-built precompiled artifacts.
The root cause and performance implications remain unvalidated; this is a
bringup setting, not an optimization claim. Runtime audio callback fixes and
the zygote wrapper were RAM/private-data changes only and vanished at reboot.

`20261110-221751-0ab7bf47`: with the audio ABI guards, zygote crypto environment
and JIT disabled, system_server reached System UI and reported boot complete.
However, LockSettingsService repeatedly received Keystore's SYSTEM_ERROR (4):
the old Binder driver does not provide transaction security contexts. The
resulting Java assertion killed the system and its Settings window. The earlier
controller recorded the raw boot flag as success; **this run did not validate a
stable GUI**. The controller now records that flag separately and requires a
focused Settings window with an unchanged system_server for at least 45 seconds.
Physical projection and longer stability are still separate checks.

The opt-in Keystore adapter prefers the native transaction SID whenever one is
present. On this legacy kernel it can instead pin the Binder caller's proc
directory, verify the effective UID against the driver-provided calling UID,
read the actual kernel SELinux context twice, and reject a missing/dead PID,
UID mismatch/drift, changed context or unreadable data. Existing key, grant,
UID and SELinux authorization functions remain unchanged. It never substitutes
a privileged context or an unlocked state. This current-process observation
is not equivalent to a kernel transaction-time SID snapshot, particularly for
concurrent credential/context transitions; retain the diagnostic classification
until security and kernel compatibility are separately reviewed.

The same helper is compiled into a Keystore-owned Rust probe within the existing
Rust adoption policy. Its isolated v14 device test passed actual-context reads
and rejected a wrong UID and PID zero. Normal boot/recovery were verified before
and after it. Main Keystore compilation and clippy passed. The image accepts
only the helper hash matching that validation record. The subsequent full boot
must establish whether the real Keystore UID can obtain and authorize callers.

`20261110-224025-3ac36afa`: the caller-context adapter allowed system_server
to remain running, user zero unlocked, and System UI reached the projected
wallpaper. The owner confirmed visible wallpaper and touchpad cursor movement.
This is not a usable launcher validation: no home application is installed.
Opening TV Settings terminated its process with a fatal framework WTF because
WifiManager had no published service. SystemServer starts that service only
when the Wi-Fi hardware feature is declared; the source product omitted the
feature XML despite the board having a radio. The next candidate includes the
official hardware declaration. Radio operation remains unvalidated on Android 12.
The controller now reads window display focus and explicitly rejects FallbackHome
as proof of an interactive Settings screen.

`20261110-225303-df68d72f`: declaring Wi-Fi allowed SystemServer to publish
the Wi-Fi service. This does not validate the physical radio. Settings next
reported a missing Ethernet service; the board's USB-host feature declaration
was also absent (SystemServer uses it to enable Ethernet/USB-adapter support).
The next candidate copies both official hardware declarations.

The factory composer also crashed at `try_prepare_first+380`, dereferencing
`0xff000028`. The upstream HWC2On1 adapter queries background-color capability;
this factory advertises it, so a solid color such as `0xff000000` occupies the
handle union. The factory then treats it as a buffer pointer despite the layer
being marked for client composition. The opt-in
`ro.rayo.graphics.no_hwc_background` quirk disables that advertised capability,
selecting upstream's existing null-handle/client-composition path for solid
colors. It does not replace buffers, colors or fences with fabricated values.
This source fix requires a rebuilt libhwc2on1adapter and on-device validation.

`20261110-230032-ddcdd7be`: the rebuilt quirk was active; Wi-Fi and Ethernet
framework services were published and MainSettings acquired display focus.
The owner saw Settings, then reported it closing before trying the power menu.
The captured kernel log explicitly records `shutdown,userrequested` from
system_server, so the final return cannot be classified as a spontaneous
framework reboot. The earlier Settings closure remains unresolved. No stable
GUI pass is claimed. Normal v14 returned and factory recovery was restored and
hash-verified. A point-in-time dumpsys reported 415318 KiB Free RAM (including
reclaimable caches), not a sustained performance benchmark.

`20261110-230611-0dcca803`: the same solid-color candidate passed the stable
Settings focus check with unchanged system_server. The owner confirmed opening
Settings options and explained that exiting left no home menu. Continuous logs
showed application-requested activity finishes without a Settings process death.
The normal v14 return and factory recovery restoration were verified.

The owner then selected a simplified local Google-TV-style interface without
Google services. See [the lightweight launcher](LIGHTWEIGHT_TV_LAUNCHER.md).

`20261110-231858-d4343a03`: the local 0.3 development launcher installed into
isolated data and became the default Home. Captures showed the dark UI, owner
logo, app icon rail, clock/battery broadcast and Android TV Settings access.
The owner confirmed seeing Home and Settings. Google Home was uninstalled;
GSF had already been removed and GMS had failed to install. This is not proof
of radio, media, battery accuracy or prolonged stability.

The host later lost USB before observing normal return. Its RESULT correctly
records normal return and factory recovery restoration as false. Preserve this
failure record. The independently saved factory recovery image still passes
its complete size/MD5 check on the PC. Follow the guarded restoration step in
[the continuation checklist](ANDROID12_NEXT_STEPS.md) before further trials.
