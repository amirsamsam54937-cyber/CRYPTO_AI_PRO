def test_xt_seed_collector_module_imports_cleanly():
    from tools import build_real_market_seed
    assert callable(build_real_market_seed.collect)
