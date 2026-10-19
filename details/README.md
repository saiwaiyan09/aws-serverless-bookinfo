# Details service on AWS Lambda

The Details service handles API Gateway HTTP API payload format 2.0 events. It
uses static book information and does not start an HTTP server.

## Lambda configuration

- Runtime: **Ruby 3.4** (`ruby3.4`, managed runtime on Amazon Linux 2023)
- Handler: **`details.lambda_handler`**
- Architecture: either x86_64 or arm64
- Dependencies: none; the handler uses Ruby's standard-library `json` module
- Environment variables: none required

The handler reads the HTTP method from
`requestContext.http.method` and the request path from `rawPath`.

## Endpoints

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/details/{productId}` | `200` with the static book details and requested numeric ID |
| `GET` | `/health` | `200` with `{"status":"Details is healthy"}` |
| `GET` | invalid details ID | `400` with a JSON error |
| `GET` | unknown path | `404` with a JSON error |
| Other | a recognized endpoint | `405` with a JSON error |

The details JSON retains the original fields: `id`, `author`, `year`, `type`,
`pages`, `publisher`, `language`, `ISBN-10`, and `ISBN-13`.

## Local testing

Run the unit tests from this directory with Ruby and Minitest:

```sh
ruby -Itest test/details_test.rb
```

Minitest is part of the standard Ruby distribution; no bundle installation is
needed.

## ZIP packaging

From this directory, create a ZIP with `details.rb` at the archive root:

```sh
zip function.zip details.rb
```

Upload `function.zip` to the Lambda function configured with runtime `ruby3.4`
and handler `details.lambda_handler`. This function code package contains no
third-party gems to vendor.

## Deferred functionality

The original optional Google Books integration is not included. Details are
static for every numeric product ID; external book lookups, a database, and a
per-product catalog are deferred. The former `ENABLE_EXTERNAL_BOOK_SERVICE`
and `DO_NOT_ENCRYPT` settings are not used.
