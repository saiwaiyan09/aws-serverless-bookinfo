# Productpage Lambda

The original Flask application is retained. `lambda_handler.lambda_handler`
adapts API Gateway HTTP API payload format 2.0 events to Flask's WSGI
application using Werkzeug, which is already installed as a Flask dependency.
It does not start a web server and does not require an ASGI conversion or
container image.

The adapter preserves HTTP status codes and headers, serializes HTML/text
responses as UTF-8, and base64-encodes binary responses such as PNG images.
API Gateway v2 cookies are mapped to and from Flask's session cookies.

## Runtime, handler, and dependencies

- AWS Lambda runtime: `Python 3.13` (`python3.13`)
- Lambda architecture used by the ZIP build: `x86_64`
- Handler configuration: `lambda_handler.lambda_handler`
- Application: existing Flask app in `productpage.py`
- Dependencies: pinned direct runtime dependencies in
  `lambda-requirements.txt`, plus their compatible transitive dependencies.
  This retains Flask, Requests, JSON formatting, OpenTelemetry tracing, and
  Prometheus metrics used by the original service without bundling the legacy
  Gunicorn/gevent server packages.
- `json2html==1.3.0` is also packaged by the build script. PyPI provides it as
  a source-only, pure-Python package, so the script builds it separately from
  the target-specific binary wheels.

The ZIP contains the handler, Flask app, dependency packages, templates, and
static assets. It excludes tests and the legacy Docker server configuration.

## Routes preserved

The Flask application continues to provide:

| Method | Path | Behavior |
| --- | --- | --- |
| `GET` | `/` and `/index.html` | Original service overview page |
| `GET` | `/productpage` | Book details, reviews, and ratings page |
| `GET` | `/health` | Original health response |
| `POST` | `/login` | Demo username session |
| `GET` | `/logout` | Clears the demo username session |
| `GET` | `/api/v1/products` | Product list JSON |
| `GET` | `/api/v1/products/{product_id}` | Product details JSON |
| `GET` | `/api/v1/products/{product_id}/reviews` | Reviews JSON |
| `GET` | `/api/v1/products/{product_id}/ratings` | Direct Ratings JSON |
| `GET` | `/metrics` | Prometheus metrics |
| `GET` | `/static/{path}` | CSS, images, and other Flask static files |

The templates use Flask URL generation for their static resources. The
legacy asset `static/tailwind/tailwind.css` is actually Tailwind's
JavaScript browser runtime (confirmed from its file contents); the Lambda
serves that original URL with a JavaScript MIME type and the templates load
it as a script so it can generate the utility CSS. The page's inline CSS and
other textual responses are returned as text; `image/png` is returned with
API Gateway's base64 response flag.

## API Gateway routes

Attach an API Gateway HTTP API Lambda proxy integration using payload format
2.0 to the Productpage Lambda. To forward the whole Flask site, configure:

- `ANY /` for the root page.
- `ANY /{proxy+}` for `/productpage`, `/health`, login/logout, product APIs,
  metrics, and `/static/...`.

This catch-all is broad because it forwards unlisted Flask paths. The shared
API already has explicit Details, Reviews, and Ratings routes; API Gateway
selects more-specific routes ahead of a greedy `{proxy+}` route. Review the
complete route table before adding it, and avoid duplicate routes or another
equally broad wildcard integration. A `$default` route is also a broad
fallback and should not be repointed without checking its existing target.
No API Gateway routes or integrations are created by this project change.

## Backend configuration and request flow

Set all three HTTPS base URLs in Lambda:

- `DETAILS_BASE_URL`: Details API base URL.
- `REVIEWS_BASE_URL`: Reviews API base URL.
- `RATINGS_BASE_URL`: Ratings API base URL.

For this project, each value is the API Gateway base URL, for example
`https://2jw7xj78g8.execute-api.ap-southeast-1.amazonaws.com`. The Productpage
calls:

- `GET {DETAILS_BASE_URL}/details/{productId}`
- `GET {REVIEWS_BASE_URL}/reviews/{productId}`
- `GET {RATINGS_BASE_URL}/ratings/{productId}` for the original direct Ratings
  JSON API route.

The homepage uses Details and Reviews, with Reviews supplying the displayed
ratings. Productpage's separate `/api/v1/products/{product_id}/ratings` route
continues to call Ratings directly. All requests use HTTPS, propagate the
original trace/session headers, and retain the existing 3-second Requests
timeout. There is no AWS SDK Lambda invocation.

For local legacy/container development only, absent base URL variables fall
back to the original internal service hostnames. In Lambda, missing or
non-HTTPS backend URLs cause initialization to fail instead of silently
falling back to internal HTTP service discovery.

## Session security

`FLASK_SECRET_KEY` is required in Lambda. Configure a long, random value in
the Lambda environment; it signs Flask's client-side session cookies and must
remain stable across execution environments. Do not commit the value. Lambda
session cookies are marked `Secure`, `HttpOnly`, and `SameSite=Lax`.

The inherited login page is a demonstration only: the `/login` handler stores
the submitted username and does not validate the password. It is not an
authentication system and must not protect sensitive functionality.
Requests with an absent or external referrer redirect to `/productpage`.

## Build and deploy the ZIP

Build on a system with Python/pip, `zip`, and network access to PyPI. The
build script downloads binary wheels compatible with the Lambda target
(Python 3.13, Linux x86_64 / manylinux2014), so the build host need not be
Amazon Linux:

```sh
cd productpage
./build_lambda.sh
```

To select a Python executable with a sufficiently recent pip:

```sh
PYTHON_BIN=/path/to/python ./build_lambda.sh
```

The artifact is `productpage/build/productpage-lambda.zip`. Upload that ZIP
manually to a Lambda function configured with runtime `Python 3.13`, x86_64
architecture, and handler `lambda_handler.lambda_handler`. Set the three
backend base URLs and `FLASK_SECRET_KEY` before invoking the function.

Build output is under `build/` and is excluded from version control.

## Tests

From `productpage/`, install the existing requirements and run:

```sh
pip install --require-hashes -r requirements.txt
pip install --require-hashes -r test-requirements.txt
python -m unittest discover tests/unit
```

The tests mock HTTP calls with `requests-mock`; they do not access AWS.

## Deferred behavior and limitations

- The login/session feature remains the original demonstration behavior, not
  production authentication.
- OpenTelemetry B3 extraction and request-header propagation are retained.
  No telemetry exporter or AWS tracing integration is configured here.
- `/metrics` is preserved, but Prometheus counters are in-memory per Lambda
  execution environment and are not a centralized metrics store.
- The legacy `FLOOD_FACTOR` load-generation option is still available, but
  enabling it increases backend requests and Lambda duration/cost.
- Productpage APIs and rendering use the original single product ID (`0`) for
  the main page. API paths continue to accept the requested product ID.
