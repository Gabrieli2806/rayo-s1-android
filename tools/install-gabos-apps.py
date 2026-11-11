#!/usr/bin/env python3
"""Install pinned optional apps only into completed, isolated GabOS Android 12."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute',action='store_true')
    args=parser.parse_args()
    sdk=Path(os.environ.get('ANDROID_HOME',str(Path(os.environ['LOCALAPPDATA'])/'Android/Sdk')))/'build-tools/36.0.0'
    records=json.loads((ROOT/'manifests/gabos-optional-apps.json').read_text(encoding='utf-8'))
    prefix=[shutil.which('adb'),'-s','0123456789ABCDEF']
    def adb(*parts,timeout=30):
        return subprocess.check_output(prefix+list(parts),text=True,encoding='utf-8',timeout=timeout).strip()
    for record in records:
        apk=ROOT/'local/device-kit/apps'/record['file']
        if hashlib.file_digest(apk.open('rb'),'sha256').hexdigest()!=record['sha256']:
            raise SystemExit('APK checksum mismatch: '+record['file'])
        output=subprocess.check_output([str(sdk/'apksigner.bat'),'verify','--print-certs',str(apk)],text=True,stderr=subprocess.DEVNULL)
        signer=re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',output)
        if not signer or signer.group(1)!=record['signer_sha256']:
            raise SystemExit('APK signer mismatch')
        badging=subprocess.check_output([str(sdk/'aapt.exe'),'dump','badging',str(apk)],text=True,encoding='utf-8')
        if "name='"+record['package']+"'" not in badging.splitlines()[0]:
            raise SystemExit('APK package mismatch')
    if (adb('shell','getprop','ro.gabos.name')!='GabOS' or
        adb('shell','getprop','ro.build.version.sdk')!='31' or
        adb('shell','getprop','sys.boot_completed')!='1' or
        not any(line.split()[:2]==['/dev/block/loop6','/data'] for line in adb('shell','cat','/proc/mounts').splitlines())):
        raise SystemExit('Requires completed isolated GabOS 12; no changes made')
    for record in records:
        if args.execute:
            # The rescue adbd does not expose abb_exec; use the checked shell PM path.
            remote='/data/local/tmp/gabos-install-'+record['file']
            adb('push',str(ROOT/'local/device-kit/apps'/record['file']),remote,timeout=120)
            try:
                adb('shell','chmod','0644',remote)
                result=adb('shell','pm','install','-r',remote,timeout=120)
                if 'Success' not in result:raise SystemExit(result)
            finally:
                adb('shell','rm','-f',remote)
            info=adb('shell','dumpsys','package',record['package'])
            if 'versionName='+record['version'] not in info:raise SystemExit('Installed version mismatch')
        print(record['package']+' '+record['version']+(' installed' if args.execute else ' verified, plan only'),flush=True)

if __name__=='__main__':main()
