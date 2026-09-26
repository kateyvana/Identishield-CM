"""La chaîne Python fonctionne (même convention que les autres outils)."""

from isc_mobile_mock import __version__


def test_package_is_importable() -> None:
    assert __version__ == "0.1.0"
