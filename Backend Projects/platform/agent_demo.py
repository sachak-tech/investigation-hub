#!/usr/bin/env python3
"""Prepare supplied synthetic documents for starting/reviewing an agent in the development UI."""
import json
from pathlib import Path
import uuid
from smoke import request, URLS
from agent_scenario import prepare, FULL_TEXT


def main():
    identifier = uuid.uuid4().hex[:10]
    tenant = "demo-agent-ui-" + identifier
    policy = json.loads((Path(__file__).parent / "examples/bank-document-agent.v1.json").read_text())
    policy["tenantId"] = tenant
    request("policy", "/v1/policies/drafts", "POST", policy)
    request("policy", "/v1/policies/drafts/bank-document-agent-policy/versions/1/publish?tenantId=" + tenant, "POST")
    case, view, document = prepare(tenant, FULL_TEXT, "ui-customer-" + identifier)
    output = {"caseId": case["caseId"], "tenantId": tenant, "documentId": document["documentId"],
              "reviewUrl": URLS["playbook"] + "/step-workspace.html?caseId=" + case["caseId"] + "&stepId=bank_document_review"}
    (Path(__file__).resolve().parents[2] / ".cache/agent-demo.json").write_text(json.dumps(output, indent=2) + "\n")
    print(json.dumps(output, indent=2))


if __name__ == "__main__": main()
