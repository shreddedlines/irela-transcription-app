# -*- coding: utf-8 -*-
"""
The two things billing needs from Google, and nothing else.

  GooglePlayApi   server-to-server Android Publisher calls, authenticated as a
                  service account: read a subscription purchase, acknowledge
                  it, and list voided (refunded / charged-back) purchases.
  RtdnVerifier    checks the Google-signed OIDC token on a Pub/Sub push, so
                  only Google's push subscription can reach /v1/billing/rtdn.

Both are small interfaces so billing.py is testable with fakes: no test ever
contacts Google. HTTP goes through httpx (already a dependency); google-auth is
used only to sign the service-account assertion and to verify JWT signatures.

Nothing here logs a purchase token, an access token, a private key or a JWT.
"""

import json
import logging
import time
import urllib.parse
from typing import Dict, List, Optional, Protocol

import httpx

log = logging.getLogger("proxy.billing.play")

PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher"
PUBLISHER_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
GOOGLE_OIDC_CERTS = "https://www.googleapis.com/oauth2/v1/certs"
GOOGLE_ISSUERS = ("https://accounts.google.com", "accounts.google.com")


class PlayApiError(Exception):
    """A Google call that did not succeed. [invalid] = Google rejected the token itself."""

    def __init__(self, message: str, status: Optional[int] = None, invalid: bool = False):
        super().__init__(message)
        self.status = status
        self.invalid = invalid


class PlayApi(Protocol):
    async def get_subscription(self, purchase_token: str) -> dict: ...
    async def acknowledge(self, product_id: str, purchase_token: str) -> None: ...
    async def voided_purchases(self, start_ms: int) -> List[dict]: ...


def _q(s: str) -> str:
    return urllib.parse.quote(s, safe="")


class GooglePlayApi:
    """The real Android Publisher API. Access tokens are cached until near expiry."""

    def __init__(self, package_name: str, service_account_file: str,
                 timeout_s: float = 15.0, clock=time.time):
        self._package = package_name
        self._sa_file = service_account_file
        self._timeout = timeout_s
        self._clock = clock
        self._token: Optional[str] = None
        self._token_expiry = 0.0

    def _service_account(self) -> dict:
        with open(self._sa_file, "r", encoding="utf-8") as f:
            return json.load(f)

    async def _access_token(self, client: httpx.AsyncClient) -> str:
        now = self._clock()
        if self._token and now < self._token_expiry - 120:
            return self._token
        from google.auth import crypt, jwt          # imported only when billing is on
        info = self._service_account()
        token_uri = info.get("token_uri") or "https://oauth2.googleapis.com/token"
        signer = crypt.RSASigner.from_service_account_info(info)
        assertion = jwt.encode(signer, {
            "iss": info["client_email"], "scope": PUBLISHER_SCOPE, "aud": token_uri,
            "iat": int(now), "exp": int(now) + 3600})
        if isinstance(assertion, bytes):
            assertion = assertion.decode("ascii")
        r = await client.post(token_uri, data={
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": assertion})
        if r.status_code != 200:
            raise PlayApiError("service account token exchange failed", r.status_code)
        body = r.json()
        self._token = body["access_token"]
        self._token_expiry = now + float(body.get("expires_in", 3600))
        return self._token

    async def _call(self, method: str, url: str, json_body: Optional[dict] = None,
                    params: Optional[Dict[str, str]] = None) -> dict:
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                token = await self._access_token(client)
                r = await client.request(method, url, json=json_body, params=params,
                                         headers={"Authorization": "Bearer " + token})
        except httpx.HTTPError as e:
            raise PlayApiError(f"transport error: {type(e).__name__}")
        if r.status_code in (400, 404, 410):
            raise PlayApiError("purchase not recognised by Google", r.status_code, invalid=True)
        if r.status_code >= 300:
            raise PlayApiError("Google Play API error", r.status_code)
        return r.json() if r.content else {}

    async def get_subscription(self, purchase_token: str) -> dict:
        return await self._call("GET", f"{PUBLISHER_BASE}/{_q(self._package)}"
                                       f"/purchases/subscriptionsv2/tokens/{_q(purchase_token)}")

    async def acknowledge(self, product_id: str, purchase_token: str) -> None:
        await self._call("POST", f"{PUBLISHER_BASE}/{_q(self._package)}/purchases/subscriptions/"
                                 f"{_q(product_id)}/tokens/{_q(purchase_token)}:acknowledge",
                         json_body={})

    async def voided_purchases(self, start_ms: int) -> List[dict]:
        out: List[dict] = []
        page: Optional[str] = None
        for _ in range(50):                            # bounded pagination
            params = {"startTime": str(start_ms), "type": "1"}   # 1 = include subscriptions
            if page:
                params["token"] = page
            body = await self._call("GET", f"{PUBLISHER_BASE}/{_q(self._package)}"
                                           f"/purchases/voidedpurchases", params=params)
            out.extend(body.get("voidedPurchases") or [])
            page = (body.get("tokenPagination") or {}).get("nextPageToken")
            if not page:
                break
        return out


class RtdnVerifier(Protocol):
    async def verify(self, authorization: Optional[str]) -> bool: ...


class GoogleOidcVerifier:
    """
    Accepts a push only when its bearer token is a Google-signed ID token for
    [audience], issued by Google, for the configured push service account with a
    verified email. Google's signing certificates are cached for an hour.
    """

    def __init__(self, audience: str, service_account_email: str,
                 timeout_s: float = 10.0, clock=time.time):
        self._audience = audience
        self._email = service_account_email.strip().lower()
        self._timeout = timeout_s
        self._clock = clock
        self._certs: Optional[dict] = None
        self._certs_at = 0.0

    async def _google_certs(self) -> dict:
        if self._certs and self._clock() - self._certs_at < 3600:
            return self._certs
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            r = await client.get(GOOGLE_OIDC_CERTS)
        r.raise_for_status()
        self._certs, self._certs_at = r.json(), self._clock()
        return self._certs

    async def verify(self, authorization: Optional[str]) -> bool:
        scheme, _, token = (authorization or "").partition(" ")
        if scheme.lower() != "bearer" or not token.strip():
            return False
        try:
            from google.auth import jwt
            claims = jwt.decode(token.strip(), certs=await self._google_certs(),
                                audience=self._audience, clock_skew_in_seconds=60)
        except Exception:
            return False
        return (claims.get("iss") in GOOGLE_ISSUERS
                and str(claims.get("email", "")).lower() == self._email
                and claims.get("email_verified") is True)
