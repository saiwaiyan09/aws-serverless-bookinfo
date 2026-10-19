package application;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ReviewsHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private static final Logger LOGGER = Logger.getLogger(ReviewsHandler.class.getName());
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final String RATINGS_UNAVAILABLE = "Ratings service is currently unavailable";
    private static final String REVIEWER_1_TEXT =
            "An extremely entertaining play by Shakespeare. The slapstick humour is refreshing!";
    private static final String REVIEWER_2_TEXT =
            "Absolutely fun and entertaining. The play lacks thematic depth when compared to other plays by Shakespeare.";
    private static final List<String> TRACE_HEADERS = Arrays.asList(
            "x-request-id",
            "x-ot-span-context",
            "x-datadog-trace-id",
            "x-datadog-parent-id",
            "x-datadog-sampling-priority",
            "traceparent",
            "tracestate",
            "x-cloud-trace-context",
            "grpc-trace-bin",
            "x-b3-traceid",
            "x-b3-spanid",
            "x-b3-parentspanid",
            "x-b3-sampled",
            "x-b3-flags",
            "sw8"
    );

    private final String ratingsBaseUrl;
    private final String starColor;
    private final String podName;
    private final String clusterName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ReviewsHandler() {
        this(
                System.getenv("RATINGS_BASE_URL"),
                System.getenv("STAR_COLOR"),
                System.getenv("HOSTNAME"),
                System.getenv("CLUSTER_NAME"),
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
                new ObjectMapper()
        );
    }

    ReviewsHandler(
            String ratingsBaseUrl,
            String starColor,
            String podName,
            String clusterName,
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.ratingsBaseUrl = ratingsBaseUrl;
        this.starColor = starColor == null || starColor.isEmpty() ? "black" : starColor;
        this.podName = podName == null ? "null" : podName;
        this.clusterName = clusterName == null ? "null" : clusterName;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        if (event == null || !"2.0".equals(event.get("version"))) {
            return response(400, errorBody("invalid HTTP API payload version"));
        }

        Map<?, ?> requestContext = asMap(event.get("requestContext"));
        Map<?, ?> http = asMap(requestContext.get("http"));
        Object methodValue = http.get("method");
        Object pathValue = event.get("rawPath");
        if (!(methodValue instanceof String) || !(pathValue instanceof String)) {
            return response(400, errorBody("invalid HTTP API request"));
        }

        String method = (String) methodValue;
        String path = (String) pathValue;
        boolean healthRoute = "/health".equals(path);
        String productId = productIdFromPath(path);
        boolean reviewsRoute = productId != null;

        if (!healthRoute && !reviewsRoute) {
            return response(404, errorBody("not found"));
        }
        if (!"GET".equals(method)) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("allow", "GET");
            return response(405, errorBody("method not allowed"), headers);
        }
        if (healthRoute) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "Reviews is healthy");
            return response(200, body);
        }
        if (!productId.matches("[0-9]+")) {
            return response(400, errorBody("please provide numeric product ID"));
        }

        try {
            productId = Integer.toString(Integer.parseInt(productId));
        } catch (NumberFormatException exception) {
            return response(400, errorBody("please provide numeric product ID"));
        }

        Map<String, Integer> ratings = fetchRatings(productId, asMap(event.get("headers")));
        return response(200, reviewsBody(productId, ratings));
    }

    private Map<String, Object> reviewsBody(String productId, Map<String, Integer> ratings) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", productId);
        body.put("podname", podName);
        body.put("clustername", clusterName);

        List<Map<String, Object>> reviews = new ArrayList<>();
        reviews.add(review("Reviewer1", REVIEWER_1_TEXT, ratings.get("Reviewer1")));
        reviews.add(review("Reviewer2", REVIEWER_2_TEXT, ratings.get("Reviewer2")));
        body.put("reviews", reviews);
        return body;
    }

    private Map<String, Object> review(String reviewer, String text, Integer stars) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reviewer", reviewer);
        result.put("text", text);
        Map<String, Object> rating = new LinkedHashMap<>();
        if (stars == null) {
            rating.put("error", RATINGS_UNAVAILABLE);
        } else {
            rating.put("stars", stars);
            rating.put("color", starColor);
        }
        result.put("rating", rating);
        return result;
    }

    private Map<String, Integer> fetchRatings(String productId, Map<?, ?> requestHeaders) {
        try {
            URI ratingsUri = ratingsUri(productId);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(ratingsUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("accept", "application/json")
                    .GET();
            addTraceHeaders(requestBuilder, requestHeaders);

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                LOGGER.warning("Ratings service returned HTTP status " + response.statusCode());
                return Collections.emptyMap();
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            JsonNode ratingsJson = responseJson == null ? null : responseJson.get("ratings");
            if (ratingsJson == null || !ratingsJson.isObject()) {
                LOGGER.warning("Ratings service returned an invalid response body");
                return Collections.emptyMap();
            }

            Map<String, Integer> ratings = new LinkedHashMap<>();
            addRating(ratings, ratingsJson, "Reviewer1");
            addRating(ratings, ratingsJson, "Reviewer2");
            return ratings;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Interrupted while contacting Ratings service", exception);
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.log(Level.WARNING, "Unable to contact or parse Ratings service response", exception);
        }
        return Collections.emptyMap();
    }

    private URI ratingsUri(String productId) {
        if (ratingsBaseUrl == null || ratingsBaseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("RATINGS_BASE_URL is not configured");
        }
        URI baseUri = URI.create(ratingsBaseUrl.trim());
        if (!"https".equalsIgnoreCase(baseUri.getScheme())
                || baseUri.getHost() == null
                || baseUri.getQuery() != null
                || baseUri.getFragment() != null) {
            throw new IllegalArgumentException("RATINGS_BASE_URL must be an HTTPS base URL");
        }
        String base = ratingsBaseUrl.trim().replaceAll("/+$", "");
        return URI.create(base + "/ratings/" + productId);
    }

    private void addTraceHeaders(HttpRequest.Builder requestBuilder, Map<?, ?> requestHeaders) {
        for (String traceHeader : TRACE_HEADERS) {
            Object value = headerValue(requestHeaders, traceHeader);
            if (value instanceof String && !((String) value).isEmpty()) {
                requestBuilder.header(traceHeader, (String) value);
            }
        }
    }

    private Object headerValue(Map<?, ?> headers, String name) {
        for (Map.Entry<?, ?> header : headers.entrySet()) {
            if (header.getKey() instanceof String && name.equalsIgnoreCase((String) header.getKey())) {
                return header.getValue();
            }
        }
        return null;
    }

    private void addRating(Map<String, Integer> ratings, JsonNode ratingsJson, String reviewer) {
        JsonNode value = ratingsJson.get(reviewer);
        if (value != null && value.isIntegralNumber() && value.canConvertToInt()) {
            ratings.put(reviewer, value.intValue());
        }
    }

    private String productIdFromPath(String path) {
        if (!path.startsWith("/reviews/")) {
            return null;
        }
        String productId = path.substring("/reviews/".length());
        return productId.contains("/") ? null : productId;
    }

    private Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }

    private Map<String, Object> response(int statusCode, Map<String, Object> body) {
        return response(statusCode, body, Collections.emptyMap());
    }

    private Map<String, Object> response(
            int statusCode,
            Map<String, Object> body,
            Map<String, String> additionalHeaders
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.putAll(additionalHeaders);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", statusCode);
        response.put("headers", headers);
        try {
            response.put("body", objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Reviews response", exception);
        }
        response.put("isBase64Encoded", false);
        return response;
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<?, ?>) value : Collections.emptyMap();
    }
}
