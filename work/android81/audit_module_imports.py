from pathlib import Path
import json
import sys
from elftools.elf.elffile import ELFFile

root = Path(__file__).parent
symbols = {line.split()[-1] for line in (root / 'kallsyms-stock.txt').read_text(encoding='utf-8-sig').splitlines() if line.split()}
exports = {name.removeprefix('__ksymtab_') for name in symbols if name.startswith('__ksymtab_')}
with Path(sys.argv[1]).open('rb') as stream:
    elf = ELFFile(stream)
    table = elf.get_section_by_name('.symtab')
    imports = [item.name for item in table.iter_symbols() if item['st_shndx'] == 'SHN_UNDEF' and item.name]
    result = {'imports': len(imports), 'exported': sorted(set(imports) & exports),
              'not_exported': [{'symbol': name, 'present_in_kernel': name in symbols} for name in imports if name not in exports]}
(root / 'hwbinder-import-audit.json').write_text(json.dumps(result, indent=2))
print(json.dumps({'imports': len(imports), 'not_exported': result['not_exported']}, indent=2))
