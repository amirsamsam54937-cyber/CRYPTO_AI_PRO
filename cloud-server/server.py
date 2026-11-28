#!/usr/bin/env python3
"""WOLFBLACK private memory sync API. Standard-library only; put behind HTTPS."""
import base64, hashlib, hmac, json, os, re, sqlite3, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

DB = os.environ.get("WOLFBLACK_DB", "/data/wolfblack.sqlite3")
SECRET = os.environ.get("WOLFBLACK_SIGNING_SECRET", "")
MAX_BODY = 128 * 1024
BLOCKED = re.compile(r"(api.?key|secret|password|private.?key|token|credential|seed.?phrase)", re.I)

def b64d(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))

def auth_user(header):
    if not SECRET or len(SECRET) < 32 or not header.startswith("Bearer "):
        return None
    try:
        token = header[7:]
        payload, signature = token.split(".", 1)
        expected = hmac.new(SECRET.encode(), payload.encode(), hashlib.sha256).digest()
        if not hmac.compare_digest(expected, b64d(signature)):
            return None
        obj = json.loads(b64d(payload))
        if int(obj["exp"]) < int(time.time()):
            return None
        uid = str(obj["sub"])
        if not re.fullmatch(r"[A-Za-z0-9_-]{8,80}", uid):
            return None
        return uid
    except Exception:
        return None

def db():
    os.makedirs(os.path.dirname(DB) or ".", exist_ok=True)
    c = sqlite3.connect(DB, timeout=15)
    c.execute("PRAGMA journal_mode=WAL")
    c.execute("""CREATE TABLE IF NOT EXISTS memory(
        user_id TEXT NOT NULL, record_id TEXT NOT NULL, created_at INTEGER NOT NULL,
        payload TEXT NOT NULL, PRIMARY KEY(user_id, record_id))""")
    c.execute("CREATE INDEX IF NOT EXISTS idx_memory_user_time ON memory(user_id, created_at)")
    return c

def contains_secret(value):
    if isinstance(value, dict):
        return any(BLOCKED.search(str(k)) or contains_secret(v) for k, v in value.items())
    if isinstance(value, list):
        return any(contains_secret(v) for v in value)
    return False

class Handler(BaseHTTPRequestHandler):
    server_version = "WOLFBLACK-Sync/1.0"
    def send_json(self, status, obj):
        raw = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status); self.send_header("Content-Type","application/json; charset=utf-8")
        self.send_header("Content-Length",str(len(raw))); self.send_header("Cache-Control","no-store")
        self.end_headers(); self.wfile.write(raw)
    def do_GET(self):
        path = urlparse(self.path)
        if path.path == "/health":
            return self.send_json(200, {"ok": True, "service":"wolfblack-sync", "storage":"sqlite"})
        if path.path != "/v1/memory":
            return self.send_json(404, {"error":"not_found"})
        uid = auth_user(self.headers.get("Authorization",""))
        if not uid: return self.send_json(401, {"error":"unauthorized"})
        q = parse_qs(path.query); before = int(q.get("before",[str(int(time.time()*1000))])[0])
        limit = min(200, max(1, int(q.get("limit",["100"])[0])))
        with db() as c:
            rows = c.execute("SELECT record_id,created_at,payload FROM memory WHERE user_id=? AND created_at<? ORDER BY created_at DESC LIMIT ?",(uid,before,limit)).fetchall()
        return self.send_json(200, {"records":[{"id":r[0],"createdAt":r[1],"data":json.loads(r[2])} for r in rows]})
    def do_POST(self):
        if urlparse(self.path).path != "/v1/memory":
            return self.send_json(404, {"error":"not_found"})
        uid = auth_user(self.headers.get("Authorization",""))
        if not uid: return self.send_json(401, {"error":"unauthorized"})
        try:
            n = int(self.headers.get("Content-Length","0"))
            if n < 2 or n > MAX_BODY: return self.send_json(413, {"error":"payload_size"})
            body = json.loads(self.rfile.read(n))
            items = body.get("records")
            if not isinstance(items,list) or not 1 <= len(items) <= 100:
                return self.send_json(400, {"error":"records_must_be_1_to_100"})
            clean=[]
            for item in items:
                rid=str(item.get("id",""))
                data=item.get("data")
                if not re.fullmatch(r"[A-Za-z0-9_-]{8,100}",rid) or not isinstance(data,dict) or contains_secret(data):
                    return self.send_json(400, {"error":"invalid_or_sensitive_record"})
                clean.append((uid,rid,int(item.get("createdAt",time.time()*1000)),json.dumps(data,ensure_ascii=False,separators=(",",":"))))
            with db() as c:
                c.executemany("INSERT OR IGNORE INTO memory(user_id,record_id,created_at,payload) VALUES(?,?,?,?)",clean)
            return self.send_json(200, {"accepted":len(clean)})
        except (ValueError,TypeError,json.JSONDecodeError):
            return self.send_json(400, {"error":"invalid_json"})
    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args), flush=True)

if __name__ == "__main__":
    if len(SECRET) < 32: raise SystemExit("Set WOLFBLACK_SIGNING_SECRET to a random value of at least 32 characters")
    port=int(os.environ.get("PORT","8080"))
    ThreadingHTTPServer(("0.0.0.0",port),Handler).serve_forever()
