from pathlib import Path
import re
work=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
text=(work/'hwcomposer-rockchip-nougat.h').read_text()
names=['hwc_layer_1','hwc_display_contents_1','hwc_procs','hwc_composer_device_1']
blocks=[]
for name in names:
    start=text.index('typedef struct '+name+' {')
    end=text.index('} '+name+'_t;',start)+len('} '+name+'_t;')
    blocks.append(text[start:end])
result='\n'.join(blocks)
for name in sorted(names,key=len,reverse=True):
    result=re.sub(r'\b'+name+r'(_t)?\b',lambda m:'rk_'+m[0],result)
result='#pragma once\n#define LayerNameLength 60\n'+result+'\n'
(work/'device/canon/rayos1/rk_hwc_abi.h').write_text(result)
print('Extracted Rockchip ABI structures for compile-time verification')
