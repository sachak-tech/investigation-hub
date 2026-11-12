#!/usr/bin/env python3
"""Validate Milestone 0 JSON contracts and cross-references."""

import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[1]
PAIRS = (
    ("policy", "policy"),
    ("command", "command"),
    ("domain-event", "domain-event"),
    ("step-destination", "step-destination"),
    ("external-section", "external-section"),
    ("task-completion", "task-completion"),
    ("agent-analysis", "agent-analysis"),
)


def read(relative_path):
    return json.loads((ROOT / relative_path).read_text(encoding="utf-8"))


def main():
    examples = {}
    for schema_name, example_name in PAIRS:
        schema = read("contracts/{}.schema.json".format(schema_name))
        example = read("examples/{}.v1.json".format(example_name))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(example)
        examples[example_name] = example
        print("PASS {}".format(example_name))

    policy = examples["policy"]
    destination = examples["step-destination"]
    section = examples["external-section"]
    case_types = {item["id"] for item in policy["caseTypes"]}
    stages = {item["id"] for item in policy["reviewStages"]}
    assert destination["destinationId"] in {item["destinationId"] for item in policy["steps"]}
    assert destination["externalSectionRef"] == section["sectionId"]
    assert policy["closureRules"]["humanApprovalStage"] in stages
    assert set(policy["agentAuthority"]["allowedCaseTypes"]) <= case_types
    assert set(policy["closureRules"]["autonomousCaseTypes"]) <= case_types
    for step in policy["steps"]:
        assert set(step["activation"]["caseTypes"]) <= case_types
    for stage in policy["reviewStages"]:
        assert set(stage["nextStages"]) <= stages
    print("PASS cross-references")


if __name__ == "__main__":
    main()
