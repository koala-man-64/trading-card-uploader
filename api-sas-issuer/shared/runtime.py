"""Process-scope singletons for expensive clients.

Azure Functions Python v2 keeps the worker process warm across invocations,
so anything safe to share (JWKS validation, Azure SDK clients, the AAD user
delegation key) is built once per warm instance instead of once per request.
`functools.lru_cache` is used for the memoization: it is thread-safe in
CPython, which matches how the Python worker dispatches sync functions
across a thread pool, and the Azure SDK clients cached here are documented
as safe for concurrent reuse across threads.
"""

from __future__ import annotations

from functools import lru_cache

from azure.storage.blob import BlobServiceClient, ContainerClient

from .auth import JwtValidator
from .config import Settings
from .gallery import build_container
from .sas import SasIssuer, UserDelegationKeyCache, build_issuer, build_service_client


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings.from_env()


@lru_cache(maxsize=1)
def get_jwt_validator() -> JwtValidator:
    return JwtValidator(get_settings())


@lru_cache(maxsize=1)
def get_service_client() -> BlobServiceClient:
    return build_service_client(get_settings())


@lru_cache(maxsize=1)
def get_container() -> ContainerClient:
    return build_container(get_settings(), service_client=get_service_client())


@lru_cache(maxsize=1)
def get_delegation_key_cache() -> UserDelegationKeyCache:
    return UserDelegationKeyCache(get_service_client())


@lru_cache(maxsize=1)
def get_issuer() -> SasIssuer:
    return build_issuer(
        get_settings(),
        service_client=get_service_client(),
        key_cache=get_delegation_key_cache(),
    )


def reset_runtime_caches() -> None:
    """Test-only helper: clear every process-scope singleton above so tests
    that monkeypatch environment variables or fakes don't leak state into
    each other."""
    get_settings.cache_clear()
    get_jwt_validator.cache_clear()
    get_service_client.cache_clear()
    get_container.cache_clear()
    get_delegation_key_cache.cache_clear()
    get_issuer.cache_clear()
