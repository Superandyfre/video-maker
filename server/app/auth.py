from __future__ import annotations

import secrets

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config import get_api_token

bearer_scheme = HTTPBearer(auto_error=False)


def _unauthorized() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Unauthorized",
        headers={"WWW-Authenticate": "Bearer"},
    )


def require_api_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> None:
    expected = get_api_token()
    if not expected:
        return
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise _unauthorized()
    if not secrets.compare_digest(credentials.credentials, expected):
        raise _unauthorized()


def require_configured_api_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> None:
    expected = get_api_token()
    if not expected:
        raise _unauthorized()
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise _unauthorized()
    if not secrets.compare_digest(credentials.credentials, expected):
        raise _unauthorized()

