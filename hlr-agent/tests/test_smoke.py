"""Trivial test required by TACHE-00 task 5: the Python toolchain works."""

from isc_hlr_agent import __version__


def test_package_is_importable() -> None:
    assert __version__ == "0.1.0"
