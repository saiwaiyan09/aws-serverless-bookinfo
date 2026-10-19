import base64
import binascii
import json
from collections.abc import Mapping

from werkzeug.test import EnvironBuilder
from werkzeug.wrappers import Response

from productpage import app


TEXTUAL_MEDIA_TYPES = {
    "application/javascript",
    "application/json",
    "application/xml",
    "image/svg+xml",
}


def lambda_handler(event, context):
    if not isinstance(event, Mapping) or event.get("version") != "2.0":
        return _error_response(400, "Expected an API Gateway HTTP API v2.0 event")

    request_context = event.get("requestContext")
    http_context = request_context.get("http") if isinstance(request_context, Mapping) else None
    if not isinstance(http_context, Mapping):
        return _error_response(400, "Invalid API Gateway HTTP API request context")

    method = http_context.get("method")
    path = event.get("rawPath")
    if not isinstance(method, str) or not isinstance(path, str) or not path.startswith("/"):
        return _error_response(400, "Invalid API Gateway HTTP API request")
    raw_query_string = event.get("rawQueryString", "")
    if not isinstance(raw_query_string, str):
        return _error_response(400, "Invalid API Gateway query string")

    event_headers = event.get("headers") or {}
    if not isinstance(event_headers, Mapping):
        return _error_response(400, "Invalid API Gateway request headers")
    headers = {
        str(name): str(value)
        for name, value in event_headers.items()
        if isinstance(name, str) and value is not None
    }
    cookies = event.get("cookies")
    if isinstance(cookies, list) and cookies:
        headers["Cookie"] = "; ".join(str(cookie) for cookie in cookies)

    body = event.get("body")
    if body is None:
        request_body = None
    elif not isinstance(body, str):
        return _error_response(400, "Invalid API Gateway request body")
    elif event.get("isBase64Encoded"):
        try:
            request_body = base64.b64decode(body, validate=True)
        except (binascii.Error, ValueError):
            return _error_response(400, "Invalid base64 request body")
    else:
        request_body = body.encode("utf-8")

    host = headers.get("host")
    if not host and isinstance(request_context, Mapping):
        host = request_context.get("domainName")
    scheme = headers.get("x-forwarded-proto", "https").split(",", 1)[0].strip()
    if scheme not in ("http", "https"):
        scheme = "https"
    base_url = "{}://{}".format(scheme, host or "lambda.local")

    environ_builder = EnvironBuilder(
        path=path,
        query_string=raw_query_string,
        method=method,
        headers=headers,
        data=request_body,
        base_url=base_url,
    )
    try:
        response = Response.from_app(app, environ_builder.get_environ())
    finally:
        environ_builder.close()

    response_headers = {}
    response_cookies = []
    for name, value in response.headers.to_wsgi_list():
        if name.lower() == "set-cookie":
            response_cookies.append(value)
        elif name in response_headers:
            response_headers[name] += ", " + value
        else:
            response_headers[name] = value

    content_type = response.mimetype or ""
    response_body = response.get_data()
    is_binary = not (
        content_type.startswith("text/")
        or content_type in TEXTUAL_MEDIA_TYPES
        or content_type.endswith("+json")
        or content_type.endswith("+xml")
    )
    if is_binary:
        response_body = base64.b64encode(response_body).decode("ascii")
    else:
        charset = response.mimetype_params.get("charset", "utf-8")
        response_body = response_body.decode(charset)
    response.close()

    proxy_response = {
        "statusCode": response.status_code,
        "headers": response_headers,
        "body": response_body,
        "isBase64Encoded": is_binary,
    }
    if response_cookies:
        proxy_response["cookies"] = response_cookies
    return proxy_response


def _error_response(status_code, message):
    return {
        "statusCode": status_code,
        "headers": {"content-type": "application/json"},
        "body": json.dumps({"error": message}),
        "isBase64Encoded": False,
    }
