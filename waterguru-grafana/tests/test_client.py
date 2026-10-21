"""Regression coverage for WaterGuru Lambda invocation failures.

AWS Lambda can fail the invocation while still answering HTTP 200: the platform sets the
``X-Amz-Function-Error`` response header, and a handled error returns an error payload. Both
must be treated as failures, with bounded (never secret) diagnostics.
"""

from __future__ import annotations

import json

import pytest
import requests

from waterguru_poller.client import WaterGuruClient, WaterGuruError


class _Session:
    def __init__(self, response: requests.Response) -> None:
        self.response = response
        self.calls: list[dict] = []

    def post(self, url, auth=None, json=None, headers=None, timeout=None):  # noqa: A002
        self.calls.append({"url": url, "auth": auth, "json": json, "headers": headers})
        return self.response


def _response(body: str = "{}", *, status_code: int = 200, headers: dict | None = None):
    response = requests.Response()
    response.status_code = status_code
    response._content = body.encode("utf-8")
    response.encoding = "utf-8"
    response.url = "https://lambda.us-west-2.amazonaws.com/2015-03-31/functions/x/invocations"
    response.headers.update(headers or {})
    return response


def _client(response: requests.Response) -> tuple[WaterGuruClient, _Session]:
    session = _Session(response)
    client = WaterGuruClient("user@example.test", "super-secret-password", session=session)
    client._auth = object()  # type: ignore[assignment] - the SRP handshake is not under test
    return client, session


def test_lambda_function_error_header_fails_even_with_http_200() -> None:
    client, _ = _client(_response('{"ok": true}', headers={"X-Amz-Function-Error": "Unhandled"}))
    with pytest.raises(WaterGuruError, match="Lambda function error"):
        client._invoke("prod-getDashboardView", {"userId": "u"})


def test_lambda_error_payload_fails_even_with_http_200() -> None:
    body = json.dumps({"errorMessage": "boom", "errorType": "java.lang.RuntimeException"})
    client, _ = _client(_response(body))
    with pytest.raises(WaterGuruError, match="boom"):
        client._invoke("prod-managePod", {})


def test_legacy_error_msg_payload_still_fails() -> None:
    client, _ = _client(_response(json.dumps({"errorMsg": "bad request", "code": "BAD_REQ"})))
    with pytest.raises(WaterGuruError, match="BAD_REQ"):
        client._invoke("prod-managePod", {})


def test_success_payload_is_returned() -> None:
    payload = {"queryDataHistoryRsp": {"data": [{"time": "2026-08-18T14:34:18.000Z"}]}}
    client, _ = _client(_response(json.dumps(payload)))
    assert client._invoke("prod-managePod", {}) == payload


def test_non_json_200_is_a_waterguru_error() -> None:
    client, _ = _client(_response("<html>gateway</html>"))
    with pytest.raises(WaterGuruError, match="not JSON"):
        client._invoke("prod-getDashboardView", {})


def test_non_object_200_is_a_waterguru_error() -> None:
    client, _ = _client(_response("[1, 2, 3]"))
    with pytest.raises(WaterGuruError, match="unexpected response type"):
        client._invoke("prod-getDashboardView", {})


def test_http_error_still_fails() -> None:
    client, _ = _client(_response('{"message": "nope"}', status_code=500))
    with pytest.raises(WaterGuruError, match="HTTP 500"):
        client._invoke("prod-getDashboardView", {})


def test_invoke_requires_authentication_first() -> None:
    client = WaterGuruClient("user@example.test", "pw")
    with pytest.raises(WaterGuruError, match="authenticate"):
        client._invoke("prod-getDashboardView", {})


def test_diagnostics_are_bounded_and_never_include_the_request_body() -> None:
    huge = "x" * 5000
    body = json.dumps({"errorMessage": huge, "errorType": "RuntimeError"})
    client, _ = _client(_response(body))
    with pytest.raises(WaterGuruError) as excinfo:
        client._invoke("prod-managePod", {"password": "super-secret-password"})
    message = str(excinfo.value)
    assert huge not in message
    assert len(message) < 1000
    assert "super-secret-password" not in message


def test_function_error_header_diagnostics_are_bounded() -> None:
    client, _ = _client(
        _response("y" * 5000, headers={"X-Amz-Function-Error": "z" * 5000})
    )
    with pytest.raises(WaterGuruError) as excinfo:
        client._invoke("prod-managePod", {})
    assert len(str(excinfo.value)) < 1000


def test_function_error_header_does_not_echo_unrelated_body_fields() -> None:
    """A function-error body can carry secrets; only allowlisted keys may be reported."""
    body = json.dumps(
        {
            "errorMessage": "handler exploded",
            "errorType": "java.lang.RuntimeException",
            "password": "super-secret-sentinel",
            "credentials": {"token": "nested-secret-sentinel"},
            "request": {"email": "pii-sentinel@example.test"},
        }
    )
    client, _ = _client(_response(body, headers={"X-Amz-Function-Error": "Unhandled"}))
    with pytest.raises(WaterGuruError) as excinfo:
        client._invoke("prod-getDashboardView", {})
    message = str(excinfo.value)
    assert "handler exploded" in message
    assert "java.lang.RuntimeException" in message
    assert "super-secret-sentinel" not in message
    assert "nested-secret-sentinel" not in message
    assert "pii-sentinel@example.test" not in message


def test_function_error_header_non_json_body_is_reported_by_length_only() -> None:
    body = "upstream boom password=super-secret-sentinel token=abc123"
    client, _ = _client(_response(body, headers={"X-Amz-Function-Error": "Unhandled"}))
    with pytest.raises(WaterGuruError) as excinfo:
        client._invoke("prod-managePod", {})
    message = str(excinfo.value)
    assert "super-secret-sentinel" not in message
    assert "abc123" not in message
    assert "not echoed" in message
    assert str(len(body)) in message


def test_function_error_header_reports_code_and_type_only() -> None:
    body = json.dumps({"errorType": "BadRequest", "code": "BAD_REQ", "junk": "ignored"})
    client, _ = _client(_response(body, headers={"X-Amz-Function-Error": "Unhandled"}))
    with pytest.raises(WaterGuruError) as excinfo:
        client._invoke("prod-managePod", {})
    message = str(excinfo.value)
    assert "BadRequest" in message
    assert "BAD_REQ" in message
    assert "ignored" not in message
