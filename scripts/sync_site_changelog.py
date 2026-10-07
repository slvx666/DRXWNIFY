"""Copies the in-app patch notes into website/releases.json.

The app is the source of truth: versions and dates come from ChangelogScreen.kt,
the text comes from the Russian string resources. Run after adding a release:

    python scripts/sync_site_changelog.py
"""
import html
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCREEN = ROOT / "app/src/main/kotlin/com/metrolist/music/ui/screens/settings/ChangelogScreen.kt"
STRINGS = ROOT / "app/src/main/res/values-ru/metrolist_strings.xml"
OUT = ROOT / "website/releases.json"

RELEASE_RE = re.compile(
    r'ReleaseInfo\(\s*tagName\s*=\s*"([^"]+)".*?R\.string\.(changelog_\w+).*?releaseDate\s*=\s*"([^"]+)"',
    re.S,
)


def android_unescape(text: str) -> str:
    text = html.unescape(text)
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), text)


def main() -> None:
    strings_xml = STRINGS.read_text(encoding="utf-8")
    releases = []
    for tag, key, date in RELEASE_RE.findall(SCREEN.read_text(encoding="utf-8")):
        match = re.search(rf'<string name="{key}">(.*?)</string>', strings_xml, re.S)
        if not match:
            raise SystemExit(f"{key} not found in {STRINGS.name}")
        releases.append({"version": tag, "date": date, "notes": android_unescape(match.group(1)).strip()})

    payload = {"latest": releases[0]["version"], "releases": releases}
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{OUT.relative_to(ROOT)}: {len(releases)} releases, latest {payload['latest']}")


if __name__ == "__main__":
    main()
