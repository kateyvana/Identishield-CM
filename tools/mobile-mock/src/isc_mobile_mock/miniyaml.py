"""Lecteur YAML restreint, bibliothèque standard seule (ADR-033).

Deux usages : lire config/ruleset.yaml (paramètres nommés) et contracts/mobile-api.yaml (tests
de contrat). Il ne couvre que le sous-ensemble écrit dans ces fichiers et REFUSE tout le reste
(ancres, alias, collections en ligne, documents multiples, tabulations) au lieu de deviner :

- correspondances en bloc (« clé: valeur » ou « clé: » suivi d'un bloc indenté) ;
- séquences en bloc (« - valeur » ou « - clé: valeur » ouvrant une correspondance) ;
- scalaires : chaînes entre '...' ou "...", entiers, true/false, null, chaînes simples ;
- collections en ligne VIDES seulement : [] et {} ;
- bloc littéral « | » (descriptions sur plusieurs lignes) ;
- commentaires « # » en début de ligne ou après une valeur.
"""

import re

_INT = re.compile(r"-?\d+")
# « - clé: ... » ouvre une correspondance ; « - 'a: b' » reste un scalaire.
_MAP_ITEM = re.compile(r"(?:'[^']*'|\"[^\"]*\"|[^'\"\s:#][^:#]*?):\s")


class YamlSubsetError(ValueError):
    """Construction YAML hors du sous-ensemble pris en charge."""


def _strip_comment(text: str) -> str:
    """Retire un commentaire de fin de ligne, hors chaîne entre guillemets."""
    quote = None
    for i, ch in enumerate(text):
        if quote:
            if ch == quote:
                quote = None
        elif ch in "'\"":
            quote = ch
        elif ch == "#" and (i == 0 or text[i - 1] in " \t"):
            return text[:i].rstrip()
    return text.rstrip()


def _scalar(raw: str, lineno: int):
    raw = raw.strip()
    if raw == "":
        return None
    if raw == "[]":  # seules collections en ligne admises : vides (OpenAPI « security: [] »)
        return []
    if raw == "{}":
        return {}
    if raw[0] in "[{&*!" or raw.startswith("---"):
        raise YamlSubsetError(f"ligne {lineno} : construction non prise en charge : {raw!r}")
    if raw[0] == "'":
        if not raw.endswith("'") or len(raw) < 2:
            raise YamlSubsetError(f"ligne {lineno} : chaîne '...' mal fermée")
        return raw[1:-1].replace("''", "'")
    if raw[0] == '"':
        if not raw.endswith('"') or len(raw) < 2:
            raise YamlSubsetError(f'ligne {lineno} : chaîne "..." mal fermée')
        return raw[1:-1].replace('\\"', '"').replace("\\\\", "\\")
    if raw in ("true", "false"):
        return raw == "true"
    if raw in ("null", "~"):
        return None
    if _INT.fullmatch(raw):
        return int(raw)
    return raw


def _split_key(text: str, lineno: int):
    """« clé: reste » -> (clé, reste). La clé peut être entre guillemets."""
    if text[0] in "'\"":
        end = text.find(text[0], 1)
        if end < 0 or text[end + 1 : end + 2] != ":":
            raise YamlSubsetError(f"ligne {lineno} : clé entre guillemets mal formée")
        return text[1:end], text[end + 2 :]
    m = re.match(r"([^:#]+?):(?:\s|$)", text + " ")
    if not m:
        raise YamlSubsetError(f"ligne {lineno} : « clé: valeur » attendu : {text!r}")
    return m.group(1), text[m.end() - 1 :]


def loads(text: str):
    """Analyse un texte YAML du sous-ensemble ; renvoie dict/list/scalaires."""
    lines = []
    raw_lines = text.splitlines()
    i = 0
    while i < len(raw_lines):
        line = raw_lines[i]
        if "\t" in line[: len(line) - len(line.lstrip())]:
            raise YamlSubsetError(f"ligne {i + 1} : tabulation d'indentation")
        stripped = line.strip()
        if stripped == "" or stripped.startswith("#"):
            i += 1
            continue
        lines.append((len(line) - len(line.lstrip(" ")), _strip_comment(line.strip()), i + 1))
        # bloc littéral : les lignes suivantes plus indentées sont du texte brut
        if lines[-1][1].endswith(": |") or lines[-1][1] == "|" or lines[-1][1].endswith("- |"):
            indent = lines[-1][0]
            block = []
            j = i + 1
            while j < len(raw_lines) and (
                raw_lines[j].strip() == ""
                or len(raw_lines[j]) - len(raw_lines[j].lstrip(" ")) > indent
            ):
                block.append(raw_lines[j])
                j += 1
            while block and block[-1].strip() == "":
                block.pop()
            margin = min((len(b) - len(b.lstrip(" ")) for b in block if b.strip()), default=0)
            literal = "\n".join(b[margin:] for b in block) + "\n"
            lines[-1] = (indent, lines[-1][1][:-1].rstrip() + " \x00LIT", lines[-1][2], literal)
            i = j
            continue
        i += 1

    pos = 0

    def value_of(entry, rest):
        if rest.strip() == "\x00LIT":
            return entry[3]
        return _scalar(rest, entry[2])

    def parse_block(indent):
        nonlocal pos
        first = lines[pos]
        if first[0] != indent:
            raise YamlSubsetError(f"ligne {first[2]} : indentation inattendue")
        if first[1].startswith("- ") or first[1] == "-":
            return parse_seq(indent)
        return parse_map(indent)

    def parse_map(indent):
        nonlocal pos
        out = {}
        while pos < len(lines) and lines[pos][0] == indent and not lines[pos][1].startswith("- "):
            entry = lines[pos]
            key, rest = _split_key(entry[1], entry[2])
            if key in out:
                raise YamlSubsetError(f"ligne {entry[2]} : clé en double {key!r}")
            pos += 1
            if rest.strip() == "":
                if pos < len(lines) and lines[pos][0] > indent:
                    out[key] = parse_block(lines[pos][0])
                elif (
                    pos < len(lines) and lines[pos][0] == indent and lines[pos][1].startswith("- ")
                ):
                    out[key] = parse_seq(indent)
                else:
                    out[key] = None
            else:
                out[key] = value_of(entry, rest)
        if pos < len(lines) and lines[pos][0] > indent:
            raise YamlSubsetError(f"ligne {lines[pos][2]} : indentation inattendue")
        return out

    def parse_seq(indent):
        nonlocal pos
        out = []
        while pos < len(lines) and lines[pos][0] == indent and lines[pos][1].startswith("-"):
            entry = lines[pos]
            item = entry[1][1:].strip()
            if item == "":
                pos += 1
                out.append(parse_block(lines[pos][0]))
                continue
            if _MAP_ITEM.match(item + " "):
                # « - clé: valeur » ouvre une correspondance indentée de 2 de plus
                child_indent = indent + 2
                lines[pos] = (child_indent, item, entry[2], *entry[3:])
                out.append(parse_map(child_indent))
            else:
                pos += 1
                out.append(value_of(entry, item))
        return out

    if not lines:
        return None
    result = parse_block(lines[0][0])
    if pos != len(lines):
        raise YamlSubsetError(f"ligne {lines[pos][2]} : contenu non analysé")
    return result


def load(path):
    with open(path, encoding="utf-8") as fh:
        return loads(fh.read())
