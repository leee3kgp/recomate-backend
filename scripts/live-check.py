"""Re-runnable checks against a deployed RecoMate environment (#22).

Creates temporary Cognito users, drives the real API through the same contract the frontend uses,
and always removes what it created. Prints check names and results only: never passwords or tokens.
Usage and prerequisites: docs/live-check.md. Exit code 0 only when every check passes.
"""
import argparse
import secrets
import sys
import uuid

import boto3
import requests
from pycognito.aws_srp import AWSSRP

PAGES = ["/", "/pages/index.html", "/pages/login.html", "/pages/signup.html", "/pages/verify-email.html",
         "/pages/profile.html", "/pages/select.html", "/pages/result.html"]
GENRES = ["ACTION", "COMEDY", "ROMANCE", "DRAMA", "SF", "FANTASY", "HORROR", "CRIME", "MYSTERY", "ANIMATION"]
SAMPLE_SESSION = "01234567-89ab-4cde-8fab-0123456789ab"


class Checks:
    def __init__(self):
        self.passed = 0
        self.failed = []

    def check(self, name, ok, detail=""):
        if ok:
            self.passed += 1
        else:
            self.failed.append(name)
        print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail != "" else ""), flush=True)
        return ok

    def section(self, title):
        print(f"\n== {title}", flush=True)


def error_code(response):
    try:
        return response.json()["error"]["code"]
    except (ValueError, KeyError, TypeError):
        return None


class TempUser:
    """A temporary Cognito user; removed by `cleanup` unless the app's withdrawal already did it."""

    def __init__(self, args, idp, nickname):
        self.args, self.idp = args, idp
        self.email = f"recomate-live-check-{uuid.uuid4().hex[:12]}@example.test"
        password = "Lc-" + secrets.token_urlsafe(24) + "7a!"
        attributes = [{"Name": "email", "Value": self.email}, {"Name": "email_verified", "Value": "true"}]
        if nickname is not None:
            attributes.append({"Name": "nickname", "Value": nickname})
        idp.admin_create_user(UserPoolId=args.user_pool_id, Username=self.email, MessageAction="SUPPRESS",
                              UserAttributes=attributes)
        self.exists = True
        idp.admin_set_user_password(UserPoolId=args.user_pool_id, Username=self.email, Password=password,
                                    Permanent=True)
        tokens = AWSSRP(username=self.email, password=password, pool_id=args.user_pool_id,
                        client_id=args.client_id, client=idp).authenticate_user()["AuthenticationResult"]
        self.id_token, self.refresh_token = tokens["IdToken"], tokens["RefreshToken"]
        self.withdrawn = False

    def headers(self, origin=None):
        headers = {"Authorization": f"Bearer {self.id_token}"}
        if origin:
            headers["Origin"] = origin
        return headers

    def refreshed_id_token(self):
        result = self.idp.initiate_auth(ClientId=self.args.client_id, AuthFlow="REFRESH_TOKEN_AUTH",
                                        AuthParameters={"REFRESH_TOKEN": self.refresh_token})
        return result["AuthenticationResult"]["IdToken"]

    def gone_from_cognito(self):
        try:
            self.idp.admin_get_user(UserPoolId=self.args.user_pool_id, Username=self.email)
            return False
        except self.idp.exceptions.UserNotFoundException:
            self.exists = False
            return True

    def cleanup(self, api):
        if not self.withdrawn:
            try:
                api.delete(f"{self.args.api_base}/api/users/me", headers=self.headers(), timeout=60)
            except requests.RequestException:
                pass
        if self.exists and not self.gone_from_cognito():
            self.idp.admin_delete_user(UserPoolId=self.args.user_pool_id, Username=self.email)


def check_site(c, args, http):
    c.section(f"frontend site {args.site_url}")
    for page in PAGES:
        r = http.get(args.site_url + page, timeout=30)
        c.check(f"page {page}", r.status_code == 200, r.status_code)
    r = http.get(args.site_url.replace("https://", "http://", 1) + "/pages/login.html", allow_redirects=False, timeout=30)
    c.check("HTTP redirects to HTTPS", r.status_code in (301, 302, 307, 308)
            and r.headers.get("Location", "").startswith("https://"), r.status_code)
    r = http.get(args.site_url + "/pages/login.html", timeout=30)
    c.check("HTML revalidates (no-cache)", r.headers.get("Cache-Control") == "no-cache", r.headers.get("Cache-Control"))
    c.check("HSTS header", "max-age=" in r.headers.get("Strict-Transport-Security", ""))
    c.check("frame protection header", r.headers.get("X-Frame-Options") in ("SAMEORIGIN", "DENY"),
            r.headers.get("X-Frame-Options"))
    c.check("nosniff header", r.headers.get("X-Content-Type-Options") == "nosniff")
    r = http.get(args.site_url + "/version.json", timeout=30)
    version = r.json() if r.status_code == 200 else {}
    c.check("version.json names the deployed commit", len(version.get("commit", "")) == 40,
            version.get("commit", r.status_code)[:12] if version else r.status_code)
    c.check("frontend build targets this API", version.get("apiBaseUrl") == args.api_base, version.get("apiBaseUrl"))
    r = http.get(args.site_url + "/no-such-page-" + uuid.uuid4().hex[:6], timeout=30)
    c.check("unknown path is 404", r.status_code == 404, r.status_code)


def check_unauthenticated(c, args, http):
    c.section("routes without a token")
    session = f"/api/sessions/{SAMPLE_SESSION}"
    protected = [("GET", "/api/users/me"), ("PATCH", "/api/users/me"), ("DELETE", "/api/users/me"),
                 ("GET", "/api/users/me/genres"), ("PUT", "/api/users/me/genres"),
                 ("GET", "/api/sessions"), ("POST", "/api/sessions"), ("GET", session), ("DELETE", session),
                 ("POST", session + "/recommendations"), ("POST", session + "/exclusions")]
    routes = protected + [("GET", "/api/genres"), ("POST", "/api/guest/recommendations")]
    for method, path in protected:
        r = http.request(method, args.api_base + path, json={} if method in ("POST", "PUT", "PATCH") else None, timeout=60)
        c.check(f"{method} {path} rejects missing token", r.status_code == 401, r.status_code)
    for origin in args.origins:
        allowed = 0
        for method, path in routes:
            r = http.options(args.api_base + path, timeout=60, headers={
                "Origin": origin, "Access-Control-Request-Method": method,
                "Access-Control-Request-Headers": "authorization,content-type"})
            allowed += r.status_code == 200 and r.headers.get("Access-Control-Allow-Origin") == origin
        c.check(f"CORS preflight allowed for {origin}", allowed == len(routes), f"{allowed}/{len(routes)}")
    r = http.options(args.api_base + "/api/users/me", timeout=60, headers={
        "Origin": "https://not-allowed.example.com", "Access-Control-Request-Method": "GET"})
    c.check("CORS preflight rejected for an unlisted origin", r.status_code == 403, r.status_code)
    check_guest(c, args, http)


def check_guest(c, args, http):
    """Public routes: guests read genres and get a TOP 3 that is never stored (one TMDB-backed call each)."""
    c.section("guest routes without a token")
    origin = args.origins[0]
    r = http.get(args.api_base + "/api/genres", headers={"Origin": origin}, timeout=60)
    codes = [g.get("code") for g in r.json().get("genres", [])] if r.status_code == 200 else []
    c.check("guest reads the genre catalog", sorted(codes) == sorted(GENRES), r.status_code)
    c.check("guest catalog response allows the frontend origin", r.headers.get("Access-Control-Allow-Origin") == origin)
    body = {"participantCount": 2, "participants": [{"index": 1, "genres": ["SF", "ACTION"]},
                                                    {"index": 2, "genres": ["COMEDY"]}]}
    r = http.post(args.api_base + "/api/guest/recommendations", headers={"Origin": origin}, json=body, timeout=60)
    data = r.json() if r.status_code == 200 else {}
    shown = data.get("recommendations", [])
    c.check("guest gets recommendations without a token", r.status_code == 200 and 0 < len(shown) <= 3
            and "analysis" in data, r.status_code)
    c.check("guest response has no session id and is not cached", "sessionId" not in data
            and "no-store" in r.headers.get("Cache-Control", ""))
    if shown:
        seen = {"tmdbId": shown[0]["tmdbId"], "mediaType": shown[0]["mediaType"]}
        r = http.post(args.api_base + "/api/guest/recommendations", json=dict(body, excluded=[seen]), timeout=60)
        keys = [(t.get("tmdbId"), t.get("mediaType")) for t in r.json().get("recommendations", [])] if r.status_code == 200 else None
        c.check("guest 'already watched' title is left out", keys is not None
                and (seen["tmdbId"], seen["mediaType"]) not in keys, r.status_code)
    r = http.post(args.api_base + "/api/guest/recommendations", timeout=60, json={
        "participantCount": 3, "participants": body["participants"]})
    c.check("guest invalid participants rejected", r.status_code == 400, r.status_code)
    r = http.post(args.api_base + "/api/guest/recommendations", headers={"Authorization": "Bearer not-a-jwt"},
                  json=body, timeout=60)
    c.check("guest route still rejects an invalid token", r.status_code == 401, r.status_code)


def check_user_flow(c, args, http, ddb, user):
    origin = args.origins[0]
    base = args.api_base
    c.section("profile, nickname and genres")
    r = http.get(base + "/api/users/me", headers=user.headers(origin), timeout=60)
    profile = r.json() if r.status_code == 200 else {}
    c.check("first profile read creates the profile", r.status_code == 200 and profile.get("email") == user.email
            and profile.get("nickname") == "점검용", r.status_code)
    c.check("authenticated response allows the frontend origin", r.headers.get("Access-Control-Allow-Origin") == origin)
    c.check("profile response is not cached", "no-store" in r.headers.get("Cache-Control", ""))
    for label, value in [("empty", ""), ("spaces only", "   "), ("21 characters", "가" * 21)]:
        r = http.patch(base + "/api/users/me", headers=user.headers(), json={"nickname": value}, timeout=60)
        c.check(f"nickname {label} rejected", r.status_code == 400 and error_code(r) == "VALIDATION_ERROR", r.status_code)
    r = http.patch(base + "/api/users/me", headers=user.headers(), json={"nickname": "  새닉네임🙂  "}, timeout=60)
    c.check("nickname trimmed and saved (emoji counts as one)", r.status_code == 200
            and r.json().get("nickname") == "새닉네임🙂", r.status_code)
    r = http.patch(base + "/api/users/me", headers=user.headers(), json={"nickname": "가" * 20}, timeout=60)
    c.check("nickname of exactly 20 characters accepted", r.status_code == 200, r.status_code)
    r = http.get(base + "/api/users/me", headers=user.headers(), timeout=60)
    c.check("saved nickname is returned on reload", r.status_code == 200 and r.json().get("nickname") == "가" * 20)

    r = http.get(base + "/api/genres", headers=user.headers(), timeout=60)
    codes = [g.get("code") for g in r.json().get("genres", [])] if r.status_code == 200 else []
    c.check("genre catalog lists the 10 codes", sorted(codes) == sorted(GENRES), len(codes))
    for label, value in [("unknown code", ["NOT_A_GENRE"]), ("duplicate", ["SF", "SF"]), ("not a list", "SF")]:
        r = http.put(base + "/api/users/me/genres", headers=user.headers(), json={"genres": value}, timeout=60)
        c.check(f"genre {label} rejected", r.status_code == 400, r.status_code)
    r = http.put(base + "/api/users/me/genres", headers=user.headers(), json={"genres": ["SF", "DRAMA"]}, timeout=60)
    c.check("preferred genres saved", r.status_code == 200, r.status_code)
    r = http.get(base + "/api/users/me/genres", headers=user.headers(), timeout=60)
    c.check("preferred genres returned on reload", r.status_code == 200
            and sorted(r.json().get("genres", [])) == ["DRAMA", "SF"])
    r = http.put(base + "/api/users/me/genres", headers=user.headers(), json={"genres": []}, timeout=60)
    c.check("clearing every genre is allowed", r.status_code == 200 and r.json().get("genres") == [], r.status_code)

    c.section("tokens")
    r = http.get(base + "/api/users/me", headers={"Authorization": f"Bearer {user.id_token[:-4]}abcd"}, timeout=60)
    c.check("tampered token rejected", r.status_code == 401, r.status_code)
    fresh = user.refreshed_id_token()
    r = http.get(base + "/api/users/me", headers={"Authorization": f"Bearer {fresh}"}, timeout=60)
    c.check("token refreshed with the refresh token works", r.status_code == 200, r.status_code)

    c.section("sessions, recommendations and exclusions")
    r = http.post(base + "/api/sessions", headers=user.headers(), timeout=60, json={
        "participantCount": 2,
        "participants": [{"index": 1, "genres": ["ACTION", "SF"]}, {"index": 2, "genres": ["SF", "DRAMA"]}]})
    session_id = r.json().get("sessionId") if r.status_code == 201 else None
    c.check("session created", r.status_code == 201 and bool(session_id), r.status_code)
    if not session_id:
        return None
    path = f"{base}/api/sessions/{session_id}"
    r = http.get(base + "/api/sessions", headers=user.headers(), timeout=60)
    c.check("session appears in my list", r.status_code == 200
            and session_id in [s.get("sessionId") for s in r.json().get("sessions", [])], r.status_code)
    if args.skip_recommendation:
        print("SKIP recommendation and exclusion checks (--skip-recommendation)")
    else:
        r = http.post(path + "/recommendations", headers=user.headers(), timeout=60)
        top = r.json().get("recommendations", []) if r.status_code == 200 else []
        c.check("TOP 3 generated", r.status_code == 200 and len(top) == 3, f"{r.status_code}, {len(top)} titles")
        if top:
            first = top[0]
            r = http.post(path + "/exclusions", headers=user.headers(), timeout=60,
                          json={"tmdbId": first["tmdbId"], "mediaType": first["mediaType"]})
            after = r.json().get("recommendations", []) if r.status_code == 200 else []
            c.check("exclusion replaces the title", r.status_code == 200 and all(
                (t["mediaType"], t["tmdbId"]) != (first["mediaType"], first["tmdbId"]) for t in after), r.status_code)
            r = http.get(path, headers=user.headers(), timeout=60)
            c.check("session detail reflects the exclusion", r.status_code == 200
                    and r.json().get("recommendations") == after)
            r = http.post(path + "/recommendations", headers=user.headers(), timeout=60)
            c.check("regeneration rejected", r.status_code == 409 and error_code(r) == "RECOMMENDATION_EXISTS", r.status_code)
    other = f"{base}/api/sessions/{uuid.uuid4()}"
    r = http.get(other, headers=user.headers(), timeout=60)
    c.check("unknown session is 404", r.status_code == 404, r.status_code)
    r = http.delete(path, headers=user.headers(), timeout=60)
    c.check("session deleted", r.status_code == 204, r.status_code)
    r = http.get(path, headers=user.headers(), timeout=60)
    c.check("deleted session is 404", r.status_code == 404, r.status_code)
    r = http.post(base + "/api/sessions", headers=user.headers(), timeout=60, json={
        "participantCount": 2, "participants": [{"index": 1, "genres": ["COMEDY"]}, {"index": 2, "genres": ["ANIMATION"]}]})
    return r.json().get("sessionId") if r.status_code == 201 else None


def check_withdrawal(c, args, http, ddb, user, kept_session):
    c.section("withdrawal")
    old = user.headers()
    r = http.delete(args.api_base + "/api/users/me", headers=old, timeout=60)
    user.withdrawn = c.check("withdrawal succeeds", r.status_code == 204, r.status_code)
    r = http.get(args.api_base + "/api/users/me", headers=old, timeout=60)
    c.check("old token rejected after withdrawal", r.status_code == 401, r.status_code)
    r = http.get(args.api_base + "/api/sessions", headers=old, timeout=60)
    c.check("old token cannot read sessions", r.status_code == 401, r.status_code)
    r = http.delete(args.api_base + "/api/users/me", headers=old, timeout=60)
    c.check("repeated withdrawal stays 204", r.status_code == 204, r.status_code)
    c.check("Cognito user removed", user.gone_from_cognito())
    if kept_session:
        item = ddb.get_item(TableName=args.sessions_table, Key={"sessionId": {"S": kept_session}}, ConsistentRead=True)
        c.check("user's sessions removed", "Item" not in item)


def check_missing_nickname(c, args, http, user):
    c.section("missing signup nickname")
    r = http.get(args.api_base + "/api/users/me", headers=user.headers(), timeout=60)
    c.check("profile asks for a nickname", r.status_code == 409 and error_code(r) == "NICKNAME_REQUIRED", r.status_code)
    r = http.patch(args.api_base + "/api/users/me", headers=user.headers(), json={"nickname": "복구"}, timeout=60)
    c.check("saving a nickname repairs the profile", r.status_code == 200, r.status_code)
    r = http.get(args.api_base + "/api/users/me", headers=user.headers(), timeout=60)
    c.check("profile readable after repair", r.status_code == 200 and r.json().get("nickname") == "복구", r.status_code)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--profile", default="recomate", help="AWS profile with access to the project")
    parser.add_argument("--region", default="ap-southeast-2")
    parser.add_argument("--api-base", default="https://rdjkqhp2el.execute-api.ap-southeast-2.amazonaws.com/dev")
    parser.add_argument("--site-url", default="https://d15wymuj9qezfe.cloudfront.net", help="'' skips site checks")
    parser.add_argument("--origin", dest="origins", action="append",
                        help="browser origin expected in CORS; repeatable (default: site URL and localhost)")
    parser.add_argument("--user-pool-id", default="ap-southeast-2_Cjugq3j3p")
    parser.add_argument("--client-id", default="7h7kjle5d1dh6ogea95thrk20q")
    parser.add_argument("--sessions-table", default="RecommendationSession")
    parser.add_argument("--skip-recommendation", action="store_true", help="skip TMDB-backed generation")
    args = parser.parse_args()
    args.api_base = args.api_base.rstrip("/")
    args.site_url = args.site_url.rstrip("/")
    args.origins = args.origins or [o for o in (args.site_url, "http://localhost:5173") if o]

    sys.stdout.reconfigure(encoding="utf-8")
    aws = boto3.Session(profile_name=args.profile, region_name=args.region)
    idp, ddb = aws.client("cognito-idp"), aws.client("dynamodb")
    http = requests.Session()
    c = Checks()
    users = []
    try:
        if args.site_url:
            check_site(c, args, http)
        check_unauthenticated(c, args, http)
        main_user = TempUser(args, idp, "점검용")
        users.append(main_user)
        c.check("temporary user signs in with SRP", True)
        kept = check_user_flow(c, args, http, ddb, main_user)
        check_withdrawal(c, args, http, ddb, main_user, kept)
        repair_user = TempUser(args, idp, None)
        users.append(repair_user)
        check_missing_nickname(c, args, http, repair_user)
    except Exception as error:  # report the type only; messages can contain request data
        c.check("run completed without an unexpected error", False, type(error).__name__)
    finally:
        cleanup_failed = 0
        for user in users:
            try:
                user.cleanup(http)
            except Exception:
                cleanup_failed += 1
        c.check("temporary users cleaned up", cleanup_failed == 0, f"{len(users)} users")
    print(f"\nSUMMARY {c.passed}/{c.passed + len(c.failed)} passed")
    for name in c.failed:
        print(f"  failed: {name}")
    return 0 if not c.failed else 1


if __name__ == "__main__":
    sys.exit(main())
