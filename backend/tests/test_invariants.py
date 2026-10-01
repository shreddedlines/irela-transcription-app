# -*- coding: utf-8 -*-
"""
Backend invariant tests. No network, no credentials.

The mip_opt_out tests are the important ones. Deepgram's Terms 3.3 make the
training opt-out per request, so a single request missing it puts that user's
audio under Terms 3.2's perpetual, sublicensable licence, irreversibly. These
tests assert it cannot be removed, disabled, or crowded out by caller input.

    python -m pytest backend/tests -q
"""

import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from providers import assemblyai, deepgram  # noqa: E402


# ---------------------------------------------------------------- invariant
def test_mip_opt_out_present_by_default():
    p = deepgram.build_params()
    assert p["mip_opt_out"] == "true"


def test_mip_opt_out_survives_language_override():
    p = deepgram.build_params(language="hi")
    assert p["mip_opt_out"] == "true"
    assert p["language"] == "hi"


def test_mip_opt_out_survives_keyterms():
    p = deepgram.build_params(keyterms=["Venkatesh", "Priyanka Deshmukh"])
    assert p["mip_opt_out"] == "true"


def test_mip_opt_out_cannot_be_disabled_via_frozen_mutation():
    """Direct tampering with FROZEN must be caught, not silently honoured."""
    deepgram.FROZEN["mip_opt_out"] = "false"
    try:
        with pytest.raises(AssertionError):
            deepgram.build_params()
    finally:
        deepgram.FROZEN.pop("mip_opt_out", None)


def test_frozen_config_matches_validated_values():
    """Guards against a silent drift away from the measured configuration."""
    p = deepgram.build_params()
    assert p["model"] == "nova-3"
    assert p["language"] == "hi"
    assert p["smart_format"] == "false"
    assert p["punctuate"] == "true"
    assert p["numerals"] == "true"


# ---------------------------------------------------------- input validation
@pytest.mark.parametrize("bad", ["hi&mip_opt_out=false", "../../etc", "h i",
                                 "toolonglanguagecode", "hi;x"])
def test_language_injection_rejected(bad):
    """A language hint must not be a vehicle for other parameters."""
    with pytest.raises(deepgram.ProviderError):
        deepgram.build_params(language=bad)


def test_keyterms_capped_at_provider_limit():
    p = deepgram.build_params(keyterms=[f"t{i}" for i in range(250)])
    assert len(p["keyterm"]) == 100


def test_blank_keyterms_dropped():
    p = deepgram.build_params(keyterms=["  ", "", "Rajesh"])
    assert p["keyterm"] == ["Rajesh"]


def test_no_keyterm_key_when_none_supplied():
    assert "keyterm" not in deepgram.build_params()


# -------------------------------------------------------------- credentials
def test_missing_key_is_a_clean_error_not_a_crash(monkeypatch):
    monkeypatch.delenv("DEEPGRAM_API_KEY", raising=False)
    with pytest.raises(deepgram.ProviderError) as e:
        deepgram._api_key()
    assert e.value.retryable is False


def test_no_api_key_literal_in_source():
    """A key must never be committed. Cheap guard against the obvious mistake."""
    here = os.path.dirname(__file__)
    for name in ("deepgram.py", "assemblyai.py"):
        src = open(os.path.join(here, "..", "providers", name),
                   encoding="utf-8").read()
        assert "os.environ" in src
        # Deepgram keys are 40-char hex; AssemblyAI 32-char hex.
        import re
        assert not re.search(r"['\"][0-9a-f]{32,}['\"]", src)


# ----------------------------------------------------------- assemblyai parity
def test_assemblyai_frozen_model():
    cfg = assemblyai.build_config("https://x/y")
    assert cfg["speech_models"] == ["universal-3-5-pro"]


def test_assemblyai_language_detection_when_no_hint():
    cfg = assemblyai.build_config("https://x/y")
    assert cfg.get("language_detection") is True
    assert "language_code" not in cfg


def test_assemblyai_keyterms_capped():
    cfg = assemblyai.build_config("https://x/y",
                                  keyterms=[f"t{i}" for i in range(250)])
    assert len(cfg["keyterms_prompt"]) == 100
