#!/usr/bin/env python3
"""Opt this legacy kernel into pinned-proc caller context recovery, retaining policy checks."""
import argparse
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    directory = args.source/'system/security/keystore2/src'
    shutil.copyfile(ROOT/'work/android12/device/canon/rayos1_12_lab/legacy_sid.rs', directory/'rayo_legacy_sid.rs')
    shutil.copyfile(ROOT/'work/android12/device/canon/rayos1_12_lab/sid_probe.rs', directory/'rayo_sid_probe.rs')
    bp = directory.parent/'Android.bp'
    module = '''
// The diagnostic tests Keystore's exact caller-context helper and belongs to
// this security package, within the platform's existing Rust adoption policy.
rust_binary {
    name: "rayo12_sid_probe",
    srcs: ["src/rayo_sid_probe.rs"],
    rustlibs: ["libanyhow", "liblibc"],
    edition: "2018",
}
'''
    bp_text = bp.read_text()
    if 'name: "rayo12_sid_probe"' not in bp_text:
        bp.write_text(bp_text+module)
    path = directory/'utils.rs'
    text = path.read_text()
    helper = '''
#[path = "rayo_legacy_sid.rs"]
mod rayo_legacy_sid;

fn with_rayo_calling_sid<T>(f: impl FnOnce(Option<&std::ffi::CStr>) -> anyhow::Result<T>) -> anyhow::Result<T> {
    ThreadState::with_calling_sid(|sid| {
        if sid.is_some() {
            return f(sid);
        }
        extern "C" {
            fn property_get_bool(key: *const libc::c_char, default_value: i8) -> i8;
        }
        let enabled = unsafe {
            property_get_bool(b"ro.rayo.keystore.legacy_pid_sid\\0".as_ptr() as *const libc::c_char, 0)
        } != 0;
        if !enabled {
            return f(None);
        }
        let context = rayo_legacy_sid::caller_sid(
            ThreadState::get_calling_pid(), ThreadState::get_calling_uid())?;
        f(Some(context.as_c_str()))
    })
}

'''
    if 'fn with_rayo_calling_sid<' not in text:
        if text.count('ThreadState::with_calling_sid(|calling_sid| {') != 3:
            raise SystemExit('Unexpected pinned keystore permission dispatch')
        text = text.replace('ThreadState::with_calling_sid(|calling_sid| {', 'with_rayo_calling_sid(|calling_sid| {')
        anchor = 'use std::sync::Mutex;\n'
        if text.count(anchor) != 1:
            raise SystemExit('Unexpected pinned keystore utilities imports')
        text = text.replace(anchor, anchor+'\n'+helper)
    path.write_text(text)
    print('Opt-in pinned-proc SID recovery; original UID, key/grant and SELinux permission checks retained.')


if __name__ == '__main__':
    main()
