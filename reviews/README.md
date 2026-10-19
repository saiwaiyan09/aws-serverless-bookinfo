# Reviews Lambda

The native Lambda entry point is `application.ReviewsHandler`. It accepts API
Gateway HTTP API payload format 2.0 events and returns a proxy response with
`statusCode`, `headers`, `body`, and `isBase64Encoded`. It does not start an
HTTP server and does not require Open Liberty at runtime.

## Endpoints

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/reviews/{productId}` | Returns the two original Bookinfo reviews and their Ratings results |
| `GET` | `/health` | Returns `{"status":"Reviews is healthy"}` |

Product IDs must be non-negative 32-bit integers. Invalid IDs return `400`,
unsupported methods on supported paths return `405`, and unknown paths return
`404`.

The reviews response preserves the original `id`, `podname`, `clustername`,
and `reviews` fields, including the original reviewer names and review text.
Rating successes contain `stars` and `color`; failed or missing Ratings data
uses the original `rating.error` shape instead of substituting a rating.
The Reviews endpoint returns `200` with these per-review errors when the
Ratings request fails, matching the legacy service's graceful degradation.

Example response for `GET /reviews/0` when Ratings returns its default data:

```json
{
  "id": "0",
  "podname": "null",
  "clustername": "null",
  "reviews": [
    {
      "reviewer": "Reviewer1",
      "text": "An extremely entertaining play by Shakespeare. The slapstick humour is refreshing!",
      "rating": {
        "stars": 5,
        "color": "black"
      }
    },
    {
      "reviewer": "Reviewer2",
      "text": "Absolutely fun and entertaining. The play lacks thematic depth when compared to other plays by Shakespeare.",
      "rating": {
        "stars": 4,
        "color": "black"
      }
    }
  ]
}
```

`podname` and `clustername` retain the legacy metadata fields. They default to
the string `"null"` when `HOSTNAME` or `CLUSTER_NAME` is not configured.

## Runtime, handler, and dependencies

- AWS Lambda runtime: `Java 21` (`java21`)
- Handler configuration: `application.ReviewsHandler`
- Java compilation target: Java 11 bytecode/API level
- Dependencies included in the ZIP:
  - `com.amazonaws:aws-lambda-java-core:1.2.3`
  - `com.fasterxml.jackson.core:jackson-databind:2.17.2`
  - Jackson's transitive runtime dependencies

The handler uses Java's built-in `java.net.http.HttpClient`, with a 2-second
connection timeout and a 3-second request timeout.

## Environment variables

Required:

- `RATINGS_BASE_URL`: HTTPS base URL of the Ratings HTTP API, for example
  `https://2jw7xj78g8.execute-api.ap-southeast-1.amazonaws.com`. The function
  calls `{RATINGS_BASE_URL}/ratings/{productId}`.

Optional:

- `STAR_COLOR`: rating star color; defaults to `black`.
- `HOSTNAME`, `CLUSTER_NAME`: optional values for the preserved response
  metadata fields. Lambda does not require these variables.

No credentials, account IDs, function ARNs, or endpoint URLs are embedded in
the Java code. Ratings calls are HTTPS requests through API Gateway; they do
not invoke Lambda directly. Request ID and distributed-tracing headers are
forwarded when present.

## Build, tests, and ZIP deployment

From the `reviews/` directory, with Gradle 8.13+ and JDK 17+ installed:

```sh
gradle :reviews-application:clean :reviews-application:test :reviews-application:lambdaZip
```

The ZIP artifact is:

```text
reviews-application/build/distributions/reviews-lambda.zip
```

It contains the application JAR at the ZIP root and runtime dependency JARs
under `lib/`, the standard Java Lambda ZIP layout. In the AWS Lambda console,
select runtime `Java 21`, upload this ZIP, and set the handler to
`application.ReviewsHandler`. Set `RATINGS_BASE_URL` in the function
environment before invoking `/reviews/{productId}`.

The existing reviews application WAR, Liberty configuration, integration
tests, and Docker build files are retained for the legacy workflow. The
Docker builder now uses JDK 17 so it can compile the Java 11-targeted code;
the legacy Liberty runtime remains unchanged.

## Deferred functionality and limitations

- The Lambda implementation is Reviews v2 behavior and always requests
  Ratings. The legacy `ENABLE_RATINGS=false` mode is not used by this handler.
- Legacy Liberty-only behavior, including server deployment tests and the
  container's runtime configuration, is retained but is not part of the
  Lambda ZIP.
- The original service's non-Lambda deployment variants and service-discovery
  configuration are not migrated. Ratings is reached only by the configured
  HTTPS API Gateway base URL.
- Failed, non-200, malformed, or incomplete Ratings responses produce the
  legacy per-review unavailable error. The response does not expose upstream
  error details to clients; the Lambda logs a warning for operational
  diagnosis.
