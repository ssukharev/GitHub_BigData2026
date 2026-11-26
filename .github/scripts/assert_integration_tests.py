"""Reject a successful build that did not execute its required database tests."""

import argparse
import glob
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--reports", required=True, help="JUnit XML glob, relative to the repository"
    )
    parser.add_argument("--class-suffix", help="Select test classes ending with this value")
    parser.add_argument("--name-prefix", help="Select test names starting with this value")
    parser.add_argument(
        "--minimum", type=int, default=1, help="Minimum number of matching test cases"
    )
    args = parser.parse_args()
    if args.minimum < 1:
        parser.error("--minimum must be positive")

    reports = sorted(glob.glob(args.reports, recursive=True))
    if not reports:
        print(f"No JUnit reports matched {args.reports!r}", file=sys.stderr)
        return 1

    cases = []
    try:
        for report in reports:
            for case in ET.parse(report).iter("testcase"):
                if args.class_suffix and not case.get("classname", "").endswith(args.class_suffix):
                    continue
                if args.name_prefix and not case.get("name", "").startswith(args.name_prefix):
                    continue
                cases.append(case)
    except (ET.ParseError, OSError) as error:
        print(f"Cannot read JUnit report: {error}", file=sys.stderr)
        return 1

    if len(cases) < args.minimum:
        print(
            f"Expected at least {args.minimum} database tests, found {len(cases)}", file=sys.stderr
        )
        return 1
    incomplete = [
        case
        for case in cases
        if any(case.find(status) is not None for status in ("skipped", "failure", "error"))
    ]
    if incomplete:
        print("Required database tests did not pass:", file=sys.stderr)
        for case in incomplete:
            print(f"  {case.get('classname')}.{case.get('name')}", file=sys.stderr)
        return 1

    print(f"Verified {len(cases)} executed database tests with no skips or failures")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
