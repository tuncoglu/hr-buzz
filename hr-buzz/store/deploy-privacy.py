#!/usr/bin/env python3
"""Publishes privacy-policy.md at https://hrbuzz.sourdoughcalculator.uk/privacy/ and
api-keys.md (how to get a key, and what it costs) at .../keys/.

    deploy-privacy.py render    write store/site/ and stop (look at it first)
    deploy-privacy.py deploy    render, then create/update the Cloudflare Pages project
                                "hr-buzz", attach the domain and its DNS record

A separate Pages project, so the sourdough site (deployed from its own repo) is never touched.
The Cloudflare token is read from ~/.cf-token inside this script and never printed. The account and
zone ids are not in the repository: put {"account_id": "...", "zone_id": "..."} in
~/.config/hrbuzz-deploy/cloudflare.json (mode 600), or set CF_ACCOUNT_ID and CF_ZONE_ID.
Standard library only; markdown is the one extra module (already installed).
"""
import base64
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

import markdown

HERE = os.path.dirname(os.path.abspath(__file__))
SITE = os.path.join(HERE, "site")
PROJECT = "hr-buzz"
HOST = "hrbuzz.sourdoughcalculator.uk"
API = "https://api.cloudflare.com/client/v4"

PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="robots" content="index,follow">
<style>
:root {{ --bg:#fff; --fg:#1b1b1f; --muted:#5c5c66; --line:#d8d8de; --accent:#c8102e; }}
@media (prefers-color-scheme: dark) {{
  :root {{ --bg:#0e0e10; --fg:#ececf1; --muted:#a0a0ac; --line:#34343c; --accent:#ff6b7f; }}
}}
body {{ background:var(--bg); color:var(--fg); margin:0;
  font:16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }}
main {{ max-width:46rem; margin:0 auto; padding:1.5rem 16px 4rem; }}
h1 {{ font-size:1.9rem; line-height:1.2; }}
h2 {{ font-size:1.25rem; margin-top:2.2rem; }}
a {{ color:var(--accent); }}
code {{ font-family:ui-monospace, monospace; font-size:.92em; }}
.tablewrap {{ overflow-x:auto; }}
table {{ border-collapse:collapse; width:100%; font-size:.95rem; }}
th, td {{ border:1px solid var(--line); padding:.5rem .6rem; text-align:left; vertical-align:top; }}
em {{ color:var(--muted); }}
.store-links {{ margin:1.5rem 0; }}
.store-label {{ margin:0 0 8px; color:var(--muted); font-size:13px; font-weight:700; letter-spacing:1px; text-transform:uppercase; }}
.store-buttons {{ display:flex; flex-wrap:wrap; gap:10px; }}
.store-button {{ display:inline-flex; flex-direction:column; align-items:flex-start; justify-content:center; gap:1px;
  min-height:48px; min-width:150px; padding:6px 16px; border:1px solid var(--line); border-radius:8px;
  background:var(--bg); color:var(--fg); font:inherit; text-decoration:none; text-align:left; cursor:pointer; }}
.store-button:hover {{ border-color:var(--fg); }}
.store-small {{ font-size:11px; color:var(--muted); line-height:1.2; }}
.store-name {{ font-size:17px; font-weight:700; letter-spacing:-.3px; line-height:1.2; }}
.store-dialog {{ max-width:min(460px, calc(100vw - 32px)); margin:auto; border:1px solid var(--line); border-radius:12px;
  background:var(--bg); color:var(--fg); padding:24px; }}
.store-dialog::backdrop {{ background:rgb(0 0 0 / .6); }}
.store-dialog h2 {{ font-size:20px; margin:0 0 10px; }}
.store-dialog p {{ margin:0; color:var(--muted); font-size:14px; line-height:1.55; }}
.store-close {{ margin-top:18px; min-height:44px; padding:0 18px; font:inherit; font-weight:700; border:0; border-radius:10px;
  background:var(--accent); color:#fff; cursor:pointer; }}
</style>
</head>
<body>
<main>
{body}
</main>
</body>
</html>
"""

INDEX = """<h1>HR Buzz</h1>
<p>A heart-rate training app for Bluetooth chest straps, published by ENFTC.</p>
<section class="store-links" aria-label="Get the app">
<p class="store-label">Get the app</p>
<div class="store-buttons">
<a class="store-button" href="https://play.google.com/store/apps/details?id=com.enftc.hrbuzz&amp;referrer=utm_source%3Dhrbuzz.sourdoughcalculator.uk%26utm_medium%3Dwebsite">
<span class="store-small">Get it on</span><span class="store-name">Google Play</span></a>
<button type="button" class="store-button" popovertarget="ios-soon">
<span class="store-small">Download on the</span><span class="store-name">App Store</span></button>
</div>
<div popover id="ios-soon" role="dialog" class="store-dialog" aria-labelledby="ios-soon-title">
<h2 id="ios-soon-title">Not on iPhone yet</h2>
<p>HR Buzz runs on Android phones and Wear OS watches for now. There is no iPhone version yet, and no web version you could add to your Home Screen.</p>
<button type="button" class="store-close" popovertarget="ios-soon" popovertargetaction="hide" autofocus>Got it</button>
</div>
</section>
<p><a href="/keys/">Getting an API key for the coach, and what it costs</a><br>
<a href="/privacy/">Privacy policy</a></p>
"""


def render():
    def page(md_name, title):
        with open(os.path.join(HERE, md_name), encoding="utf-8") as f:
            body = markdown.markdown(f.read(), extensions=["tables"])
        body = body.replace("<table>", '<div class="tablewrap"><table>').replace(
            "</table>", "</table></div>")
        return PAGE.format(title=title, body=body)

    files = {
        "privacy/index.html": page("privacy-policy.md", "HR Buzz privacy policy"),
        "keys/index.html": page("api-keys.md", "Getting an API key for HR Buzz"),
        "index.html": PAGE.format(title="HR Buzz", body=INDEX),
    }
    for path, text in files.items():
        full = os.path.join(SITE, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(text)
    print("rendered", ", ".join(sorted(files)), "into", SITE)


def cloudflare_id(env, key):
    """An account or zone id, from the environment or ~/.config/hrbuzz-deploy/cloudflare.json."""
    value = os.environ.get(env)
    if not value:
        path = os.path.expanduser("~/.config/hrbuzz-deploy/cloudflare.json")
        try:
            with open(path) as f:
                value = json.load(f).get(key)
        except (OSError, ValueError):
            value = None
    if not value:
        sys.exit("no Cloudflare %s: set %s or put it in ~/.config/hrbuzz-deploy/cloudflare.json"
                 % (key, env))
    return value


def token():
    with open(os.path.expanduser("~/.cf-token")) as f:
        return "".join(f.read().split())


def call(method, url, body=None, bearer=None, raw=None, content_type=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + (bearer or token()))
    if data is not None:
        req.add_header("Content-Type", content_type or "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            out = json.load(r)
    except urllib.error.HTTPError as e:
        out = json.loads(e.read().decode() or "{}")
    if not out.get("success", False):
        raise SystemExit("%s %s failed: %s" % (method, url.replace(API, ""), out.get("errors")))
    return out.get("result")


def asset_hash(b64, ext):
    # Wrangler uses the first 32 hex chars of blake3(base64 + extension). This is the same
    # shape from sha256, since the server only needs a stable key per file.
    return hashlib.sha256((b64 + ext).encode()).hexdigest()[:32]


def deploy():
    render()
    acct = "%s/accounts/%s" % (API, cloudflare_id("CF_ACCOUNT_ID", "account_id"))
    existing = {p["name"]: p for p in call("GET", acct + "/pages/projects")}
    if PROJECT not in existing:
        print("creating Pages project", PROJECT)
        existing[PROJECT] = call("POST", acct + "/pages/projects",
                                 {"name": PROJECT, "production_branch": "main"})
    pages_host = existing[PROJECT]["subdomain"]

    jwt = call("GET", "%s/pages/projects/%s/upload-token" % (acct, PROJECT))["jwt"]
    manifest, uploads = {}, []
    for root, _, names in os.walk(SITE):
        for n in names:
            full = os.path.join(root, n)
            rel = "/" + os.path.relpath(full, SITE)
            with open(full, "rb") as f:
                b64 = base64.b64encode(f.read()).decode()
            h = asset_hash(b64, os.path.splitext(n)[1].lstrip("."))
            manifest[rel] = h
            uploads.append({"key": h, "value": b64, "base64": True,
                            "metadata": {"contentType": "text/html; charset=utf-8"}})
    hashes = sorted(manifest.values())
    missing = call("POST", API + "/pages/assets/check-missing", {"hashes": hashes}, bearer=jwt)
    todo = [u for u in uploads if u["key"] in missing]
    if todo:
        call("POST", API + "/pages/assets/upload", todo, bearer=jwt)
    call("POST", API + "/pages/assets/upsert-hashes", {"hashes": hashes}, bearer=jwt)

    boundary = uuid.uuid4().hex
    parts = []
    for name, value in (("manifest", json.dumps(manifest)), ("branch", "main")):
        parts.append('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n%s\r\n'
                     % (boundary, name, value))
    raw = ("".join(parts) + "--%s--\r\n" % boundary).encode()
    dep = call("POST", "%s/pages/projects/%s/deployments" % (acct, PROJECT), raw=raw,
               content_type="multipart/form-data; boundary=" + boundary)
    print("deployment", dep["id"], dep.get("url"))

    domains = [d["name"] for d in call("GET", "%s/pages/projects/%s/domains" % (acct, PROJECT))]
    if HOST not in domains:
        call("POST", "%s/pages/projects/%s/domains" % (acct, PROJECT), {"name": HOST})
        print("attached", HOST)
    zone = cloudflare_id("CF_ZONE_ID", "zone_id")
    records = call("GET", "%s/zones/%s/dns_records?name=%s" % (API, zone, HOST))
    if not records:
        call("POST", "%s/zones/%s/dns_records" % (API, zone),
             {"type": "CNAME", "name": "hrbuzz", "content": pages_host, "proxied": True})
        print("added DNS CNAME", HOST, "->", pages_host)
    print("done: https://%s/privacy" % HOST)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "render":
        render()
    elif cmd == "deploy":
        deploy()
    else:
        raise SystemExit(__doc__)
