#!/usr/bin/env python3
"""Check comment-based scenario links without executing or rewriting features."""

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


CASE_ID = re.compile(r"# case-id: ([A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+)$")
SCENARIO = re.compile(r"(?:Scenario(?: Outline)?|Example):\s*(.*)$")


@dataclass(frozen=True)
class Case:
    path: Path
    line: int
    title: str

    def location(self, root):
        return f"{self.path.relative_to(root)}:{self.line}"


def collect_cases(directory, errors):
    cases = {}
    files = sorted(
        path for path in directory.rglob("*.feature")
        if "support" not in path.relative_to(directory).parts
    )
    if not files:
        errors.append(f"{directory}: no feature files found")

    for path in files:
        pending = None
        doc_string = None
        scenario_count = 0
        for number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            line = raw_line.strip()
            # Karate permits multiline JSON / JavaScript; text inside is not Gherkin structure.
            if doc_string:
                if line == doc_string:
                    doc_string = None
                continue
            if line.startswith(('"""', "```")):
                if pending:
                    errors.append(f"{path}:{pending[1]}: case ID must precede a Scenario")
                    pending = None
                doc_string = line[:3]
                continue
            if line.startswith("# case-id:"):
                if pending:
                    errors.append(f"{path}:{pending[1]}: case ID has no Scenario")
                match = CASE_ID.fullmatch(line)
                pending = (match[1], number) if match else None
                if not match:
                    errors.append(f"{path}:{number}: invalid case ID comment")
                continue
            if not line or line.startswith(("#", "@")):
                continue
            scenario = SCENARIO.fullmatch(line)
            if scenario:
                scenario_count += 1
                if not pending:
                    errors.append(f"{path}:{number}: Scenario is missing a case ID")
                else:
                    case_id, _ = pending
                    if case_id in cases:
                        previous = cases[case_id]
                        errors.append(
                            f"{path}:{number}: duplicate {case_id}; "
                            f"already at {previous.path}:{previous.line}"
                        )
                    else:
                        cases[case_id] = Case(path, number, scenario[1])
                pending = None
            elif pending:
                errors.append(f"{path}:{pending[1]}: case ID must precede a Scenario")
                pending = None
        if pending:
            errors.append(f"{path}:{pending[1]}: case ID has no Scenario")
        if not scenario_count:
            errors.append(f"{path}: no scenarios found outside support/")
    return cases


def check(root):
    errors = []
    business = collect_cases(root / "e2e/biz-bdd/features", errors)
    executable = collect_cases(root / "e2e/spec/features", errors)
    for case_id in sorted(business.keys() - executable.keys()):
        errors.append(f"{case_id}: missing Karate counterpart ({business[case_id].location(root)})")
    for case_id in sorted(executable.keys() - business.keys()):
        errors.append(f"{case_id}: missing business counterpart ({executable[case_id].location(root)})")
    for case_id in sorted(business.keys() & executable.keys()):
        if business[case_id].title != executable[case_id].title:
            errors.append(
                f"{case_id}: Scenario titles differ\n"
                f"  {business[case_id].location(root)}: {business[case_id].title}\n"
                f"  {executable[case_id].location(root)}: {executable[case_id].title}"
            )
    return business, executable, errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="list both source locations for every case ID")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    business, executable, errors = check(root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    if args.list:
        for case_id, case in sorted(business.items()):
            print(f"{case_id}  {case.title}")
            print(f"  BDD    {case.location(root)}")
            print(f"  Karate {executable[case_id].location(root)}")
    print(f"PASS: {len(business)} scenarios have unique, matching BDD / Karate case IDs and titles.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
