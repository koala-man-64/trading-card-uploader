from __future__ import annotations

import pytest

from shared import runtime


@pytest.fixture(autouse=True)
def _clear_runtime_caches():
    runtime.reset_runtime_caches()
    yield
    runtime.reset_runtime_caches()


def _set_required_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ENTRA_TENANT_ID", "tenant")
    monkeypatch.setenv("API_CLIENT_ID", "api-client")
    monkeypatch.setenv("UPLOAD_STORAGE_ACCOUNT_URL", "https://upload.blob.core.windows.net")
    monkeypatch.setenv("UPLOAD_CONTAINER_NAME", "card-uploads")
    monkeypatch.setenv("HASH_SALT", "salt")
    monkeypatch.setenv("SAS_SIGNER_MODE", "connection_string")
    monkeypatch.setenv(
        "AZURE_STORAGE_CONNECTION_STRING",
        "DefaultEndpointsProtocol=https;AccountName=upload;AccountKey=key;EndpointSuffix=core.windows.net",
    )


def test_get_settings_is_a_process_scope_singleton(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_required_env(monkeypatch)

    first = runtime.get_settings()
    second = runtime.get_settings()

    assert first is second


def test_get_jwt_validator_reuses_settings_singleton(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_required_env(monkeypatch)

    first = runtime.get_jwt_validator()
    second = runtime.get_jwt_validator()

    assert first is second


def test_reset_runtime_caches_forces_rebuild(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_required_env(monkeypatch)

    first = runtime.get_settings()
    runtime.reset_runtime_caches()
    _set_required_env(monkeypatch)
    second = runtime.get_settings()

    assert first is not second
