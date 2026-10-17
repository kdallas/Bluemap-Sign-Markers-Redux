#!/usr/bin/env python3
"""Convert the markers in a BlueMap map-config into the marker-set JSON this plugin writes.

BlueMap's per-map configs use HOCON, not JSON: entries are newline-separated rather than
comma-separated, keys are often unquoted, and comments start with '#' or '//'. That is not
valid JSON, so this reads the subset of HOCON that BlueMap actually emits and re-serialises
the marker-sets into the shape MarkerHelper.saveWorldMarkerSet produces.

Usage:
    python3 tools/bmconf2json.py <bluemap-map.conf> [output.json]

With no output path it writes <input stem>-markers.json next to the input. One file is
written per marker-set that contains markers.
"""

import json
import sys
from pathlib import Path

# Characters that terminate a bare (unquoted) token. Quoting is why ':' and '=' are here:
# a bare word may not contain them, so they always parse as separators.
_BARE_STOP = set('{}[]:=,"\'')


class ParseError(Exception):
    """Raised when the input is not the HOCON subset this tool understands."""


class _Parser:
    """Recursive-descent reader for BlueMap's HOCON configs."""

    def __init__(self, text):
        self.s = text
        self.i = 0
        self.n = len(text)

    # ----- primitives -----

    def _peek(self):
        return self.s[self.i] if self.i < self.n else ''

    def _skip_ws(self):
        """Skip whitespace, '#' comments and '//' comments."""
        while self.i < self.n:
            c = self.s[self.i]
            if c.isspace():
                self.i += 1
            elif c == '#' or (c == '/' and self.i + 1 < self.n and self.s[self.i + 1] == '/'):
                while self.i < self.n and self.s[self.i] != '\n':
                    self.i += 1
            else:
                return

    def _skip_separators(self):
        """Skip whitespace and any commas. HOCON separates entries with either."""
        while True:
            self._skip_ws()
            if self._peek() == ',':
                self.i += 1
                continue
            return

    def _read_bare(self):
        start = self.i
        while self.i < self.n:
            c = self.s[self.i]
            if c.isspace() or c in _BARE_STOP or c == '#':
                break
            self.i += 1
        if self.i == start:
            raise ParseError(
                f'unexpected {self.s[self.i:self.i + 1]!r} at offset {self.i}'
            )
        return self.s[start:self.i]

    def _read_quoted(self):
        quote = self.s[self.i]
        self.i += 1
        out = []
        escapes = {'n': '\n', 't': '\t', 'r': '\r', '"': '"', "'": "'",
                   '\\': '\\', '/': '/'}
        while True:
            if self.i >= self.n:
                raise ParseError('unterminated string')
            c = self.s[self.i]
            if c == quote:
                self.i += 1
                return ''.join(out)
            if c == '\\' and self.i + 1 < self.n:
                nxt = self.s[self.i + 1]
                if nxt in escapes:
                    out.append(escapes[nxt])
                    self.i += 2
                    continue
                if nxt == 'u' and self.i + 6 <= self.n:
                    try:
                        out.append(chr(int(self.s[self.i + 2:self.i + 6], 16)))
                    except ValueError:
                        raise ParseError(
                            f'bad \\u escape at offset {self.i}'
                        ) from None
                    self.i += 6
                    continue
            out.append(c)
            self.i += 1

    # ----- grammar -----

    def parse_document(self):
        root = {}
        self._skip_ws()
        while self.i < self.n:
            self._parse_entry(root)
            self._skip_separators()
        return root

    def _parse_entry(self, into):
        key = self._read_key()
        self._skip_ws()
        # ':' or '=' may be omitted before an object, e.g. `marker-sets {`.
        if self._peek() in (':', '='):
            self.i += 1
            self._skip_ws()
        _put(into, key, self._parse_value())

    def _read_key(self):
        self._skip_ws()
        if self._peek() in ('"', "'"):
            return self._read_quoted()
        return self._read_bare()

    def _parse_value(self):
        self._skip_ws()
        c = self._peek()
        if c == '{':
            return self._parse_object()
        if c == '[':
            return self._parse_array()
        if c in ('"', "'"):
            return self._read_quoted()
        token = self._read_bare()
        if token == 'true':
            return True
        if token == 'false':
            return False
        if token == 'null':
            return None
        return _coerce_number(token)

    def _parse_object(self):
        self.i += 1  # consume '{'
        obj = {}
        while True:
            self._skip_separators()
            if self._peek() == '}':
                self.i += 1
                return obj
            if self.i >= self.n:
                raise ParseError('unterminated object')
            self._parse_entry(obj)

    def _parse_array(self):
        self.i += 1  # consume '['
        arr = []
        while True:
            self._skip_separators()
            if self._peek() == ']':
                self.i += 1
                return arr
            if self.i >= self.n:
                raise ParseError('unterminated array')
            arr.append(self._parse_value())


def _coerce_number(token):
    """Return an int or float for a numeric token, else the token unchanged."""
    try:
        if any(ch in token for ch in '.eE'):
            return float(token)
        return int(token)
    except ValueError:
        return token


def _put(target, key, value):
    """Assign a key, merging objects the way HOCON does for duplicate keys.

    BlueMap's own configs repeat `marker-sets`: an empty block near the top, then the real
    one filled in later. HOCON merges those, so overwriting would silently drop markers.
    """
    if key in target and isinstance(target[key], dict) and isinstance(value, dict):
        for k, v in value.items():
            _put(target[key], k, v)
    else:
        target[key] = value


# ----- conversion to this plugin's marker-set shape -----


def _marker_to_plugin(marker):
    if not isinstance(marker, dict):
        raise ValueError(f'marker is not an object: {marker!r}')
    out = {
        'type': marker.get('type', 'poi'),
    }
    if 'position' in marker:
        out['position'] = marker['position']
    out['label'] = marker.get('label', '')
    if 'icon' in marker:
        out['icon'] = marker['icon']
    if 'anchor' in marker:
        out['anchor'] = marker['anchor']
    out['classes'] = marker.get('classes', [])
    # Omitted rather than defaulted to "": the plugin emits an HTML block here, and an
    # empty string renders as an empty popup on the map.
    if marker.get('detail'):
        out['detail'] = marker['detail']
    out['minDistance'] = marker.get('min-distance', 0)
    out['maxDistance'] = marker.get('max-distance', 100000)
    out['sorting'] = marker.get('sorting', 0)
    out['listed'] = marker.get('listed', True)
    return out


def _marker_set_to_plugin(set_id, raw):
    markers = raw.get('markers') or {}
    return {
        'label': raw.get('label', set_id),
        'toggleable': raw.get('toggleable', True),
        'defaultHidden': raw.get('default-hidden', False),
        'sorting': raw.get('sorting', 0),
        'markers': {mid: _marker_to_plugin(m) for mid, m in markers.items()},
    }


def convert(conf_path, out_path=None):
    """Parse conf_path and write one JSON file per non-empty marker-set.

    Returns the list of paths written.
    """
    src = Path(conf_path)
    root = _Parser(src.read_text(encoding='utf-8')).parse_document()

    raw_sets = root.get('marker-sets')
    if not isinstance(raw_sets, dict):
        raise ValueError(f'{src}: no "marker-sets" block found')

    sets = [
        (sid, s) for sid, s in raw_sets.items()
        if isinstance(s, dict) and isinstance(s.get('markers'), dict) and s['markers']
    ]
    if not sets:
        raise ValueError(f'{src}: no marker-set containing markers')

    base = Path(out_path) if out_path else src.with_name(f'{src.stem}-markers')
    if base.suffix:
        base = base.with_suffix('')

    world = root.get('world')
    written = []
    for set_id, raw in sets:
        target = (base.with_suffix('.json') if len(sets) == 1
                  else Path(f'{base}.{set_id}.json'))
        data = _marker_set_to_plugin(set_id, raw)
        target.write_text(json.dumps(data, indent=4, ensure_ascii=False) + '\n',
                          encoding='utf-8')
        written.append(target)
        print(f'{target}: marker-set "{set_id}" ({data["label"]}) '
              f'-> {len(data["markers"])} markers')
        if world:
            print(f'    the plugin expects this as marker-set-{world}.json '
                  f'in its data folder')

    return written


def main(argv):
    if not 1 <= len(argv) <= 2:
        print('usage: bmconf2json.py <bluemap-map.conf> [output.json]', file=sys.stderr)
        return 2
    try:
        convert(argv[0], argv[1] if len(argv) == 2 else None)
    except (OSError, ParseError, ValueError) as exc:
        print(f'error: {exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
