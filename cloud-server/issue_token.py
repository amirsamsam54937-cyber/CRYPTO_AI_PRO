#!/usr/bin/env python3
import base64, hashlib, hmac, json, os, sys, time
secret=os.environ.get("WOLFBLACK_SIGNING_SECRET","")
if len(secret)<32: raise SystemExit("WOLFBLACK_SIGNING_SECRET must be at least 32 chars")
if len(sys.argv)<2: raise SystemExit("usage: python issue_token.py USER_ID [DAYS]")
sub=sys.argv[1]; days=max(1,min(90,int(sys.argv[2]) if len(sys.argv)>2 else 30))
if not all(c.isalnum() or c in "_-" for c in sub) or not 8<=len(sub)<=80: raise SystemExit("USER_ID must be 8-80 safe characters")
def enc(b): return base64.urlsafe_b64encode(b).decode().rstrip("=")
payload=enc(json.dumps({"sub":sub,"exp":int(time.time())+days*86400},separators=(",",":")).encode())
sig=enc(hmac.new(secret.encode(),payload.encode(),hashlib.sha256).digest())
print(payload+"."+sig)
