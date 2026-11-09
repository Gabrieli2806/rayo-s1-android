"""Extract concrete C layouts from a module; compare candidates offline."""
import json
import sys
from pathlib import Path
from elftools.elf.elffile import ELFFile
from elftools.dwarf.descriptions import describe_form_class
from elftools.dwarf.dwarf_expr import DWARFExprParser

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
layouts = {}
with source.open('rb') as stream:
    elf = ELFFile(stream)
    dwarf = elf.get_dwarf_info()
    parser = DWARFExprParser(dwarf.structs)
    for cu in dwarf.iter_CUs():
        for die in cu.iter_DIEs():
            if die.tag not in ('DW_TAG_structure_type', 'DW_TAG_union_type'):
                continue
            attrs = die.attributes
            if 'DW_AT_name' not in attrs or 'DW_AT_byte_size' not in attrs:
                continue
            name = attrs['DW_AT_name'].value.decode(errors='replace')
            members = {}
            for child in die.iter_children():
                if child.tag != 'DW_TAG_member':
                    continue
                fields = child.attributes
                label = fields.get('DW_AT_name')
                if label is None:
                    continue
                location = fields.get('DW_AT_data_member_location')
                offset = None
                if location is not None:
                    if describe_form_class(location.form) == 'constant':
                        offset = location.value
                    elif describe_form_class(location.form) == 'exprloc' or isinstance(location.value, list):
                        operations = parser.parse_expr(location.value)
                        if len(operations) == 1 and operations[0].op_name == 'DW_OP_plus_uconst':
                            offset = operations[0].args[0]
                members[label.value.decode(errors='replace')] = {
                    'offset': offset,
                    'bit_size': fields['DW_AT_bit_size'].value if 'DW_AT_bit_size' in fields else None,
                    'bit_offset': fields['DW_AT_bit_offset'].value if 'DW_AT_bit_offset' in fields else None,
                }
            layouts[name] = {'size': attrs['DW_AT_byte_size'].value, 'members': members}
destination.write_text(json.dumps(layouts, indent=2))
for name in ('module', 'task_struct', 'mm_struct', 'vm_area_struct', 'file', 'inode', 'cred', 'page', 'files_struct', 'thread_info'):
    item = layouts.get(name)
    print(name, item['size'] if item else 'not emitted')
print('Concrete layouts:', len(layouts))
