"""Shared filter for derived aliases. Everything fetched from a source is untrusted text: keep only short printable ASCII."""
import re

PRINTABLE = re.compile(r"[ -~]{2,40}")


def clean(alias, key, candidates_lower):
    """The alias stripped, or None if it must not be used for the tag with this key."""
    a = " ".join(alias.split())
    if not PRINTABLE.fullmatch(a):
        return None
    low = a.lower()
    if low == key or low in candidates_lower:  # never restate the tag, never take another candidate's name
        return None
    if re.search(r"[<>{}\\`:()]", a) or a.startswith("."):  # markup, namespaces ("Extension:AWS"), disambiguations, file extensions
        return None
    if len(a) <= 2 and not a.isupper():  # "rb", "tf", "es" are not names; "JS" and "TS" are
        return None
    return a


def read_candidates(path):
    names = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                names.append(line)
    return names
