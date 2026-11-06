from app.exchanges.xt_futures import XTCredentials, XTFuturesClient


def test_xt_signature_is_deterministic():
    client = XTFuturesClient(
        credentials=XTCredentials(api_key="public-test-key", secret_key="test-secret"),
        base_url="https://example.invalid",
    )
    sig1 = client._signature(
        "/future/user/v1/balance/detail",
        "1700000000000",
        {"coin": "btc"},
    )
    sig2 = client._signature(
        "/future/user/v1/balance/detail",
        "1700000000000",
        {"coin": "btc"},
    )
    assert len(sig1) == 64
    assert sig1 == sig2


def test_xt_signature_changes_when_request_changes():
    client = XTFuturesClient(
        credentials=XTCredentials(api_key="public-test-key", secret_key="test-secret"),
        base_url="https://example.invalid",
    )
    a = client._signature("/endpoint", "1700000000000", {"coin": "btc"})
    b = client._signature("/endpoint", "1700000000000", {"coin": "eth"})
    assert a != b
