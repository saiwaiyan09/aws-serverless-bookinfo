# Ratings Lambda

The Lambda entry point is `index.handler`. It handles API Gateway HTTP API
payload format version 2.0 events and returns Lambda proxy responses with
`statusCode`, `headers`, and a JSON-stringified `body`.

## Supported endpoints

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/ratings/{productId}` | Returns the product ID and the default in-memory ratings |
| `GET` | `/health` | Returns `{"status":"Ratings is healthy"}` |

Product IDs must be non-negative safe integers. Invalid IDs return `400`;
unknown paths return `404`.

Example response for `GET /ratings/0`:

```json
{
  "id": 0,
  "ratings": {
    "Reviewer1": 5,
    "Reviewer2": 4
  }
}
```

The handler expects the HTTP API v2 fields `rawPath` and
`requestContext.http.method`. API Gateway supplies these as part of its
payload format 2.0 event.

## Runtime and dependencies

- AWS Lambda runtime: `nodejs22.x`
- Handler configuration: `index.handler`
- External npm dependencies: none
- Required environment variables: none

The handler uses only built-in Node.js functionality. It does not start an HTTP
server.

## Local tests

Use Node.js 18 or later, which includes the built-in test runner:

```sh
npm test
```

## ZIP packaging

From the `ratings/` directory, package the handler and manifest:

```sh
zip ../ratings-lambda.zip index.js package.json
```

No `node_modules` directory is needed. The ZIP is a deployment artifact and is
not committed by this project.

## Deferred functionality and legacy files

This initial Lambda version implements the default ratings behavior used by
Reviews v2: `Reviewer1` receives 5 stars and `Reviewer2` receives 4 stars. It
does not persist data between Lambda invocations.

The previous HTTP server supported in-memory `POST /ratings/{productId}`,
database-backed MySQL and MongoDB modes, and fault-injection variants
(`v-faulty`, `v-delayed`, `v-unavailable`, and `v-unhealthy`). These are
intentionally deferred. Their `SERVICE_VERSION`, `DB_TYPE`, database
connection variables, and associated integrations are not required by this
handler.

`ratings.js` and `Dockerfile` are the pre-migration server/container
implementation. They are not part of the Lambda ZIP and can be removed later
once the project no longer needs the legacy container workflow. The old
`ratings.js` imports `httpdispatcher`, `mysql`, and `mongodb`, which are no
longer declared in this Lambda package manifest; the old container workflow is
therefore not supported by this package configuration.
