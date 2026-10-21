"""Unofficial WaterGuru cloud API client.

Auth is AWS Cognito SRP (lifted from ``dwradcliffe/home-assistant-waterguru`` /
``bdwilson/waterguru-api``): SRP login to the user pool, then federated Cognito Identity
credentials, then AWS4-signed direct Lambda ``invocations`` calls.

Two Lambdas are used:

* ``prod-getDashboardView`` — current snapshot (processed measurements incl. lab values).
* ``prod-managePod`` with ``queryDataHistoryReq`` — full-resolution reading history for a pod.
  This is the endpoint that backs the app's trend charts; it is what makes a once-daily
  poll able to backfill the day's ~3 readings at their real timestamps.
"""

from __future__ import annotations

import json
import logging
from datetime import UTC, datetime
from typing import Any

import boto3
import requests
from pycognito import Cognito
from pycognito.aws_srp import AWSSRP
from requests_aws4auth import AWS4Auth

_LOGGER = logging.getLogger(__name__)

# InfluxDB/Lambda timestamps must be exactly millisecond precision or the API returns BAD_REQ.
_WG_TS_FMT = "%Y-%m-%dT%H:%M:%S.%f"

# AWS sets this response header when the Lambda invocation itself failed. It is the
# authoritative failure signal: the invocation can still answer HTTP 200.
_LAMBDA_ERROR_HEADER = "X-Amz-Function-Error"

# Keys an AWS Lambda "handled error" payload carries even behind HTTP 200.
_LAMBDA_ERROR_KEYS = ("errorMsg", "errorMessage", "errorType", "FunctionError")

_MAX_DIAGNOSTIC = 300


def _bounded(value: object, limit: int = _MAX_DIAGNOSTIC) -> str:
    """Bound diagnostic context so an error never dumps a whole (PII-carrying) body."""
    text = str(value)
    return text if len(text) <= limit else text[:limit] + "..."


# Keys safe to surface from a Lambda error body. Anything else (tokens, credentials,
# request echoes) must never be copied into an exception message.
_SAFE_LAMBDA_ERROR_KEYS = ("errorType", "errorMessage", "errorMsg", "code", "message")


def _lambda_error_detail(body: str) -> str:
    """Bounded, allowlisted diagnostics from a Lambda function-error body.

    Never echo arbitrary response fields: a function-error body can carry unrelated
    sensitive fields, so only the allowlisted keys above are reported, and the body's size
    is reported instead of its contents when it is not a JSON object of those keys.
    """
    stripped = body.strip()
    if not stripped:
        return "empty body"
    try:
        payload = json.loads(stripped)
    except ValueError:
        return f"non-JSON body ({len(body)} bytes, not echoed)"
    if not isinstance(payload, dict):
        return f"JSON {type(payload).__name__} body ({len(body)} bytes, not echoed)"
    parts = [
        f"{key}={_bounded(payload[key])}"
        for key in _SAFE_LAMBDA_ERROR_KEYS
        if isinstance(payload.get(key), (str, int, float))
        and not isinstance(payload.get(key), bool)
    ]
    if parts:
        return ", ".join(parts)
    return f"JSON body with no allowlisted diagnostic keys ({len(payload)} keys, not echoed)"


def _fmt_wg_time(dt: datetime) -> str:
    return dt.astimezone(UTC).strftime(_WG_TS_FMT)[:-3] + "Z"


class WaterGuruError(RuntimeError):
    """Raised when the WaterGuru API returns an error or is unreachable."""


class WaterGuruClient:
    REGION = "us-west-2"
    POOL_ID = "us-west-2_icsnuWQWw"
    IDENTITY_POOL_ID = "us-west-2:691e3287-5776-40f2-a502-759de65a8f1c"
    CLIENT_ID = "7pk5du7fitqb419oabb3r92lni"
    LAMBDA_BASE = "https://lambda.us-west-2.amazonaws.com/2015-03-31/functions"

    def __init__(
        self,
        email: str,
        password: str,
        *,
        client_version: str = "0.2.3",
        timeout: float = 30.0,
        session: requests.Session | None = None,
    ) -> None:
        self._email = email
        self._password = password
        self._client_version = client_version
        self._timeout = timeout
        self._session = session or requests.Session()
        self._auth: AWS4Auth | None = None
        self.user_id: str | None = None

    @property
    def _idp_pool(self) -> str:
        return f"cognito-idp.{self.REGION}.amazonaws.com/{self.POOL_ID}"

    def authenticate(self) -> None:
        """Perform SRP login and obtain federated AWS credentials for Lambda calls."""
        boto3.setup_default_session(region_name=self.REGION)
        idp = boto3.client("cognito-idp", region_name=self.REGION)
        srp = AWSSRP(
            username=self._email,
            password=self._password,
            pool_id=self.POOL_ID,
            client_id=self.CLIENT_ID,
            client=idp,
        )
        try:
            tokens = srp.authenticate_user()["AuthenticationResult"]
        except Exception as exc:  # botocore ClientError et al.
            raise WaterGuruError(f"Cognito SRP authentication failed: {exc}") from exc

        id_token = tokens["IdToken"]
        cognito = Cognito(
            self.POOL_ID,
            self.CLIENT_ID,
            id_token=id_token,
            refresh_token=tokens["RefreshToken"],
            access_token=tokens["AccessToken"],
        )
        self.user_id = cognito.get_user()._metadata["username"]

        identity = boto3.client("cognito-identity", region_name=self.REGION)
        identity_id = identity.get_id(IdentityPoolId=self.IDENTITY_POOL_ID)["IdentityId"]
        creds = identity.get_credentials_for_identity(
            IdentityId=identity_id, Logins={self._idp_pool: id_token}
        )["Credentials"]
        self._auth = AWS4Auth(
            creds["AccessKeyId"],
            creds["SecretKey"],
            self.REGION,
            "lambda",
            session_token=creds["SessionToken"],
        )
        _LOGGER.info("Authenticated with WaterGuru cloud (userId acquired)")

    def _invoke(self, function: str, body: dict[str, Any]) -> dict[str, Any]:
        if self._auth is None:
            raise WaterGuruError("authenticate() must be called before invoking the API")
        headers = {
            "User-Agent": "aws-sdk-iOS/2.24.3 iOS/14.7.1 en_US invoker",
            "Content-Type": "application/x-amz-json-1.0",
        }
        url = f"{self.LAMBDA_BASE}/{function}/invocations"
        try:
            resp = self._session.post(
                url, auth=self._auth, json=body, headers=headers, timeout=self._timeout
            )
        except requests.RequestException as exc:
            raise WaterGuruError(f"{function}: request failed: {exc}") from exc
        if resp.status_code != 200:
            raise WaterGuruError(f"{function}: HTTP {resp.status_code}: {_bounded(resp.text)}")

        # A Lambda invocation can fail while the HTTP response is still 200. Check the
        # platform's own failure header first, then the Lambda error payload below.
        function_error = resp.headers.get(_LAMBDA_ERROR_HEADER)
        if function_error:
            raise WaterGuruError(
                f"{function}: Lambda function error ({_bounded(function_error)}): "
                f"{_lambda_error_detail(resp.text)}"
            )
        try:
            data: dict[str, Any] = resp.json()
        except ValueError as exc:
            raise WaterGuruError(
                f"{function}: response was not JSON: {exc}: {_bounded(resp.text)}"
            ) from exc
        if not isinstance(data, dict):
            raise WaterGuruError(f"{function}: unexpected response type {type(data).__name__}")
        for key in _LAMBDA_ERROR_KEYS:
            if data.get(key):
                raise WaterGuruError(
                    f"{function}: {data.get('code') or data.get('errorType') or 'error'}: "
                    f"{_bounded(data[key])}"
                )
        return data

    def get_dashboard(self) -> dict[str, Any]:
        return self._invoke(
            "prod-getDashboardView",
            {
                "userId": self.user_id,
                "clientType": "WEB_APP",
                "clientVersion": self._client_version,
                "clip": False,
            },
        )

    def get_pod_history(
        self, pod_id: int, start: datetime, end: datetime, *, max_results: int = 500
    ) -> dict[str, Any]:
        return self._invoke(
            "prod-managePod",
            {
                "clientType": "WEB_APP",
                "clientView": "ADVICE",
                "clientVersion": self._client_version,
                "locale": "en",
                "tenant": "",
                "region": "US",
                "queryDataHistoryReq": {
                    "podIds": [pod_id],
                    "maxResults": max_results,
                    "startTime": _fmt_wg_time(start),
                    "endTime": _fmt_wg_time(end),
                    "returnCsv": False,
                },
            },
        )
