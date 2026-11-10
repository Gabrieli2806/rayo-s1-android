# Android 12 legacy networking source experiment

These patches restore an explicit non-eBPF backend for investigation on the original Rayo kernel. **Compiled, not runtime validated. Do not launch this netd against the working 8.1 system or apply its rules to that live network.**

Primary upstream source: [8890q/patches](https://github.com/8890q/patches/tree/9ce066611ad946435041cee7d5a45461e4dea398), pinned commit `9ce066611ad946435041cee7d5a45461e4dea398`. Preserve the patches' original author/license provenance. This is a community Android 12.1 legacy-kernel implementation, not Canon board support or an official Google update.

- [Original netd patch](https://github.com/8890q/patches/blob/9ce066611ad946435041cee7d5a45461e4dea398/system_netd/0001-Add-back-support-for-non-bpf-trafic-monitoring.patch), original patch commit `9264fbe7c9afb043dafff4d3ba0beff9b83889ec`.
- [Original BPF patch](https://github.com/8890q/patches/blob/9ce066611ad946435041cee7d5a45461e4dea398/system_bpf/0001-Ignore-bpf-errors-for-4.9-kernels.patch), original patch commit `c63b00403e747ee63bb83b0c7682341d406679ae`.

Local netd adaptations to AOSP `android-12.0.0_r34`: adjust the FirewallController context (`int res = 0`, without the later branch's flush call); retain the original unconditional fatal handling of unexpected TrafficController startup errors. The restored non-BPF start path itself succeeds because it deliberately selects the legacy backend. No broad failure suppression was added. Line endings are normalized to LF.

The implementation restores iptables owner/quota rule construction and legacy xt_qtaguid accounting calls, with explicit `ro.kernel.ebpf.supported=false` selection. BPF remains the default when the property is absent/true. The BPF loader's opt-out alone is insufficient: netd's legacy backend and framework accounting must agree. Android 12 NetworkStatsService in this source already selects BPF based on the map's existence; this observation is not end-to-end verification.

`netd` and `bpfloader` compiled successfully in the native Binder32 lab. The running 8.1 kernel exposes owner, quota2, conntrack matches, REJECT targets and `/proc/net/xt_qtaguid/ctrl`; only read-only inventory was performed. Availability does not establish correct UID filtering, quotas, doze/restricted rules, socket accounting, DNS, routing, IPv6, Wi-Fi connectivity or validated internet under Android 12. Those remain mandatory tests in an isolated network environment and the eventual OS trial. Tethering/offload also needs separate treatment; do not claim it works.

Apply using `tools/apply-android12-network-lab.py --source <pinned-source-root>`. It checks the patch or its exact reverse before modifying source. `tools/build-android12-tv.sh` replays the patches for its dedicated TV development product. No device property or partition is changed by either tool.
