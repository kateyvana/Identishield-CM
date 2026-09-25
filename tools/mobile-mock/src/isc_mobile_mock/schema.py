"""Validation d'un corps JSON contre un schéma de contracts/mobile-api.yaml (stdlib, ADR-033).

Sous-ensemble de JSON Schema effectivement employé par le contrat : $ref local, type, enum,
pattern, required, properties, additionalProperties: false, items, minItems, maxItems, minimum.
Tout mot-clé de validation inconnu fait échouer la validation (fail-closed) plutôt que d'être
ignoré en silence ; les mots-clés purement descriptifs sont tolérés.
"""

import re

_DESCRIPTIVE = {"description", "format", "summary", "title", "example", "examples"}
_TYPES = {
    "object": dict,
    "array": list,
    "string": str,
    "boolean": bool,
}


class ContractViolation(ValueError):
    """Le corps ne respecte pas le schéma du contrat."""


def resolve(contract: dict, node: dict) -> dict:
    """Suit les $ref locaux (#/components/...) jusqu'au schéma effectif."""
    seen = 0
    while "$ref" in node:
        ref = node["$ref"]
        if not ref.startswith("#/"):
            raise ContractViolation(f"$ref non local : {ref}")
        target = contract
        for part in ref[2:].split("/"):
            target = target[part]
        node = {**target, **{k: v for k, v in node.items() if k != "$ref"}}
        seen += 1
        if seen > 20:
            raise ContractViolation(f"$ref circulaire : {ref}")
    return node


def validate(contract: dict, schema: dict, value, where: str = "$") -> None:
    schema = resolve(contract, schema)
    unknown = (
        set(schema)
        - _DESCRIPTIVE
        - {
            "type",
            "enum",
            "pattern",
            "required",
            "properties",
            "additionalProperties",
            "items",
            "minItems",
            "maxItems",
            "minimum",
        }
    )
    if unknown:
        raise ContractViolation(
            f"{where} : mot-clé de schéma non pris en charge : {sorted(unknown)}"
        )
    kind = schema.get("type")
    if kind == "integer":
        if not isinstance(value, int) or isinstance(value, bool):
            raise ContractViolation(f"{where} : entier attendu, reçu {value!r}")
    elif kind is not None:
        expected = _TYPES.get(kind)
        if expected is None:
            raise ContractViolation(f"{where} : type non pris en charge : {kind}")
        if not isinstance(value, expected) or (kind != "boolean" and isinstance(value, bool)):
            raise ContractViolation(f"{where} : {kind} attendu, reçu {value!r}")
    if "enum" in schema and value not in schema["enum"]:
        raise ContractViolation(f"{where} : {value!r} hors de l'énumération {schema['enum']}")
    if "pattern" in schema and not re.search(schema["pattern"], value):
        raise ContractViolation(f"{where} : {value!r} ne respecte pas {schema['pattern']}")
    if "minimum" in schema and value < schema["minimum"]:
        raise ContractViolation(f"{where} : {value!r} < {schema['minimum']}")
    if isinstance(value, list):
        if len(value) < schema.get("minItems", 0) or len(value) > schema.get(
            "maxItems", len(value)
        ):
            raise ContractViolation(f"{where} : nombre d'éléments hors bornes ({len(value)})")
        for i, item in enumerate(value):
            validate(contract, schema.get("items", {}), item, f"{where}[{i}]")
    if isinstance(value, dict):
        props = schema.get("properties", {})
        for name in schema.get("required", []):
            if name not in value:
                raise ContractViolation(f"{where} : champ obligatoire absent : {name}")
        if schema.get("additionalProperties") is False:
            extra = set(value) - set(props)
            if extra:
                raise ContractViolation(f"{where} : champs hors contrat : {sorted(extra)}")
        for name, sub in props.items():
            if name in value:
                validate(contract, sub, value[name], f"{where}.{name}")


def operation(contract: dict, method: str, template: str) -> dict:
    return contract["paths"][template][method.lower()]


def request_schema(contract: dict, method: str, template: str):
    body = operation(contract, method, template).get("requestBody")
    if body is None:
        return None
    return body["content"]["application/json"]["schema"]


def response_schema(contract: dict, method: str, template: str, status: int):
    """Schéma du corps attendu pour ce code HTTP ; ContractViolation si le code n'est pas déclaré."""
    responses = operation(contract, method, template)["responses"]
    declared = responses.get(str(status))
    if declared is None:
        raise ContractViolation(f"{method} {template} : code {status} non déclaré par le contrat")
    declared = resolve(contract, declared)
    return declared["content"]["application/json"]["schema"]
