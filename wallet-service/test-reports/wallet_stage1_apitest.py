#!/usr/bin/env python3
"""
Right-sized integration test for wallet-service Stage 1 (live API on :8080).
CHIPS coverage: Contract / Happy / Invariants / Permutations / Side-effects.
No auth/Kafka/Redis yet (not in scope at Stage 1). Pure stdlib (urllib) — no deps.
Writes an HTML report next to this file.
"""
import json
import time
import urllib.request
import urllib.error
import html
import os

BASE = "http://localhost:8080"
RESULTS = []


def call(method, path, body=None):
    """Return (status, parsed_json_or_text, raw_text)."""
    url = BASE + path
    data = None
    headers = {}
    if body is not None:
        data = body.encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode("utf-8")
            status = resp.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        status = e.code
    try:
        parsed = json.loads(raw) if raw else None
    except json.JSONDecodeError:
        parsed = None
    return status, parsed, raw


def record(case_id, name, chips, method, path, req_body,
           expect_status, status, body, raw, checks):
    passed = (status == expect_status) and all(ok for _, ok in checks)
    RESULTS.append({
        "id": case_id, "name": name, "chips": chips,
        "method": method, "path": path, "req_body": req_body,
        "expect_status": expect_status, "status": status,
        "raw": raw, "checks": checks, "passed": passed,
    })
    flag = "PASS" if passed else "FAIL"
    print(f"[{flag}] {case_id} {name} -> {status} (expected {expect_status})")


def run():
    # ---- C/H: create a wallet (happy path) ----
    st, body, raw = call("POST", "/wallets", '{"ownerName":"Alice"}')
    created_id = body.get("id") if isinstance(body, dict) else None
    checks = [
        ("body has id", isinstance(body, dict) and "id" in body),
        ("ownerName == Alice", isinstance(body, dict) and body.get("ownerName") == "Alice"),
        ("balance == 0 (invariant)", isinstance(body, dict) and float(body.get("balance", -1)) == 0.0),
    ]
    record("C1", "POST /wallets creates wallet", "C/H/I",
           "POST", "/wallets", '{"ownerName":"Alice"}', 201, st, body, raw, checks)

    # ---- H/S: get it back (persistence across requests) ----
    if created_id is not None:
        st, body, raw = call("GET", f"/wallets/{created_id}")
        checks = [
            ("same id returned", isinstance(body, dict) and body.get("id") == created_id),
            ("ownerName persisted", isinstance(body, dict) and body.get("ownerName") == "Alice"),
            ("balance persisted == 0", isinstance(body, dict) and float(body.get("balance", -1)) == 0.0),
        ]
        record("C2", "GET returns the created wallet (side-effect: persisted)", "H/S",
               "GET", f"/wallets/{created_id}", None, 200, st, body, raw, checks)

    # ---- I: ids are distinct / auto-incremented ----
    st1, b1, _ = call("POST", "/wallets", '{"ownerName":"Bob"}')
    st2, b2, _ = call("POST", "/wallets", '{"ownerName":"Carol"}')
    id1 = b1.get("id") if isinstance(b1, dict) else None
    id2 = b2.get("id") if isinstance(b2, dict) else None
    checks = [
        ("two creates succeed", st1 == 201 and st2 == 201),
        ("ids are distinct", id1 is not None and id2 is not None and id1 != id2),
    ]
    record("I1", "Each wallet gets a distinct id", "I",
           "POST", "/wallets x2", '{"ownerName":"Bob"} / {"ownerName":"Carol"}',
           201, st2, b2, f"id1={id1} id2={id2}", checks)

    # ---- P: empty ownerName -> 400 (validation) ----
    st, body, raw = call("POST", "/wallets", '{"ownerName":""}')
    checks = [("rejected with 400", st == 400)]
    record("P1", "Empty ownerName rejected", "P",
           "POST", "/wallets", '{"ownerName":""}', 400, st, body, raw, checks)

    # ---- P: malformed JSON -> 400 ----
    st, body, raw = call("POST", "/wallets", '{ownerName:}')
    checks = [("malformed JSON rejected with 400", st == 400)]
    record("P2", "Malformed JSON rejected", "P",
           "POST", "/wallets", '{ownerName:}', 400, st, body, raw, checks)

    # ---- P: missing body -> 400 ----
    st, body, raw = call("POST", "/wallets", None)
    checks = [("missing body rejected with 400", st == 400)]
    record("P3", "Missing body rejected", "P",
           "POST", "/wallets", "(none)", 400, st, body, raw, checks)

    # ---- P: get non-existent -> 404 with error message ----
    st, body, raw = call("GET", "/wallets/999999")
    checks = [
        ("status 404", st == 404),
        ("error message present", isinstance(body, dict) and "error" in body),
    ]
    record("P4", "GET missing wallet returns 404", "P",
           "GET", "/wallets/999999", None, 404, st, body, raw, checks)

    # ---- P: non-numeric id -> 400 (type mismatch) ----
    st, body, raw = call("GET", "/wallets/abc")
    checks = [("non-numeric id rejected with 400", st == 400)]
    record("P5", "GET with non-numeric id returns 400", "P",
           "GET", "/wallets/abc", None, 400, st, body, raw, checks)


def render_html(path):
    total = len(RESULTS)
    passed = sum(1 for r in RESULTS if r["passed"])
    failed = total - passed
    rows = []
    for r in RESULTS:
        checks_html = "<br>".join(
            f"{'✅' if ok else '❌'} {html.escape(desc)}" for desc, ok in r["checks"])
        badge = ("#1f9d55", "PASS") if r["passed"] else ("#e3342f", "FAIL")
        rows.append(f"""
        <tr>
          <td><b>{r['id']}</b></td>
          <td>{html.escape(r['name'])}<div class="chips">CHIPS: {r['chips']}</div></td>
          <td><code>{r['method']} {html.escape(r['path'])}</code>
              <div class="req">{html.escape(str(r['req_body']))}</div></td>
          <td>{r['status']} <span class="muted">/ exp {r['expect_status']}</span></td>
          <td>{checks_html}</td>
          <td><span style="background:{badge[0]}" class="badge">{badge[1]}</span></td>
        </tr>
        <tr class="raw"><td colspan="6"><pre>{html.escape((r['raw'] or '')[:400])}</pre></td></tr>""")
    status_color = "#1f9d55" if failed == 0 else "#e3342f"
    doc = f"""<!DOCTYPE html><html lang="vi"><head><meta charset="UTF-8">
<title>Wallet Stage 1 — API Test Report</title>
<style>
body{{font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;background:#0f1117;color:#e6e6e6;margin:0;padding:32px}}
.wrap{{max-width:1000px;margin:0 auto}}
h1{{font-size:26px}}
.cards{{display:flex;gap:16px;margin:20px 0}}
.card{{background:#171a21;border:1px solid #2a2f3a;border-radius:12px;padding:18px 24px;flex:1;text-align:center}}
.card .n{{font-size:32px;font-weight:700}}
table{{width:100%;border-collapse:collapse;background:#171a21;border-radius:12px;overflow:hidden}}
th,td{{padding:10px 12px;border-bottom:1px solid #2a2f3a;text-align:left;vertical-align:top;font-size:14px}}
th{{background:#1f242e}}
code{{background:#0b0d13;padding:2px 6px;border-radius:5px;color:#5be7c4;font-size:13px}}
.badge{{color:#fff;padding:3px 10px;border-radius:6px;font-size:12px;font-weight:700}}
.chips{{color:#9aa4b2;font-size:11px;margin-top:4px}}
.req{{color:#9aa4b2;font-size:12px;margin-top:4px;font-family:monospace}}
.muted{{color:#9aa4b2;font-size:12px}}
.raw pre{{background:#0b0d13;border:1px solid #2a2f3a;border-radius:8px;padding:10px;color:#c8d3e6;font-size:12px;overflow-x:auto;margin:0}}
.raw td{{padding-top:0;border-bottom:2px solid #2a2f3a}}
</style></head><body><div class="wrap">
<h1>🧪 Wallet Stage 1 — API Test Report</h1>
<p class="muted">Right-sized integration test · live API :8080 · CHIPS coverage · no auth/Kafka/Redis (out of scope at Stage 1)</p>
<div class="cards">
  <div class="card"><div class="n">{total}</div>Tổng ca</div>
  <div class="card"><div class="n" style="color:#1f9d55">{passed}</div>PASS</div>
  <div class="card"><div class="n" style="color:#e3342f">{failed}</div>FAIL</div>
  <div class="card"><div class="n" style="color:{status_color}">{round(passed/total*100) if total else 0}%</div>Tỉ lệ</div>
</div>
<table>
<tr><th>ID</th><th>Tên ca</th><th>Request</th><th>Status</th><th>Kiểm tra</th><th>KQ</th></tr>
{''.join(rows)}
</table>
<p class="muted" style="margin-top:24px">Sinh tự động · wallet-service Stage 1 · 2026-06-10</p>
</div></body></html>"""
    with open(path, "w") as f:
        f.write(doc)


if __name__ == "__main__":
    run()
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wallet-stage1-report.html")
    render_html(out)
    total = len(RESULTS)
    passed = sum(1 for r in RESULTS if r["passed"])
    print(f"\n=== {passed}/{total} PASS ===")
    print(f"Report: {out}")
