"""Run against a fresh or existing BankCore demo instance."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

BASE = os.environ.get("BANKCORE_URL", "http://127.0.0.1:8080")
ALICE = os.environ.get("ALICE_TOKEN", "demo-alice-local")
BOB = os.environ.get("BOB_TOKEN", "demo-bob-local")
AUDITOR = os.environ.get("AUDITOR_TOKEN", "demo-auditor-local")


def call(method, path, token, status=200, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if key:
        headers["Idempotency-Key"] = key
    data = None if body is None else json.dumps(body).encode()
    try:
        response = urlopen(Request(BASE + path, data, headers, method=method), timeout=20)
    except HTTPError as error:
        response = error
    with response:
        assert response.status == status, (path, response.status, response.read())
        return json.load(response)


def transfer(source, target, amount, token, key):
    body = {"sourceId": source, "targetId": target, "amount": amount}
    try:
        return 201, call("POST", "/transfers", token, 201, body, key)
    except AssertionError as error:
        path, status, data = error.args[0]
        assert status in (200, 422), (path, status, data)
        return status, json.loads(data)


def main():
    call("GET", "/audit", None, 401)
    call("GET", "/audit", ALICE, 403)
    alice = call("POST", "/accounts", ALICE, 201, {"kind": "SAVINGS"})["id"]
    bob = call("POST", "/accounts", BOB, 201, {"kind": "CURRENT"})["id"]
    call("GET", f"/accounts/{alice}", BOB, 404)
    call("POST", f"/accounts/{alice}/deposits", ALICE, 201, {"amount": 1000}, str(uuid4()))

    key = str(uuid4())
    with ThreadPoolExecutor(max_workers=2) as pool:
        submits = [pool.submit(transfer, alice, bob, 100, ALICE, key) for _ in range(2)]
        results = [future.result() for future in submits]
        assert sorted(status for status, _ in results) == [200, 201]
        assert results[0][1]["movement"] == results[1][1]["movement"]
    call("POST", "/transfers", ALICE, 409,
         {"sourceId": alice, "targetId": bob, "amount": 101}, key)

    with ThreadPoolExecutor(max_workers=2) as pool:
        submits = [pool.submit(transfer, alice, bob, 600, ALICE, str(uuid4())) for _ in range(2)]
        assert sorted(future.result()[0] for future in submits) == [201, 422]
    assert call("GET", f"/accounts/{alice}", ALICE)["balance"] == 300
    assert call("GET", f"/accounts/{bob}", BOB)["balance"] == 700

    with ThreadPoolExecutor(max_workers=2) as pool:
        forward = pool.submit(transfer, alice, bob, 100, ALICE, str(uuid4()))
        reverse = pool.submit(transfer, bob, alice, 100, BOB, str(uuid4()))
        assert forward.result()[0] == 201
        status, movement = reverse.result()
        assert status == 201 and movement["movement"]["fee"] == 25
    assert call("GET", f"/accounts/{alice}", ALICE)["balance"] == 300
    assert call("GET", f"/accounts/{bob}", BOB)["balance"] == 675

    call("POST", f"/accounts/{bob}/withdrawals", BOB, 201, {"amount": 75}, str(uuid4()))
    assert call("GET", f"/accounts/{bob}", BOB)["balance"] == 600
    assert len(call("GET", f"/accounts/{alice}/history", ALICE)) == 5
    assert any(row["action"] == "TRANSFER" for row in call("GET", "/audit", AUDITOR))
    full = call("POST", "/accounts", BOB, 201, {"kind": "CURRENT"})["id"]
    call("POST", f"/accounts/{full}/deposits", BOB, 201, {"amount": 2**63 - 1}, str(uuid4()))
    call("POST", "/transfers", ALICE, 422,
         {"sourceId": alice, "targetId": full, "amount": 1}, str(uuid4()))
    assert call("GET", f"/accounts/{alice}", ALICE)["balance"] == 300
    assert call("GET", f"/accounts/{full}", BOB)["balance"] == 2**63 - 1
    assert len(call("GET", f"/accounts/{alice}/history", ALICE)) == 5
    print("Authentication, audit, concurrent transfers, idempotent replay and rollback passed.")


if __name__ == "__main__":
    main()
