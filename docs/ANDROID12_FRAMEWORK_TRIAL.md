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
