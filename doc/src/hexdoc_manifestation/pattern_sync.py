from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
SERVER_SOURCE = REPO_ROOT / "src/main/kotlin/com/bluup/manifestation/server/ManifestationServer.kt"
STUBS_PATH = REPO_ROOT / "doc/pattern_stubs.json"

SIGNATURE_RE = re.compile(r'private const val (\w+) = "([^"]+)"')
DIRECTION_RE = re.compile(r"private val (\w+) = HexDir\.([A-Z_]+)")
REGISTRATION_RE = re.compile(
    r'Manifestation\.id\("([^"]+)"\),\s*'
    r'ActionRegistryEntry\(\s*'
    r'HexPattern\.fromAngles\((\w+),\s*(\w+)\)',
    re.MULTILINE | re.DOTALL,
)


def _load_server_source() -> str:
    return SERVER_SOURCE.read_text(encoding="utf-8")


def _build_pattern_stubs(source: str) -> list[dict[str, object]]:
    signatures = dict(SIGNATURE_RE.findall(source))
    directions = dict(DIRECTION_RE.findall(source))

    stubs: list[dict[str, object]] = []
    for pattern_id, signature_name, direction_name in REGISTRATION_RE.findall(source):
        signature = signatures.get(signature_name)
        direction = directions.get(direction_name)
        if signature is None or direction is None:
            missing = signature_name if signature is None else direction_name
            raise ValueError(f"Missing pattern registration source for {pattern_id}: {missing}")

        stubs.append(
            {
                "id": f"manifestation:{pattern_id}",
                "signature": signature,
                "startdir": direction,
                "is_per_world": False,
            }
        )

    if not stubs:
        raise ValueError("No pattern registrations were found in ManifestationServer.kt")

    return stubs


def render_pattern_stubs() -> str:
    return json.dumps(_build_pattern_stubs(_load_server_source()), indent=2) + "\n"


def sync_pattern_stubs(*, check: bool = False) -> bool:
    rendered = render_pattern_stubs()
    current = STUBS_PATH.read_text(encoding="utf-8") if STUBS_PATH.exists() else None

    if current == rendered:
        return False

    if check:
        raise SystemExit("doc/pattern_stubs.json is out of sync with ManifestationServer.kt")

    STUBS_PATH.write_text(rendered, encoding="utf-8")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync hexdoc pattern stubs from ManifestationServer registrations.")
    parser.add_argument("--check", action="store_true", help="Fail if doc/pattern_stubs.json would change.")
    args = parser.parse_args()

    changed = sync_pattern_stubs(check=args.check)
    if args.check:
        print("doc/pattern_stubs.json is in sync.")
    elif changed:
        print("Synced doc/pattern_stubs.json from ManifestationServer.kt.")
    else:
        print("doc/pattern_stubs.json already matches ManifestationServer.kt.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())