#!/usr/bin/env python3
"""Synthetic Python external worker for the Milestone 0 Flowable spike."""

import json
import os
import urllib.request


BASE_URL = os.environ.get("SPIKE_BASE_URL", "http://127.0.0.1:18080/spike")
WORKER_ID = "synthetic-python-worker"


def request(path, method="GET", payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {} if data is None else {"Content-Type": "application/json"}
    req = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=10) as response:
        content = response.read()
        return json.loads(content) if content else None


def main():
    jobs = request("/workers/acquire?workerId=" + WORKER_ID)
    if not jobs:
        raise SystemExit("No Flowable external-worker job available")
    for job in jobs:
        finding = "Synthetic transaction analysis completed by Python worker"
        request("/workers/" + job["id"] + "/complete", "POST", {"workerId": WORKER_ID, "finding": finding})
        print(json.dumps({"jobId": job["id"], "finding": finding}))


if __name__ == "__main__":
    main()

