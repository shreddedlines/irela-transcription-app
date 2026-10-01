# -*- coding: utf-8 -*-
"""
Irela Pro (billing.py, pro.py, play_api.py): verification, entitlement, Pro
limits, notifications, reconciliation, the operator script -- and the rule that
overrides all of them: expiry, cancellation, refund, payment failure or a used-up
allowance never deletes anything.

No test contacts Google. Play is a scripted fake; the OIDC and service-account
code paths are exercised with locally generated keys and an httpx mock.
"""
import asyncio
import base64
import datetime as dt
import json
import os
import re
import sqlite3
import sys
import tempfile
import time

import httpx
import pytest
from cryptography.fernet import Fernet

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.dirname(__file__))

import billing as B                                                   # noqa: E402
import play_api                                                       # noqa: E402
from test_owner_entitlement import claim, make_world, me, post        # noqa: E402

PKG = "com.whispercppdemo"
PRODUCT = "irela_pro"
KEY = Fernet.generate_key().decode()
BILLING_ENV = {"BILLING_ENABLED": "true", "PLAY_PACKAGE_NAME": PKG,
               "PLAY_SERVICE_ACCOUNT_FILE": "/nonexistent/sa.json", "BILLING_TOKEN_KEY": KEY,
               "PLAY_RTDN_AUDIENCE": "https://example.test/v1/billing/rtdn",
               "PLAY_RTDN_SERVICE_ACCOUNT": "rtdn@example.iam.gserviceaccount.com"}
BACKEND_MODULES = ("billing.py", "pro.py", "play_api.py", "billing_sync.py", "billing_admin.py")
HERE = os.path.join(os.path.dirname(__file__), "..")


def rfc(t: float) -> str:
    return dt.datetime.fromtimestamp(t, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def purchase(state=B.ACTIVE, base_plan="monthly", expiry=None, ack=False, linked=None,
             product=PRODUCT, test=False, auto=True):
    d = {"subscriptionState": state, "startTime": rfc(time.time() - 86400),
         "acknowledgementState": "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED" if ack else B.ACK_PENDING,
         "lineItems": [{"productId": product,
                        "expiryTime": rfc(expiry if expiry is not None else time.time() + 20 * 86400),
                        "autoRenewingPlan": {"autoRenewEnabled": auto},
                        "offerDetails": {"basePlanId": base_plan}}]}
    if linked:
        d["linkedPurchaseToken"] = linked
    if test:
        d["testPurchase"] = {}
    return d


class FakePlay:
    def __init__(self):
        self.subs, self.acks, self.gets, self.voided = {}, [], [], []
        self.fail_status = None                # e.g. 503: every call fails
        self.ack_fails = False

    async def get_subscription(self, token):
        self.gets.append(token)
        if self.fail_status:
            raise play_api.PlayApiError("down", self.fail_status)
        if token not in self.subs:
            raise play_api.PlayApiError("unknown", 404, invalid=True)
        return self.subs[token]

    async def acknowledge(self, product_id, token):
        if self.ack_fails:
            raise play_api.PlayApiError("ack down", 503)
        self.acks.append((product_id, token))
        self.subs[token]["acknowledgementState"] = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"

    async def voided_purchases(self, start_ms):
        return [{"purchaseToken": t} for t in self.voided]


class FakeVerifier:
    def __init__(self):
        self.ok = True

    async def verify(self, authorization):
        return self.ok and authorization == "Bearer google-signed"


def billing_world(monkeypatch, **env):
    w = make_world(monkeypatch, **{**BILLING_ENV, **env})
    w.play, w.verifier = FakePlay(), FakeVerifier()
    w.app_mod.billing._play = w.play
    w.app_mod.billing._verifier = w.verifier
    return w


@pytest.fixture()
def bw(monkeypatch):
    return billing_world(monkeypatch)


@pytest.fixture()
def w(monkeypatch):
    """The deployed default: no billing configuration at all."""
    return make_world(monkeypatch)


def verify(cl, token, product=PRODUCT):
    return cl.post("/v1/billing/verify", json={"product_id": product, "purchase_token": token})


def rtdn(cl, data, auth="Bearer google-signed"):
    env = {"message": {"data": base64.b64encode(json.dumps(data).encode()).decode(),
                       "messageId": "1"}, "subscription": "projects/p/subscriptions/s"}
    headers = {"Authorization": auth} if auth else {}
    return cl.post("/v1/billing/rtdn", json=env, headers=headers)


def sub_note(token, ntype=2):
    return {"version": "1.0", "packageName": PKG, "eventTimeMillis": str(int(time.time() * 1000)),
            "subscriptionNotification": {"version": "1.0", "notificationType": ntype,
                                         "purchaseToken": token, "subscriptionId": PRODUCT}}


def table_counts(db):
    with sqlite3.connect(db) as con:
        return {t: con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
                for t in ("requests", "installations", "subscriptions", "billing_events")}


# ---- billing OFF (the deployed default) -------------------------------------------

def test_billing_is_off_by_default_and_every_billing_route_is_404(w):
    cl = w.client()
    assert not w.app_mod.billing.verify_enabled and not w.app_mod.billing.rtdn_enabled
    r = verify(cl, "any-token")
    assert r.status_code == 404 and r.json()["reason"] == "not_found"
    assert rtdn(cl, sub_note("t")).status_code == 404
    assert w.app_mod.subscriptions.all() == []


def test_free_and_owner_me_are_unchanged_while_billing_is_off(w):
    free = me(w.client())
    assert set(free) == {"plan", "unlimited", "monthly", "daily", "limits", "service"}
    assert free["plan"] == "free" and "subscription" not in free
    owner = me(w.owner(ip="198.51.100.11"))
    assert owner == {"plan": "owner", "unlimited": True, "monthly": None, "daily": None,
                     "limits": owner["limits"], "service": owner["service"]}


@pytest.mark.parametrize("missing", ["PLAY_PACKAGE_NAME", "PLAY_SERVICE_ACCOUNT_FILE",
                                     "BILLING_TOKEN_KEY", "BILLING_ENABLED"])
def test_billing_stays_off_unless_everything_it_needs_is_configured(monkeypatch, missing):
    w = make_world(monkeypatch, **{**BILLING_ENV, missing: None})
    assert not w.app_mod.billing.verify_enabled
    assert verify(w.client(), "t").status_code == 404


def test_an_invalid_token_key_disables_billing_without_taking_the_service_down(monkeypatch):
    w = make_world(monkeypatch, **{**BILLING_ENV, "BILLING_TOKEN_KEY": "not-a-fernet-key"})
    assert not w.app_mod.billing.verify_enabled
    cl = w.client()
    assert post(cl, 5).status_code == 200


# ---- verification -------------------------------------------------------------------

def test_an_active_purchase_makes_this_installation_pro_and_is_acknowledged(bw):
    cl = bw.client()
    bw.play.subs["tok-1"] = purchase()
    r = verify(cl, "tok-1")
    assert r.status_code == 200, r.text
    assert r.json()["plan"] == "pro" and r.json()["subscription"]["state"] == "active"
    assert bw.play.acks == [(PRODUCT, "tok-1")]
    m = me(cl)
    assert m["plan"] == "pro" and m["unlimited"] is False
    assert m["monthly"]["allowance_seconds"] == 5 * 3600
    assert m["daily"]["allowance_seconds"] == 2 * 3600
    assert m["daily"]["transcriptions_limit"] == 60
    assert m["subscription"]["base_plan"] == "monthly"


def test_a_pending_purchase_grants_nothing_but_is_reported(bw):
    cl = bw.client()
    bw.play.subs["tok-p"] = purchase(state=B.PENDING)
    r = verify(cl, "tok-p")
    assert r.status_code == 200 and r.json()["plan"] == "free"
    assert bw.play.acks == []
    m = me(cl)
    assert m["plan"] == "free" and m["subscription"]["state"] == "pending"


@pytest.mark.parametrize("data,sent,reason", [
    (lambda: purchase(product="other_product"), PRODUCT, "product_not_supported"),
    (lambda: purchase(), "other_product", "product_not_supported"),
    (lambda: purchase(base_plan="weekly"), PRODUCT, "product_not_supported"),
])
def test_other_products_and_base_plans_are_refused(bw, data, sent, reason):
    cl = bw.client()
    bw.play.subs["tok"] = data()
    r = verify(cl, "tok", product=sent)
    assert r.status_code == 400 and r.json()["reason"] == reason
    assert me(cl)["plan"] == "free"


def test_a_token_google_does_not_know_is_refused(bw):
    r = verify(bw.client(), "forged-token")
    assert r.status_code == 400 and r.json()["reason"] == "purchase_invalid"


def test_google_unavailable_is_a_retryable_503_and_grants_nothing(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    bw.play.fail_status = 503
    r = verify(cl, "tok")
    assert r.status_code == 503 and r.json()["retryable"] is True
    assert r.json()["reason"] == "billing_unavailable" and me(cl)["plan"] == "free"


def test_verify_needs_the_installation_token(bw):
    anon = bw.client()
    anon.headers.pop("Authorization")
    assert verify(anon, "tok").status_code == 401


@pytest.mark.parametrize("body", [{"product_id": PRODUCT}, {"purchase_token": "t"},
                                  {"product_id": 5, "purchase_token": "t"}, "not-an-object"])
def test_malformed_verify_bodies_are_refused(bw, body):
    r = bw.client().post("/v1/billing/verify", json=body)
    assert r.status_code == 400


def test_oversized_verify_requests_are_refused_unread(bw):
    cl = bw.client()
    assert verify(cl, "x" * 5000).status_code == 400                  # token too long
    r = cl.post("/v1/billing/verify", content=b"{" + b" " * 9000 + b"}",
                headers={"Content-Type": "application/json"})
    assert r.status_code == 400
    assert bw.play.gets == []


def test_verification_is_rate_limited_per_installation(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    codes = [verify(cl, "tok").status_code for _ in range(21)]
    assert codes[:20] == [200] * 20 and codes[20] == 429


def test_a_restore_moves_the_purchase_to_the_new_installation_a_bounded_number_of_times(bw):
    bw.play.subs["tok"] = purchase()
    phones = [bw.client(ip=f"198.51.100.{20 + i}") for i in range(5)]
    assert verify(phones[0], "tok").json()["plan"] == "pro"
    for i in (1, 2, 3):                                               # three moves allowed
        assert verify(phones[i], "tok").json()["plan"] == "pro"
        assert me(phones[i])["plan"] == "pro" and me(phones[i - 1])["plan"] == "free"
    r = verify(phones[4], "tok")                                      # a fourth is refused
    assert r.status_code == 409 and r.json()["reason"] == "subscription_in_use"
    assert me(phones[3])["plan"] == "pro" and me(phones[4])["plan"] == "free"


def test_a_failed_acknowledgement_keeps_pro_and_the_sync_retries_it(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    bw.play.ack_fails = True
    assert verify(cl, "tok").json()["plan"] == "pro"
    assert bw.app_mod.subscriptions.all()[0].acknowledged is False
    bw.play.ack_fails = False
    asyncio.run(bw.app_mod.billing.sync())
    assert bw.play.acks == [(PRODUCT, "tok")]
    assert bw.app_mod.subscriptions.all()[0].acknowledged is True


def test_purchase_tokens_are_stored_encrypted_never_in_plain_text(bw):
    cl = bw.client()
    token = "plain-token-" + "q" * 40
    bw.play.subs[token] = purchase()
    verify(cl, token)
    raw = open(bw.db, "rb").read()
    assert token.encode() not in raw
    with sqlite3.connect(bw.db) as con:
        th, enc = con.execute("SELECT token_hash, token_enc FROM subscriptions").fetchone()
    assert th == B.token_hash(token) and B.TokenCipher(KEY).decrypt(enc) == token


# ---- Pro transcription and limits ---------------------------------------------------

def test_pro_is_not_limited_by_the_free_allowance_and_does_not_consume_it(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    verify(cl, "tok")
    r = post(cl, 25 * 60, key="pro-1")                              # > 20 free minutes
    assert r.status_code == 200, r.text
    assert bw.calls == 1
    assert bw.rows(cl.installation_id)[-1][:2] == ("pro", 0)       # plan pro, not free tier
    assert bw.app_mod.allowance.status(cl.installation_id).used_seconds == 0
    assert me(cl)["monthly"]["used_seconds"] == 25 * 60


def test_the_billing_cycle_cap_refuses_before_any_provider_call_with_resets_at(monkeypatch):
    w = billing_world(monkeypatch, PRO_MONTHLY_SECONDS=1800)
    cl = w.client()
    expiry = time.time() + 20 * 86400
    w.play.subs["tok"] = purchase(expiry=expiry)
    verify(cl, "tok")
    assert post(cl, 1200, key="a").status_code == 200
    r = post(cl, 1200, key="b")
    assert r.status_code == 429 and r.json()["reason"] == "pro_monthly_limit_exceeded"
    assert r.json()["retryable"] is False
    assert abs(dt.datetime.fromisoformat(r.json()["resets_at"]).timestamp() - expiry) < 2
    assert w.calls == 1


def test_the_daily_cap_resets_at_midnight(monkeypatch):
    w = billing_world(monkeypatch, PRO_DAILY_SECONDS=1800)
    cl = w.client()
    w.play.subs["tok"] = purchase()
    verify(cl, "tok")
    assert post(cl, 1200, key="a").status_code == 200
    r = post(cl, 1200, key="b")
    assert r.status_code == 429 and r.json()["reason"] == "pro_daily_limit_exceeded"
    resets = dt.datetime.fromisoformat(r.json()["resets_at"])
    assert (resets.hour, resets.minute) == (0, 0) and resets.timestamp() > time.time()


def test_owner_outranks_pro(bw):
    cl = bw.owner()
    bw.play.subs["tok"] = purchase()
    verify(cl, "tok")
    assert me(cl)["plan"] == "owner"


def test_a_revoked_installation_gets_nothing_from_a_subscription(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    verify(cl, "tok")
    bw.app_mod.auth.store.revoke(cl.installation_id)
    assert cl.get("/v1/me").status_code == 403
    assert post(cl, 5).status_code == 403


# ---- lifecycle: notifications, expiry, grace, refunds --------------------------------

def test_rtdn_without_googles_signature_is_rejected_and_changes_nothing(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    verify(cl, "tok")
    bw.play.subs["tok"] = purchase(state=B.EXPIRED)
    for auth in (None, "Bearer forged", "Basic x"):
        assert rtdn(cl, sub_note("tok"), auth=auth).status_code == 401
    assert me(cl)["plan"] == "pro"


def test_rtdn_state_comes_from_google_not_from_the_message(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    verify(cl, "tok")
    bw.play.subs["tok"] = purchase(state=B.ON_HOLD, ack=True)       # payment failed
    assert rtdn(cl, sub_note("tok", ntype=5)).status_code == 204
    m = me(cl)
    assert m["plan"] == "free" and m["subscription"]["state"] == "on_hold"
    r = post(cl, 25 * 60)                                            # back to free limits
    assert r.status_code == 429 and r.json()["reason"] == "monthly_free_allowance_exceeded"


def test_rtdn_for_another_package_or_malformed_is_acknowledged_and_ignored(bw):
    cl = bw.client()
    assert rtdn(cl, {**sub_note("tok"), "packageName": "com.other"}).status_code == 204
    assert rtdn(cl, {"packageName": PKG, "testNotification": {"version": "1.0"}}).status_code == 204
    r = cl.post("/v1/billing/rtdn", json={"message": {"data": "!!!"}},
                headers={"Authorization": "Bearer google-signed"})
    assert r.status_code == 204
    assert bw.play.gets == []


def test_rtdn_is_retried_by_pubsub_while_google_is_unavailable(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    verify(cl, "tok")
    bw.play.fail_status = 503
    assert rtdn(cl, sub_note("tok")).status_code == 503


def test_a_notification_before_the_app_verifies_binds_to_no_one(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    assert rtdn(cl, sub_note("tok", ntype=4)).status_code == 204
    s = bw.app_mod.subscriptions.all()[0]
    assert s.installation_id is None and me(cl)["plan"] == "free"
    assert bw.play.acks == [(PRODUCT, "tok")]                         # the user is not refunded
    assert verify(cl, "tok").json()["plan"] == "pro"


@pytest.mark.parametrize("state,expiry_offset,pro", [
    (B.ACTIVE, 10 * 86400, True),
    (B.IN_GRACE, -86400, True),                   # payment retrying: keep Pro
    (B.CANCELED, 5 * 86400, True),                # cancelled: paid time still runs
    (B.CANCELED, -60, False),                     # ...until it ends
    (B.ON_HOLD, 5 * 86400, False),
    (B.PAUSED, 5 * 86400, False),
    (B.EXPIRED, -60, False),
    (B.PENDING, 5 * 86400, False),
])
def test_each_play_state_maps_to_the_right_plan(bw, state, expiry_offset, pro):
    cl = bw.client()
    bw.play.subs["tok"] = purchase(state=state, expiry=time.time() + expiry_offset, ack=True)
    verify(cl, "tok")
    assert me(cl)["plan"] == ("pro" if pro else "free")


def test_a_refund_makes_it_free_immediately_and_permanently(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    verify(cl, "tok")
    note = {"packageName": PKG, "voidedPurchaseNotification": {
        "purchaseToken": "tok", "orderId": "GPA.1", "productType": 1, "refundType": 1}}
    assert rtdn(cl, note).status_code == 204
    assert me(cl)["plan"] == "free" and me(cl)["subscription"]["state"] == "revoked"
    asyncio.run(bw.app_mod.billing.sync())         # Google still says ACTIVE: stays revoked
    assert me(cl)["plan"] == "free"
    assert verify(cl, "tok").json()["plan"] == "free"


def test_the_sync_applies_voided_purchases_a_notification_missed(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    verify(cl, "tok")
    bw.play.voided = ["tok"]
    report = asyncio.run(bw.app_mod.billing.sync())
    assert report["revoked"] == 1 and me(cl)["plan"] == "free"


def test_the_sync_refreshes_stale_subscriptions_from_google(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    verify(cl, "tok")
    bw.play.subs["tok"] = purchase(state=B.EXPIRED, expiry=time.time() - 60, ack=True)
    asyncio.run(bw.app_mod.billing.sync(stale_after_s=0))
    assert me(cl)["plan"] == "free"


def test_the_sync_does_nothing_while_billing_is_off(w):
    assert asyncio.run(w.app_mod.billing.sync()) == {"enabled": False}


def test_an_upgrade_supersedes_the_old_purchase_and_annual_hours_reset_monthly(bw):
    cl = bw.client()
    bw.play.subs["old"] = purchase()
    verify(cl, "old")
    bw.play.subs["new"] = purchase(base_plan="annual", expiry=time.time() + 300 * 86400,
                                   linked="old")
    assert verify(cl, "new").json()["plan"] == "pro"
    states = {s.token_hash: s.state for s in bw.app_mod.subscriptions.all()}
    assert states[B.token_hash("old")] == B.REPLACED
    m = me(cl)
    start = dt.datetime.fromisoformat(m["monthly"]["starts_at"]).timestamp()
    end = dt.datetime.fromisoformat(m["monthly"]["resets_at"]).timestamp()
    assert 27 * 86400 < end - start < 32 * 86400                     # a month, not a year


# ---- THE INVARIANT: nothing is ever deleted ------------------------------------------

def test_expiry_refund_payment_failure_and_quota_never_delete_anything(monkeypatch):
    w = billing_world(monkeypatch, PRO_MONTHLY_SECONDS=1800)
    cl = w.client()
    w.play.subs["tok"] = purchase()
    verify(cl, "tok")
    done = post(cl, 1200, key="paid-job")
    assert done.status_code == 200
    seen = table_counts(w.db)

    def never_shrinks():
        now = table_counts(w.db)
        assert all(now[t] >= seen[t] for t in seen), (seen, now)
        seen.update(now)

    assert post(cl, 1200, key="over").status_code == 429               # quota exhausted
    never_shrinks()
    w.play.subs["tok"] = purchase(state=B.ON_HOLD, ack=True)           # payment failure
    rtdn(cl, sub_note("tok", 5)); never_shrinks()
    w.play.subs["tok"] = purchase(state=B.CANCELED, expiry=time.time() - 1, ack=True)
    rtdn(cl, sub_note("tok", 3)); never_shrinks()                     # cancelled and expired
    rtdn(cl, {"packageName": PKG, "voidedPurchaseNotification": {"purchaseToken": "tok"}})
    never_shrinks()                                                    # refunded
    asyncio.run(w.app_mod.billing.sync(stale_after_s=0)); never_shrinks()
    # The transcript already paid for is still served, now as a free installation.
    again = post(cl, 1200, key="paid-job")
    assert again.status_code == 200 and again.json()["text"] == done.json()["text"]
    assert again.json()["replayed"] is True and me(cl)["plan"] == "free"
    with sqlite3.connect(w.db) as con:
        assert con.execute("SELECT COUNT(*) FROM subscriptions").fetchone()[0] == 1


def test_billing_code_contains_no_delete_of_any_kind():
    pattern = re.compile(r"DELETE\s+FROM|DROP\s+TABLE|TRUNCATE|os\.remove|\.unlink\(|rmtree|os\.rmdir",
                         re.IGNORECASE)
    for name in BACKEND_MODULES:
        src = open(os.path.join(HERE, name), encoding="utf-8").read()
        assert not pattern.search(src), name


# ---- pure rules -----------------------------------------------------------------------

def test_entitlement_rules():
    now = 1_800_000_000.0
    assert B.entitles(B.ACTIVE, now + 1, now) and B.entitles(B.ACTIVE, None, now)
    assert B.entitles(B.ACTIVE, now - 2 * 86400, now)                # missed renewal: slack
    assert not B.entitles(B.ACTIVE, now - 4 * 86400, now)
    assert B.entitles(B.IN_GRACE, now - 9 * 86400, now)
    assert B.entitles(B.CANCELED, now + 1, now) and not B.entitles(B.CANCELED, now - 1, now)
    assert not B.entitles(B.CANCELED, None, now)
    for s in (B.ON_HOLD, B.PAUSED, B.EXPIRED, B.PENDING, B.PENDING_CANCELED, B.REVOKED,
              B.REPLACED, "SUBSCRIPTION_STATE_UNSPECIFIED"):
        assert not B.entitles(s, now + 86400, now), s


def ts(*a):
    return dt.datetime(*a, tzinfo=dt.timezone.utc).timestamp()


def test_usage_windows_follow_the_billing_cycle():
    exp = ts(2026, 10, 15)
    assert B.usage_window(1, exp, ts(2026, 10, 1)) == (ts(2026, 9, 15), exp)
    assert B.usage_window(1, exp, ts(2026, 10, 20)) == (ts(2026, 9, 15), exp)   # grace after expiry
    # Month-end expiry clamps (31 March -> 28 February).
    assert B.usage_window(1, ts(2027, 3, 31), ts(2027, 3, 1))[0] == ts(2027, 2, 28)
    # Annual: the month containing now, within the year.
    yexp = ts(2027, 9, 15)
    assert B.usage_window(12, yexp, ts(2027, 1, 20)) == (ts(2027, 1, 15), ts(2027, 2, 15))
    assert B.usage_window(12, yexp, ts(2026, 9, 16)) == (ts(2026, 9, 15), ts(2026, 10, 15))


def test_play_facts_are_parsed_from_subscriptionsv2():
    f = B.PlayFacts.parse(purchase(base_plan="annual", expiry=ts(2027, 1, 1), linked="L",
                                   test=True, auto=False))
    assert (f.state, f.product_id, f.base_plan, f.linked_token) == (B.ACTIVE, PRODUCT, "annual", "L")
    assert f.expiry_at == ts(2027, 1, 1) and f.test and not f.auto_renewing and not f.acknowledged
    empty = B.PlayFacts.parse({})
    assert empty.product_id == "" and empty.expiry_at is None


def test_the_free_allowance_ignores_pro_rows():
    from allowance import MonthlyAllowance
    from metering import Metering
    db = os.path.join(tempfile.mkdtemp(), "m.sqlite3")
    m = Metering(db)
    m.record("deepgram", 10, 600.0, True, 200, installation_id="i1", provider_called=True,
             allowance_s=600.0, plan="pro")
    m.record("deepgram", 10, 60.0, True, 200, installation_id="i1", provider_called=True,
             allowance_s=60.0, plan="free")
    assert MonthlyAllowance(db).used_seconds("i1") == 60.0


# ---- the real Google clients, against local keys and a mock transport ----------------

def _rsa_and_cert():
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "test")])
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(1)
            .not_valid_before(dt.datetime(2020, 1, 1)).not_valid_after(dt.datetime(2040, 1, 1))
            .sign(key, hashes.SHA256()))
    pem_key = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.NoEncryption()).decode()
    return pem_key, cert.public_bytes(serialization.Encoding.PEM).decode()


def _id_token(pem_key, **claims):
    from google.auth import crypt, jwt
    signer = crypt.RSASigner.from_string(pem_key, key_id="k1")
    now = int(time.time())
    base = {"iss": "https://accounts.google.com", "aud": BILLING_ENV["PLAY_RTDN_AUDIENCE"],
            "email": BILLING_ENV["PLAY_RTDN_SERVICE_ACCOUNT"], "email_verified": True,
            "iat": now, "exp": now + 600}
    tok = jwt.encode(signer, {**base, **claims})
    return tok.decode() if isinstance(tok, bytes) else tok


def test_the_oidc_verifier_accepts_only_googles_push_identity():
    pem_key, cert = _rsa_and_cert()
    other_key, _ = _rsa_and_cert()
    v = play_api.GoogleOidcVerifier(BILLING_ENV["PLAY_RTDN_AUDIENCE"],
                                    BILLING_ENV["PLAY_RTDN_SERVICE_ACCOUNT"])

    async def certs():
        return {"k1": cert}
    v._google_certs = certs
    ok = lambda t: asyncio.run(v.verify("Bearer " + t))
    assert ok(_id_token(pem_key))
    assert not ok(_id_token(pem_key, aud="https://evil.example/rtdn"))
    assert not ok(_id_token(pem_key, email="someone@evil.example"))
    assert not ok(_id_token(pem_key, email_verified=False))
    assert not ok(_id_token(pem_key, iss="https://evil.example"))
    assert not ok(_id_token(pem_key, exp=int(time.time()) - 3600, iat=int(time.time()) - 7200))
    assert not ok(_id_token(other_key))                               # not Google's key
    assert not asyncio.run(v.verify(None)) and not asyncio.run(v.verify("Bearer garbage"))


def test_the_play_client_signs_as_the_service_account_and_maps_errors(monkeypatch, tmp_path):
    pem_key, _ = _rsa_and_cert()
    sa = tmp_path / "sa.json"
    sa.write_text(json.dumps({"type": "service_account", "client_email": "svc@p.iam.gserviceaccount.com",
                              "private_key": pem_key, "private_key_id": "k1",
                              "token_uri": "https://oauth2.googleapis.com/token"}))
    seen = []

    def handler(request):
        seen.append((request.method, str(request.url)))
        if request.url.host == "oauth2.googleapis.com":
            form = dict(x.split("=", 1) for x in request.content.decode().split("&"))
            assert form["grant_type"].startswith("urn%3Aietf")
            return httpx.Response(200, json={"access_token": "at", "expires_in": 3600})
        assert request.headers["authorization"] == "Bearer at"
        if "tokens/good" in str(request.url) and request.method == "GET":
            return httpx.Response(200, json=purchase())
        if request.url.path.endswith(":acknowledge"):
            return httpx.Response(200)
        if "voidedpurchases" in str(request.url):
            return httpx.Response(200, json={"voidedPurchases": [{"purchaseToken": "v1"}]})
        if "tokens/down" in str(request.url):
            return httpx.Response(500)
        return httpx.Response(404)

    real = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient",
                        lambda **kw: real(transport=httpx.MockTransport(handler), **kw))
    api = play_api.GooglePlayApi(PKG, str(sa))
    assert asyncio.run(api.get_subscription("good"))["subscriptionState"] == B.ACTIVE
    asyncio.run(api.acknowledge(PRODUCT, "good"))
    assert asyncio.run(api.voided_purchases(0)) == [{"purchaseToken": "v1"}]
    with pytest.raises(play_api.PlayApiError) as e:
        asyncio.run(api.get_subscription("unknown"))
    assert e.value.invalid
    with pytest.raises(play_api.PlayApiError) as e:
        asyncio.run(api.get_subscription("down"))
    assert not e.value.invalid and e.value.status == 500
    assert any("subscriptionsv2/tokens/good" in u for _, u in seen)
    assert any(u.endswith("/purchases/subscriptions/irela_pro/tokens/good:acknowledge") for _, u in seen)


# ---- operator script and /v1/usage --------------------------------------------------

def test_admin_grant_and_revoke_without_printing_secrets(bw, monkeypatch, capsys):
    import billing_admin
    cl = bw.client()
    monkeypatch.setenv("METERING_DB", bw.db)
    assert billing_admin.main(["grant", cl.installation_id, "30"]) == 0
    assert me(cl)["plan"] == "pro"
    assert billing_admin.main(["grant", cl.installation_id, "90"]) == 2
    capsys.readouterr()
    billing_admin.main(["list"])
    listing = capsys.readouterr().out
    assert cl.installation_id not in listing and cl.installation_id[:8] in listing
    ref = re.search(r"\n  (\S{10})\s", listing).group(1)
    assert billing_admin.main(["revoke", ref]) == 0
    assert me(cl)["plan"] == "free"
    assert billing_admin.main(["key"]) == 0
    Fernet(capsys.readouterr().out.strip().splitlines()[-1].encode())


def test_usage_reports_billing_counts_only(bw):
    cl = bw.client()
    bw.play.subs["tok"] = purchase()
    verify(cl, "tok")
    body = bw.client().get("/v1/usage").json()["billing"]
    assert body["verify_enabled"] and body["subscriptions"] == {"active": 1}
    assert "tok" not in json.dumps(body)
