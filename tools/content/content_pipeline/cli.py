"""Command-line entry point for the content pipeline: discover, import,
validate, build, and publish stages, matching docs/CONTENT.md's runbook."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .adapters.fleurs import FleursAdapter
from .adapters.kencorpus import KenCorpusAdapter
from .adapters.common_voice import CommonVoiceAdapter
from .adapters.tatoeba import TatoebaAdapter
from .limits import DEFAULT_SMOKE_ITEM_LIMIT
from .registry import SourceRegistry
from .schemas import get_catalog, SchemaDependencyMissing

ADAPTERS = {
    "fleurs": FleursAdapter,
    "kencorpus": KenCorpusAdapter,
    "common_voice": CommonVoiceAdapter,
    "tatoeba": TatoebaAdapter,
}


def cmd_discover(args: argparse.Namespace) -> int:
    adapter_cls = ADAPTERS[args.source]
    adapter = adapter_cls() if args.source != "common_voice" else adapter_cls(
        release_dir=Path(args.release_dir) if args.release_dir else None
    )
    result = adapter.discover(language=args.language) if args.language else adapter.discover()
    print(json.dumps(result.__dict__, indent=2, default=str))
    return 0 if result.available else 1


def cmd_import(args: argparse.Namespace) -> int:
    from .schemas import repo_root
    adapter_cls = ADAPTERS[args.source]
    adapter = adapter_cls() if args.source != "common_voice" else adapter_cls(
        release_dir=Path(args.release_dir) if args.release_dir else None
    )
    report = adapter.import_candidates(language=args.language, limit=args.limit)
    out_dir = Path(args.out_dir) if args.out_dir else repo_root() / "tools" / "content" / "build" / "reports"
    path = report.write(out_dir)
    print("Wrote report to " + str(path))
    print(json.dumps(report.summary(), indent=2, default=str))
    return 0 if report.is_ok_to_proceed() else 1


def cmd_validate_registry(args: argparse.Namespace) -> int:
    registry = SourceRegistry.load()
    try:
        errors = registry.validate_against_schema()
    except SchemaDependencyMissing as e:
        print("MISSING DEPENDENCY: " + str(e), file=sys.stderr)
        return 2
    if errors:
        for e in errors:
            print("ERROR: " + e, file=sys.stderr)
        return 1
    print("Registry OK.")
    return 0


def cmd_validate_fixtures(args: argparse.Namespace) -> int:
    from .schemas import repo_root
    try:
        catalog = get_catalog()
    except SchemaDependencyMissing as e:
        print("MISSING DEPENDENCY: " + str(e), file=sys.stderr)
        return 2
    fixtures_dir = repo_root() / "content" / "fixtures"
    failures = 0
    checked = 0
    for kind in ("valid", "invalid"):
        for path in sorted((fixtures_dir / kind).glob("*.json")):
            checked += 1
            schema_filename = path.stem + ".schema.json"
            try:
                with open(path, "r", encoding="utf-8") as f:
                    instance = json.load(f)
                errors = catalog.validate(schema_filename, instance)
            except KeyError:
                print("SKIP (no matching schema): " + str(path))
                continue
            is_valid = len(errors) == 0
            expected_valid = kind == "valid"
            status = "OK" if is_valid == expected_valid else "MISMATCH"
            if status == "MISMATCH":
                failures += 1
            print(status + ": " + str(path) + (" errors=" + str(errors) if errors else ""))
    if checked == 0:
        print("ERROR: no fixture files found under " + str(fixtures_dir), file=sys.stderr)
        return 2
    return 1 if failures else 0


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="content-pipeline")
    sub = parser.add_subparsers(dest="command", required=True)

    p_discover = sub.add_parser("discover", help="Metadata-first source discovery.")
    p_discover.add_argument("source", choices=list(ADAPTERS.keys()))
    p_discover.add_argument("--language", default=None)
    p_discover.add_argument("--release-dir", default=None)
    p_discover.set_defaults(func=cmd_discover)

    p_import = sub.add_parser("import", help="Bounded candidate import.")
    p_import.add_argument("source", choices=list(ADAPTERS.keys()))
    p_import.add_argument("--language", required=True)
    p_import.add_argument("--limit", type=int, default=DEFAULT_SMOKE_ITEM_LIMIT)
    p_import.add_argument("--out-dir", default=None)
    p_import.add_argument("--release-dir", default=None)
    p_import.set_defaults(func=cmd_import)

    p_validate_registry = sub.add_parser("validate-registry", help="Validate content/sources/registry.json.")
    p_validate_registry.set_defaults(func=cmd_validate_registry)

    p_validate_fixtures = sub.add_parser("validate-fixtures", help="Validate content/fixtures/** against schemas.")
    p_validate_fixtures.set_defaults(func=cmd_validate_fixtures)

    return parser


def main(argv=None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
