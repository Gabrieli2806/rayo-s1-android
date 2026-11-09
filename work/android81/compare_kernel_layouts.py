import json
from pathlib import Path

root = Path(__file__).parent
stock = json.loads((root / 'kernel-layouts-stock.json').read_text())
candidate = json.loads((root / 'kernel-layouts-candidate.json').read_text())
common = sorted(stock.keys() & candidate.keys())
different = []
for name in common:
    if stock[name] != candidate[name]:
        a, b = stock[name], candidate[name]
        members = sorted(set(a['members']) | set(b['members']))
        changed = [m for m in members if a['members'].get(m) != b['members'].get(m)]
        different.append({'name': name, 'stock_size': a['size'], 'candidate_size': b['size'], 'changed_members': changed})
result = {'compared_types': len(common), 'identical_types': len(common) - len(different), 'differences': different,
          'limit': 'DWARF layout agreement is a compatibility check, not proof that all configuration, code, exported symbols, or runtime behavior match.'}
(root / 'kernel-layout-comparison.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result, indent=2))
