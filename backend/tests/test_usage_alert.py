# -*- coding: utf-8 -*-
"""
The spend threshold.

What matters for a beta is narrow: the number is visible, crossing it is
logged once rather than thousands of times, an unset or malformed threshold
disables the feature instead of firing constantly, and none of it can break a
transcription that otherwise succeeded.
"""

import logging
import os

import pytest

from usage_alert import UsageAlert, threshold_inr, window_hours, DEFAULT_WINDOW_HOURS


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    monkeypatch.delenv("USAGE_ALERT_INR", raising=False)
    monkeypatch.delenv("USAGE_ALERT_WINDOW_HOURS", raising=False)


# ---- configuration ---------------------------------------------------------

def test_unset_threshold_means_no_threshold():
    assert threshold_inr() is None


def test_threshold_is_read_from_the_environment(monkeypatch):
    monkeypatch.setenv("USAGE_ALERT_INR", "250")
    assert threshold_inr() == 250.0


def test_threshold_is_read_per_call_not_cached(monkeypatch):
    monkeypatch.setenv("USAGE_ALERT_INR", "100")
    assert threshold_inr() == 100.0
    monkeypatch.setenv("USAGE_ALERT_INR", "500")
    assert threshold_inr() == 500.0


@pytest.mark.parametrize("bad", ["abc", "", "  ", "-5", "0"])
def test_a_bad_threshold_disables_rather_than_firing_constantly(monkeypatch, bad):
    # Returning 0.0 here would make every request a breach and train the
    # operator to ignore the alert entirely.
    monkeypatch.setenv("USAGE_ALERT_INR", bad)
    assert threshold_inr() is None


def test_window_defaults_and_rejects_nonsense(monkeypatch):
    assert window_hours() == DEFAULT_WINDOW_HOURS
    monkeypatch.setenv("USAGE_ALERT_WINDOW_HOURS", "6")
    assert window_hours() == 6.0
    monkeypatch.setenv("USAGE_ALERT_WINDOW_HOURS", "nope")
    assert window_hours() == DEFAULT_WINDOW_HOURS
    monkeypatch.setenv("USAGE_ALERT_WINDOW_HOURS", "0")
    assert window_hours() == DEFAULT_WINDOW_HOURS


# ---- detection -------------------------------------------------------------

def test_crossing_the_threshold_is_detected(monkeypatch):
    monkeypatch.setenv("USAGE_ALERT_INR", "100")
    a = UsageAlert()
    assert a.evaluate(99.99) is False
    assert a.evaluate(100.0) is True, "at the threshold counts as crossed"
    assert a.breached


def test_no_threshold_never_triggers(monkeypatch):
    a = UsageAlert()
    assert a.evaluate(1_000_000.0) is False
    assert not a.breached


def test_it_logs_once_on_crossing_not_on_every_request(monkeypatch, caplog):
    monkeypatch.setenv("USAGE_ALERT_INR", "100")
    a = UsageAlert()
    with caplog.at_level(logging.WARNING, logger="proxy.usage"):
        a.evaluate(50.0)
        a.evaluate(120.0)
        a.evaluate(130.0)
        a.evaluate(900.0)
    fired = [r for r in caplog.records if "USAGE_ALERT" in r.getMessage()]
    assert len(fired) == 1, "an incident must not bury the log it is reported in"


def test_the_log_line_carries_the_numbers_an_operator_needs(monkeypatch, caplog):
    monkeypatch.setenv("USAGE_ALERT_INR", "100")
    a = UsageAlert()
    with caplog.at_level(logging.WARNING, logger="proxy.usage"):
        a.evaluate(150.0)
    msg = caplog.records[-1].getMessage()
    assert "est_cost_inr=150.00" in msg
    assert "threshold_inr=100.00" in msg
    assert "window_hours" in msg


def test_falling_back_under_re_arms_the_alert(monkeypatch, caplog):
    # A rolling window drops old spend, so a later spike must be reported again.
    monkeypatch.setenv("USAGE_ALERT_INR", "100")
    a = UsageAlert()
    with caplog.at_level(logging.INFO, logger="proxy.usage"):
        a.evaluate(150.0)
        a.evaluate(10.0)
        assert not a.breached
        a.evaluate(150.0)
    fired = [r for r in caplog.records if "USAGE_ALERT " in r.getMessage() + " "]
    assert len([r for r in caplog.records
                if r.getMessage().startswith("USAGE_ALERT est")]) == 2
    assert any(r.getMessage().startswith("USAGE_ALERT_CLEARED")
               for r in caplog.records)


def test_removing_the_threshold_clears_a_standing_breach(monkeypatch):
    monkeypatch.setenv("USAGE_ALERT_INR", "100")
    a = UsageAlert()
    a.evaluate(150.0)
    assert a.breached
    monkeypatch.delenv("USAGE_ALERT_INR")
    assert a.evaluate(150.0) is False
    assert not a.breached


# ---- what /v1/usage reports ------------------------------------------------

def test_status_fields_are_present_even_with_no_threshold():
    # A client must never have to guess whether the feature exists.
    st = UsageAlert().status(42.0)
    assert st["alert_threshold_inr"] is None
    assert st["alert_triggered"] is False
    assert st["alert_window_hours"] == DEFAULT_WINDOW_HOURS


def test_status_reports_a_breach(monkeypatch):
    monkeypatch.setenv("USAGE_ALERT_INR", "10")
    st = UsageAlert().status(11.0)
    assert st["alert_threshold_inr"] == 10.0
    assert st["alert_triggered"] is True


def test_status_contains_no_transcript_or_content_fields(monkeypatch):
    monkeypatch.setenv("USAGE_ALERT_INR", "10")
    st = UsageAlert().status(11.0)
    for k, v in st.items():
        assert "text" not in k and "transcript" not in k
        assert not isinstance(v, str) or k == "never"
