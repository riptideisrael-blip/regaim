#!/usr/bin/env python3
"""
Puts the new APK in the app's private updates folder (Supabase storage 'app-updates'), so "Check for a new version"
in the app's settings finds it. Runs in the GitHub build after the APK is built.

The server key: the SUPABASE_SERVICE_ROLE_KEY secret if set, otherwise it's fetched with the SUPABASE_ACCESS_TOKEN
secret (the one the server-deploy workflow already uses). Neither one set: nothing is published (the build still
succeeds). Only the newest version is kept on the server (the free plan has 1 GB).

usage: publish_update.py APK VERSION_CODE VERSION_NAME
"""
import hashlib, json, os, sys, urllib.request, urllib.error

REF = "acqgmcmvxijyijhgivnt"
BASE = f"https://{REF}.supabase.co/storage/v1"
BUCKET = "app-updates"
PART = 45 * 1024 * 1024  # one file on the free plan: at most 50 MB
UA = {"User-Agent": "moments-build/1.0"}


def server_key():
    key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "").strip()
    tok = os.environ.get("SUPABASE_ACCESS_TOKEN", "").strip()
    if not key and tok:
        keys = None
        for url in (f"https://api.supabase.com/v1/projects/{REF}/api-keys?reveal=true", f"https://api.supabase.com/v1/projects/{REF}/api-keys"):
            req = urllib.request.Request(url, headers={"Authorization": "Bearer " + tok, "Accept": "application/json", **UA})
            try:
                keys = json.load(urllib.request.urlopen(req, timeout=60))
                break
            except urllib.error.HTTPError as e:
                # Said plainly (never the token itself): 401 = the token is wrong or expired, 403 = refused.
                print(f"::warning::Supabase answered {e.code} for the project's keys: {e.read()[:300]!r}")
        if keys is None:
            # The same request through curl (Supabase's firewall sometimes turns Python's requests away with 403).
            import subprocess
            for url in (f"https://api.supabase.com/v1/projects/{REF}/api-keys?reveal=true", f"https://api.supabase.com/v1/projects/{REF}/api-keys"):
                r = subprocess.run(["curl", "-sS", "-w", "\n%{http_code}", "-H", "Authorization: Bearer " + tok, "-H", "Accept: application/json", url],
                                   capture_output=True, text=True)
                body, _, code = r.stdout.rpartition("\n")
                if code.strip() == "200":
                    keys = json.loads(body)
                    print("got the project's keys (through curl)")
                    break
                print(f"::warning::curl: Supabase answered {code.strip()}: {body[:300]!r}")
        if keys is None:
            raise SystemExit("Couldn't get the server key with SUPABASE_ACCESS_TOKEN (see the warning above). "
                             "Fix: make a new token at supabase.com > Account > Access Tokens and put it in the GitHub secret.")
        for k in keys:
            if k.get("name") == "service_role" and k.get("api_key"):
                key = k["api_key"]
                break
        else:
            for k in keys:
                if k.get("type") == "secret" and k.get("api_key"):
                    key = k["api_key"]
                    break
    return key


def call(key, method, path, data=None, ctype="application/json", extra=None):
    headers = {"apikey": key, **UA, **(extra or {})}
    if not key.startswith("sb_secret_"):
        headers["Authorization"] = "Bearer " + key
    if data is not None:
        headers["Content-Type"] = ctype
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            body = r.read()
            return json.loads(body) if body[:1] in (b"[", b"{") else body
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path}: {e.code} {e.read()[:300]!r}")


def main():
    apk, code, name = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    # The number the build really gave the app (it grows with time; the one passed in is the old way).
    kept = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "build", "version-code.txt")
    if os.path.exists(kept):
        code = int(open(kept).read().strip())
    kept_name = os.path.join(os.path.dirname(kept), "version-name.txt")
    if os.path.exists(kept_name):
        name = open(kept_name, encoding="utf-8").read().strip()
    key = server_key()
    if not key:
        print("::warning::No SUPABASE_ACCESS_TOKEN / SUPABASE_SERVICE_ROLE_KEY secret: the update wasn't published to the app.")
        return 0
    # First the APKs get Moments' own signing key (sign_release.py). If that fails, nothing is published: an update
    # signed the old way could no longer install over a phone that already has the new key.
    import sign_release
    sign_release.main_for([apk, os.path.join(os.path.dirname(os.path.abspath(apk)), "regaim-protected.apk")])
    print(f"::add-mask::{key}")

    def publish(path, folder, note_name):
        blob = open(path, "rb").read()
        parts = []
        for i in range(0, len(blob), PART):
            p = f"{folder}/moments.apk.{i // PART:03d}"
            call(key, "POST", f"/object/{BUCKET}/{p}", blob[i:i + PART], "application/octet-stream", {"x-upsert": "true"})
            parts.append(p)
            print("uploaded", p)
        note = {"code": code, "name": name, "parts": parts, "size": len(blob), "sha256": hashlib.sha256(blob).hexdigest()}
        # The note last: the app only sees a version once all its parts are there.
        call(key, "POST", f"/object/{BUCKET}/{note_name}", json.dumps(note).encode(), "application/json",
             {"x-upsert": "true", "cache-control": "no-cache"})
        print("published", note_name, note["name"], len(blob), "bytes")

    folder = f"v{code}"
    publish(apk, folder, "latest.json")
    # The protected build (R8) as a test channel the admin can turn on in the app (never the families' default).
    beta = f"b{code}"
    protected = os.path.join(os.path.dirname(os.path.abspath(apk)), "regaim-protected.apk")
    if os.path.exists(protected):
        publish(protected, beta, "latest-beta.json")
    # Older versions are removed (only the newest is kept).
    for entry in call(key, "POST", f"/object/list/{BUCKET}", json.dumps({"prefix": "", "limit": 1000}).encode()):
        old = entry.get("name", "")
        if entry.get("id") is None and old[:1] in ("v", "b") and old[1:].isdigit() and old not in (folder, beta):
            files = call(key, "POST", f"/object/list/{BUCKET}", json.dumps({"prefix": old, "limit": 1000}).encode())
            names = [f"{old}/{f['name']}" for f in files if f.get("name")]
            if names:
                call(key, "DELETE", f"/object/{BUCKET}", json.dumps({"prefixes": names}).encode())
                print("removed", old)
    return 0


if __name__ == "__main__":
    sys.exit(main())
