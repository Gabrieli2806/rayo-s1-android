#!/usr/bin/env python3
"""Apply the explicit legacy-network source experiment; never run netd on the live OS."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    for project, name in [('system/bpf', 'legacy-bpf.patch'), ('system/netd', 'legacy-netd.patch')]:
        prefix = ['git', '-C', str(args.source/project), 'apply']
        patch = str(ROOT/'patches/android12'/name)
        reverse = subprocess.run(prefix+['--reverse', '--check', patch], capture_output=True)
        if reverse.returncode == 0:
            print('Already applied:', project)
            continue
        subprocess.run(prefix+['--check', patch], check=True)
        subprocess.run(prefix+[patch], check=True)
        print('Applied source-only experiment:', project)
    print('No product property was changed and no device operation was performed.')


if __name__ == '__main__':
    main()
