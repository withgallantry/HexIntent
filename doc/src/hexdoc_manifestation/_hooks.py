from importlib.resources import Package

from hexdoc.core import Properties
from hexdoc.plugin import HookReturn, ModPlugin, ModPluginImplWithProps, ModPluginWithBook, hookimpl
from typing_extensions import override

import hexdoc_manifestation

from .__gradle_version__ import FULL_VERSION, GRADLE_VERSION
from .pattern_sync import sync_pattern_stubs
from .__version__ import PY_VERSION


class ManifestationPlugin(ModPluginImplWithProps):
    @staticmethod
    @hookimpl
    def hexdoc_mod_plugin(branch: str, props: Properties) -> ModPlugin:
        sync_pattern_stubs()
        return ManifestationModPlugin(branch=branch, props=props)


class ManifestationModPlugin(ModPluginWithBook):
    @property
    @override
    def modid(self) -> str:
        return "manifestation"

    @property
    @override
    def full_version(self) -> str:
        return FULL_VERSION

    @property
    @override
    def mod_version(self) -> str:
        return GRADLE_VERSION

    @property
    @override
    def plugin_version(self) -> str:
        return PY_VERSION

    @override
    def resource_dirs(self) -> HookReturn[Package]:
        # Import lazily because generated content may not exist at import time.
        from hexdoc_manifestation._export import generated

        return generated

    @override
    def jinja_template_root(self) -> tuple[Package, str]:
        return hexdoc_manifestation, "_templates"
