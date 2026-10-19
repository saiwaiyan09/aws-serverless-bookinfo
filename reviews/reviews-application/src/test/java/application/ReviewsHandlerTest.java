package application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewsHandlerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private HttpClient httpClient;
    private ReviewsHandler handler;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        handler = new ReviewsHandler(
                "https://ratings.example.test",
                null,
                "test-host",
                "test-cluster",
                httpClient,
                OBJECT_MAPPER
        );
    }

    @Test
    void reviewsEndpointReturnsOriginalReviewsAndRatingsOverHttps() throws Exception {
        AtomicReference<HttpRequest> sentRequest = new AtomicReference<>();
        doAnswer(invocation -> {
            sentRequest.set(invocation.getArgument(0));
            return ratingsResponse(200, "{\"id\":0,\"ratings\":{\"Reviewer1\":5,\"Reviewer2\":4}}");
        }).when(httpClient).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        );

        Map<String, Object> event = event("GET", "/reviews/0");
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) event.get("headers");
        headers.put("x-request-id", "request-123");
        headers.put("traceparent", "00-abc-def-01");

        Map<String, Object> response = handler.handleRequest(event, null);
        JsonNode body = body(response);

        assertEquals(200, response.get("statusCode"));
        assertEquals("application/json", ((Map<?, ?>) response.get("headers")).get("content-type"));
        assertEquals("0", body.get("id").asText());
        assertEquals("test-host", body.get("podname").asText());
        assertEquals("test-cluster", body.get("clustername").asText());
        assertEquals(2, body.get("reviews").size());
        assertEquals("Reviewer1", body.at("/reviews/0/reviewer").asText());
        assertEquals(
                "An extremely entertaining play by Shakespeare. The slapstick humour is refreshing!",
                body.at("/reviews/0/text").asText()
        );
        assertEquals(5, body.at("/reviews/0/rating/stars").asInt());
        assertEquals("black", body.at("/reviews/0/rating/color").asText());
        assertEquals("Reviewer2", body.at("/reviews/1/reviewer").asText());
        assertEquals(
                "Absolutely fun and entertaining. The play lacks thematic depth when compared to other plays by Shakespeare.",
                body.at("/reviews/1/text").asText()
        );
        assertEquals(4, body.at("/reviews/1/rating/stars").asInt());
        assertEquals("https://ratings.example.test/ratings/0", sentRequest.get().uri().toString());
        assertEquals("GET", sentRequest.get().method());
        assertEquals(Duration.ofSeconds(3), sentRequest.get().timeout().orElseThrow());
        assertEquals("request-123", sentRequest.get().headers().firstValue("x-request-id").orElseThrow());
        assertEquals("00-abc-def-01", sentRequest.get().headers().firstValue("traceparent").orElseThrow());
        assertFalse((Boolean) response.get("isBase64Encoded"));
    }

    @Test
    void healthEndpointDoesNotCallRatings() throws Exception {
        Map<String, Object> response = handler.handleRequest(event("GET", "/health"), null);

        assertEquals(200, response.get("statusCode"));
        assertEquals("Reviews is healthy", body(response).get("status").asText());
        verify(httpClient, never()).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        );
    }

    @Test
    void invalidProductIdsReturnBadRequest() throws Exception {
        for (String productId : new String[]{"abc", "-1", "2147483648"}) {
            Map<String, Object> response = handler.handleRequest(event("GET", "/reviews/" + productId), null);

            assertEquals(400, response.get("statusCode"));
            assertEquals("please provide numeric product ID", body(response).get("error").asText());
        }
        verify(httpClient, never()).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        );
    }

    @Test
    void unknownRoutesReturnNotFound() throws Exception {
        Map<String, Object> response = handler.handleRequest(event("GET", "/unknown"), null);

        assertEquals(404, response.get("statusCode"));
        assertEquals("not found", body(response).get("error").asText());
    }

    @Test
    void unsupportedMethodsReturnMethodNotAllowed() throws Exception {
        Map<String, Object> response = handler.handleRequest(event("POST", "/reviews/0"), null);

        assertEquals(405, response.get("statusCode"));
        assertEquals("method not allowed", body(response).get("error").asText());
        assertEquals("GET", ((Map<?, ?>) response.get("headers")).get("allow"));
        verify(httpClient, never()).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        );
    }

    @Test
    void invalidPayloadVersionReturnsBadRequest() throws Exception {
        Map<String, Object> event = event("GET", "/health");
        event.put("version", "1.0");

        Map<String, Object> response = handler.handleRequest(event, null);

        assertEquals(400, response.get("statusCode"));
        assertEquals("invalid HTTP API payload version", body(response).get("error").asText());
    }

    @Test
    void ratingsHttpErrorsProducePerReviewRatingErrors() throws Exception {
        mockRatingsResponse(503, "{\"message\":\"unavailable\"}");

        Map<String, Object> response = handler.handleRequest(event("GET", "/reviews/0"), null);
        JsonNode body = body(response);

        assertEquals(200, response.get("statusCode"));
        assertEquals("Ratings service is currently unavailable", body.at("/reviews/0/rating/error").asText());
        assertEquals("Ratings service is currently unavailable", body.at("/reviews/1/rating/error").asText());
        assertTrue(body.at("/reviews/0/rating/stars").isMissingNode());
    }

    @Test
    void ratingsTimeoutProducesPerReviewRatingErrors() throws Exception {
        doThrow(new HttpTimeoutException("request timed out")).when(httpClient).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        );

        Map<String, Object> response = handler.handleRequest(event("GET", "/reviews/0"), null);

        assertEquals(200, response.get("statusCode"));
        assertEquals("Ratings service is currently unavailable", body(response).at("/reviews/0/rating/error").asText());
    }

    @Test
    void ratingsConnectionFailureProducesPerReviewRatingErrors() throws Exception {
        doThrow(new IOException("connection refused")).when(httpClient).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        );

        Map<String, Object> response = handler.handleRequest(event("GET", "/reviews/0"), null);

        assertEquals(200, response.get("statusCode"));
        assertEquals("Ratings service is currently unavailable", body(response).at("/reviews/1/rating/error").asText());
    }

    @Test
    void missingRatingForOneReviewerDoesNotInventAValue() throws Exception {
        mockRatingsResponse(200, "{\"ratings\":{\"Reviewer1\":5}}");

        Map<String, Object> response = handler.handleRequest(event("GET", "/reviews/0"), null);
        JsonNode body = body(response);

        assertEquals(5, body.at("/reviews/0/rating/stars").asInt());
        assertEquals("Ratings service is currently unavailable", body.at("/reviews/1/rating/error").asText());
    }

    private void mockRatingsResponse(int status, String responseBody) throws Exception {
        doAnswer(invocation -> ratingsResponse(status, responseBody)).when(httpClient).send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        );
    }

    private HttpResponse<String> ratingsResponse(int status, String responseBody) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(responseBody);
        return response;
    }

    private Map<String, Object> event(String method, String path) {
        Map<String, Object> http = new LinkedHashMap<>();
        http.put("method", method);
        http.put("path", path);
        Map<String, Object> requestContext = new LinkedHashMap<>();
        requestContext.put("http", http);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("version", "2.0");
        event.put("routeKey", "$default");
        event.put("rawPath", path);
        event.put("rawQueryString", "");
        event.put("headers", new LinkedHashMap<String, Object>());
        event.put("requestContext", requestContext);
        event.put("isBase64Encoded", false);
        return event;
    }

    private JsonNode body(Map<String, Object> response) throws Exception {
        return OBJECT_MAPPER.readTree((String) response.get("body"));
    }
}
