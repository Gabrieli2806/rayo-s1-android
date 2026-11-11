#!/usr/bin/env python3
"""Opt the measured cgroup-v1 kernel into real memory-controller process groups."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    path = args.source/'system/core/libprocessgroup/processgroup.cpp'
    text = path.read_text()
    before = '    auto controller = CgroupMap::GetInstance().FindController(cgroup_name);'
    after = '''    const bool legacy = cgroup_name == CGROUPV2_CONTROLLER_NAME &&
            GetBoolProperty("ro.rayo.cgroup.legacy", false);
    auto controller = CgroupMap::GetInstance().FindController(legacy ? "memory" : cgroup_name);'''
    if after not in text:
        if text.count(before) != 1:
            raise SystemExit('Unexpected pinned controller lookup')
        text = text.replace(before, after)
        text = text.replace('        *path = controller.path();',
                            '        *path = controller.path();\n        if (legacy) *path += "/apps";', 1)
        # The primary hierarchy now IS memory/apps; do not create or remove it
        # a second time through the optional parallel memory hierarchy.
        condition = 'if (isMemoryCgroupSupported() && UsePerAppMemcg()) {'
        if text.count(condition) != 2:
            raise SystemExit('Unexpected pinned parallel memory group branches')
        text = text.replace(condition, 'if (isMemoryCgroupSupported() && UsePerAppMemcg() &&\n'
            '            !GetBoolProperty("ro.rayo.cgroup.legacy", false)) {')
    path.write_text(text)
    print('Opt-in real cgroup-v1 process groups; default unified hierarchy unchanged.')


if __name__ == '__main__':
    main()
