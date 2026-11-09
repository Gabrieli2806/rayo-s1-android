"""Prepare compatible keymaps offline; do not assign guessed OEM actions."""
from pathlib import Path
import re
import json
work=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
source=work/'build-root/aosp'
labels=(work/'oreo-input-labels.h').read_text()
keys=set(re.findall(r'DEFINE_KEYCODE\((\w+)\)',labels))
flags=set(re.findall(r'DEFINE_FLAG\((\w+)\)',labels))
target=work/'input-keymaps-oreo'
target.mkdir(exist_ok=True)
unsupported=[]
for original in (work/'stock-system/usr/keylayout').glob('*.kl'):
    output=[]
    for number,line in enumerate(original.read_text(errors='strict').splitlines(),1):
        parts=line.split('#',1)[0].split()
        if parts and parts[0]=='key':
            label_index=3 if len(parts)>1 and parts[1]=='usage' else 2
            if len(parts)<=label_index:
                raise ValueError((original.name,number,'Malformed key entry'))
            key=parts[label_index]
            unknown_flags=[f for f in parts[label_index+1:] if f not in flags]
            if key not in keys or unknown_flags:
                unsupported.append({'file':original.name,'line':number,'entry':line,
                                    'key_supported':key in keys,'unknown_flags':unknown_flags})
                if key not in keys:
                    output.append('# OEM key omitted pending explicit integration: '+line)
                else:
                    # Preserve standard actions (including POWER) while removing
                    # flags the Oreo parser does not understand.
                    supported_flags=[f for f in parts[label_index+1:] if f in flags]
                    output.append(' '.join(parts[:label_index+1]+supported_flags))
                continue
        output.append(line)
    (target/original.name).write_text('\n'.join(output)+'\n')
(work/'input-keymap-compatibility.json').write_text(json.dumps(unsupported,indent=2))
print('Prepared keymaps:',len(list(target.glob('*.kl'))))
print('Unsupported OEM entries excluded from test maps:',len(unsupported))
for item in unsupported:
    if item['file'] in ('Generic.kl','rk29-keypad.kl'):
        print(item['file'],item['line'],item['entry'])
