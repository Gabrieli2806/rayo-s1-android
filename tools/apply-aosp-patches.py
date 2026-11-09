#!/usr/bin/env python3
from pathlib import Path
import argparse
import subprocess
import json

parser=argparse.ArgumentParser()
parser.add_argument('--aosp',required=True,type=Path)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
series=json.loads((root/'patches/aosp/series.json').read_text())
for item in series:
    directory=args.aosp/item['path']
    patch=root/'patches/aosp'/item['patch']
    git=['git','-c','safe.directory='+str(directory),'-C',str(directory)]
    base=git+['apply']
    if subprocess.run(base+['--reverse','--check',str(patch)],capture_output=True).returncode==0:
        print('Already applied:',item['path']);continue
    actual=subprocess.check_output(git+['rev-parse','HEAD']).decode().strip()
    if actual!=item['base_revision']:
        raise SystemExit('Unexpected base revision: '+item['path'])
    subprocess.run(base+['--check',str(patch)],check=True)
    subprocess.run(base+[str(patch)],check=True)
    print('Applied:',item['path'])
