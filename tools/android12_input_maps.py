"""Normalize verified legacy keymaps without inventing key actions."""


def normalized(path):
    seen = {}
    output = []
    for line in path.read_text(encoding='utf-8').splitlines():
        parts = line.split('#', 1)[0].split()
        if parts and parts[0] == 'key':
            columns = 3 if len(parts) > 1 and parts[1] == 'usage' else 2
            if len(parts) <= columns:
                raise ValueError('Malformed key entry in '+str(path))
            key, action = tuple(parts[:columns]), tuple(parts[columns:])
            if key in seen:
                if seen[key] != action:
                    raise ValueError('Conflicting OEM key entries in '+str(path)+': '+str(key))
                continue
            seen[key] = action
        output.append(line)
    return '\n'.join(output)+'\n'
