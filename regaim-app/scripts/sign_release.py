#!/usr/bin/env python3
"""
Signs the built APKs with Moments' own signing key, rotated from the old key (the old one was in the public
repository, so anyone could have signed an app that installs over Moments). Runs in the GitHub build, before the
APK is published.

Key rotation (APK signature scheme v3): the APK carries "old key -> new key", so phones that have the old-signed app
update to it normally (nothing is lost), and from then on (Android 9 and up) an app signed only with the old key is
refused as an update.

The new key is made once, here, and kept in a private Supabase folder ('build-secrets', no rules: only the server's
own key reads it) - never in the repository and never shown. Uses the same server key as publish_update.py.

usage: sign_release.py APK [APK...]      exit 0 = signed (or no server key: left as it was, with a warning)
"""
import glob, json, os, secrets, subprocess, sys, tempfile, urllib.request, urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import publish_update as pu  # noqa: E402

BUCKET = "build-secrets"
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OLD = dict(ks=os.path.join(ROOT, "app", "regaim-debug.keystore"), alias="regaim", password="regaim123")


def tool(name):
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    found = sorted(glob.glob(os.path.join(home, "build-tools", "*", name)))
    if not found:
        raise SystemExit(f"{name} not found (ANDROID_HOME={home!r})")
    return found[-1]


def get(key, path):
    """The file's bytes, or None when it isn't there."""
    req = urllib.request.Request(f"{pu.BASE}/object/{BUCKET}/{path}", headers=headers(key))
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        if e.code in (400, 404):
            return None
        raise SystemExit(f"GET {path}: {e.code}")


def headers(key, extra=None):
    h = {"apikey": key, **pu.UA, **(extra or {})}
    if not key.startswith("sb_secret_"):
        h["Authorization"] = "Bearer " + key
    return h


def put(key, path, data):
    req = urllib.request.Request(f"{pu.BASE}/object/{BUCKET}/{path}", data=data, method="POST",
                                 headers=headers(key, {"Content-Type": "application/octet-stream"}))
    try:
        urllib.request.urlopen(req, timeout=120).read()
    except urllib.error.HTTPError as e:
        raise SystemExit(f"POST {path}: {e.code} {e.read()[:200]!r}")


def ensure_bucket(key):
    req = urllib.request.Request(f"{pu.BASE}/bucket/{BUCKET}", headers=headers(key))
    try:
        info = json.load(urllib.request.urlopen(req, timeout=60))
        if info.get("public"):
            raise SystemExit("build-secrets is public: stopping (it must be private)")
        return
    except urllib.error.HTTPError as e:
        if e.code not in (400, 404):
            raise SystemExit(f"bucket check: {e.code}")
    body = json.dumps({"id": BUCKET, "name": BUCKET, "public": False}).encode()
    req = urllib.request.Request(f"{pu.BASE}/bucket", data=body, method="POST", headers=headers(key, {"Content-Type": "application/json"}))
    urllib.request.urlopen(req, timeout=60).read()
    print("made the private folder", BUCKET)


def run(args, secret_values=()):
    r = subprocess.run(args, capture_output=True, text=True)
    out = (r.stdout + r.stderr)
    for s in secret_values:
        out = out.replace(s, "***")
    if r.returncode != 0:
        raise SystemExit("failed: " + " ".join(a if a not in secret_values else "***" for a in args[:3]) + "\n" + out[-2000:])
    return out


def main():
    return main_for(sys.argv[1:])


def main_for(paths):
    apks = [a for a in paths if os.path.exists(a)]
    key = pu.server_key()
    if not key:
        print("::warning::No server key: the APK keeps the old signature.")
        return 0
    print(f"::add-mask::{key}")
    ensure_bucket(key)
    work = tempfile.mkdtemp()
    new_ks, lineage = os.path.join(work, "moments.p12"), os.path.join(work, "lineage")
    ks, pw, lin = get(key, "signing/moments.p12"), get(key, "signing/pass.txt"), get(key, "signing/lineage")
    if ks is None and pw is None and lin is None:
        # First time: a new key (RSA 4096, 100 years) and the rotation record old -> new.
        password = secrets.token_urlsafe(30)
        print(f"::add-mask::{password}")
        run(["keytool", "-genkeypair", "-keystore", new_ks, "-storetype", "PKCS12", "-alias", "moments", "-keyalg", "RSA",
             "-keysize", "4096", "-validity", "36500", "-dname", "CN=Moments, O=Moments", "-storepass", password, "-keypass", password],
            [password])
        run([tool("apksigner"), "rotate", "--out", lineage,
             "--old-signer", "--ks", OLD["ks"], "--ks-key-alias", OLD["alias"], "--ks-pass", "pass:" + OLD["password"], "--key-pass", "pass:" + OLD["password"],
             "--new-signer", "--ks", new_ks, "--ks-key-alias", "moments", "--ks-pass", "pass:" + password, "--key-pass", "pass:" + password],
            [password])
        # Saved before any APK is signed with it (a key that signed an app and then got lost would lock the app).
        put(key, "signing/moments.p12", open(new_ks, "rb").read())
        put(key, "signing/lineage", open(lineage, "rb").read())
        put(key, "signing/pass.txt", password.encode())
        print("made the new signing key and kept it in", BUCKET)
    elif ks is None or pw is None or lin is None:
        raise SystemExit("build-secrets has only part of the signing key: stopping (nothing is signed or published)")
    else:
        password = pw.decode().strip()
        print(f"::add-mask::{password}")
        open(new_ks, "wb").write(ks)
        open(lineage, "wb").write(lin)
    signer = tool("apksigner")
    for apk in apks:
        run([signer, "sign",
             "--ks", OLD["ks"], "--ks-key-alias", OLD["alias"], "--ks-pass", "pass:" + OLD["password"], "--key-pass", "pass:" + OLD["password"],
             "--next-signer", "--ks", new_ks, "--ks-key-alias", "moments", "--ks-pass", "pass:" + password, "--key-pass", "pass:" + password,
             "--lineage", lineage, "--rotation-min-sdk-version", "28", "--v4-signing-enabled", "false", apk], [password])
        out = run([signer, "verify", "-v", "--print-certs", apk], [password])
        if "Verified using v3 scheme (APK Signature Scheme v3): true" not in out:
            raise SystemExit(f"{apk}: not signed with the new key (v3)\n" + out[-1500:])
        print("signed", apk, "(old key -> new key)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
