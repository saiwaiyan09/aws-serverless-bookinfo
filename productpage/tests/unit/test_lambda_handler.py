import base64
import json
import os
import unittest
from unittest.mock import patch

import requests
import requests_mock

from lambda_handler import lambda_handler
import productpage


API_BASE_URL = "https://bookinfo.example.test"
DETAILS_RESPONSE = {
    "id": 0,
    "title": "The Comedy of Errors",
    "descriptionHtml": "A Shakespeare comedy.",
    "ISBN-10": "1234567890",
    "publisher": "Example Publisher",
    "pages": 280,
    "type": "Paperback",
    "language": "English",
}
REVIEWS_RESPONSE = {
    "id": "0",
    "podname": "reviews-lambda",
    "clustername": "null",
    "reviews": [
        {
            "reviewer": "Reviewer1",
            "text": "A very funny play.",
            "rating": {"stars": 5, "color": "black"},
        },
        {
            "reviewer": "Reviewer2",
            "text": "A delightful comedy.",
            "rating": {"stars": 4, "color": "black"},
        },
    ],
}


class LambdaHandlerTest(unittest.TestCase):
    def setUp(self):
        self.environment = patch.dict(os.environ, {
            "DETAILS_BASE_URL": API_BASE_URL,
            "REVIEWS_BASE_URL": API_BASE_URL,
            "RATINGS_BASE_URL": API_BASE_URL,
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def invoke(self, method, path, headers=None, body=None, is_base64_encoded=False):
        event = {
            "version": "2.0",
            "routeKey": "$default",
            "rawPath": path,
            "rawQueryString": "",
            "headers": {
                "host": "bookinfo.example.test",
                "x-forwarded-proto": "https",
                **(headers or {}),
            },
            "requestContext": {
                "domainName": "bookinfo.example.test",
                "http": {
                    "method": method,
                    "path": path,
                    "protocol": "HTTP/1.1",
                    "sourceIp": "127.0.0.1",
                    "userAgent": "unit-test",
                },
            },
            "isBase64Encoded": is_base64_encoded,
        }
        if body is not None:
            event["body"] = body
        return lambda_handler(event, None)

    def test_root_returns_original_index_html(self):
        response = self.invoke("GET", "/")

        self.assertEqual(200, response["statusCode"])
        self.assertIn("text/html", response["headers"]["Content-Type"])
        self.assertIn("Hello! This is a simple bookstore application", response["body"])
        self.assertIn("/static/tailwind/tailwind.css", response["body"])
        self.assertIn("<script", response["body"])

    @requests_mock.Mocker()
    def test_productpage_renders_details_reviews_and_ratings(self, mock):
        mock.get(API_BASE_URL + "/details/0", json=DETAILS_RESPONSE)
        mock.get(API_BASE_URL + "/reviews/0", json=REVIEWS_RESPONSE)

        with patch("productpage.send_request", wraps=productpage.send_request) as send_request:
            response = self.invoke("GET", "/productpage")

        self.assertEqual(200, response["statusCode"])
        self.assertIn("The Comedy of Errors", response["body"])
        self.assertIn("1234567890", response["body"])
        self.assertIn("Reviewer1", response["body"])
        self.assertIn("A very funny play.", response["body"])
        self.assertIn("text-black-500", response["body"])
        self.assertEqual(
            ["https://bookinfo.example.test/details/0", "https://bookinfo.example.test/reviews/0"],
            [request.url for request in mock.request_history],
        )
        self.assertEqual(3.0, send_request.call_args_list[0].kwargs["timeout"])

    def test_health_returns_original_health_text(self):
        response = self.invoke("GET", "/health")

        self.assertEqual(200, response["statusCode"])
        self.assertEqual("Product page is healthy", response["body"])

    def test_tailwind_static_asset_is_served_as_javascript(self):
        response = self.invoke("GET", "/static/tailwind/tailwind.css")

        self.assertEqual(200, response["statusCode"])
        self.assertEqual("text/javascript", response["headers"]["Content-Type"].split(";")[0])
        self.assertFalse(response["isBase64Encoded"])
        self.assertTrue(response["body"].startswith("(()=>{"))

    def test_static_image_is_base64_encoded(self):
        response = self.invoke("GET", "/static/img/izzy.png")

        with open(os.path.join(os.path.dirname(__file__), "../../static/img/izzy.png"), "rb") as image:
            expected_image = image.read()
        self.assertEqual(200, response["statusCode"])
        self.assertEqual("image/png", response["headers"]["Content-Type"])
        self.assertTrue(response["isBase64Encoded"])
        self.assertEqual(expected_image, base64.b64decode(response["body"]))

    @requests_mock.Mocker()
    def test_direct_ratings_api_uses_ratings_base_url(self, mock):
        mock.get(API_BASE_URL + "/ratings/0", json={"id": 0, "ratings": {"Reviewer1": 5}})

        response = self.invoke("GET", "/api/v1/products/0/ratings")

        self.assertEqual(200, response["statusCode"])
        self.assertEqual({"id": 0, "ratings": {"Reviewer1": 5}}, json.loads(response["body"]))
        self.assertEqual(API_BASE_URL + "/ratings/0", mock.last_request.url)

    @requests_mock.Mocker()
    def test_backend_timeout_is_rendered_as_original_unavailable_message(self, mock):
        mock.get(API_BASE_URL + "/details/0", json=DETAILS_RESPONSE)
        mock.get(API_BASE_URL + "/reviews/0", exc=requests.Timeout)

        response = self.invoke("GET", "/productpage")

        self.assertEqual(200, response["statusCode"])
        self.assertIn("Error fetching product reviews", response["body"])
        self.assertIn("Sorry, product reviews are currently unavailable", response["body"])
        self.assertEqual(3, mock.call_count)
        self.assertEqual(
            2,
            sum(request.url == API_BASE_URL + "/reviews/0" for request in mock.request_history),
        )

    @requests_mock.Mocker()
    def test_backend_connection_error_returns_json_service_error(self, mock):
        mock.get(API_BASE_URL + "/details/12", exc=requests.ConnectionError)

        response = self.invoke("GET", "/api/v1/products/12")

        self.assertEqual(500, response["statusCode"])
        self.assertIn("product details are currently unavailable", response["body"])
        self.assertEqual(API_BASE_URL + "/details/12", mock.last_request.url)

    def test_v2_event_body_and_cookie_session(self):
        body = base64.b64encode(b"username=sample-user").decode("ascii")
        login_response = self.invoke(
            "POST",
            "/login",
            headers={"content-type": "application/x-www-form-urlencoded"},
            body=body,
            is_base64_encoded=True,
        )

        self.assertEqual(302, login_response["statusCode"])
        self.assertEqual("/productpage", login_response["headers"]["Location"])
        self.assertTrue(login_response["cookies"])
        cookie_header = login_response["cookies"][0].split(";", 1)[0]

        page_response = self.invoke("GET", "/productpage", headers={"cookie": cookie_header})
        self.assertEqual(200, page_response["statusCode"])
        self.assertIn("sample-user", page_response["body"])

    def test_invalid_route_returns_not_found(self):
        response = self.invoke("GET", "/not-a-productpage-route")

        self.assertEqual(404, response["statusCode"])

    def test_unsupported_method_returns_method_not_allowed(self):
        response = self.invoke("POST", "/health")

        self.assertEqual(405, response["statusCode"])

    def test_invalid_event_is_rejected(self):
        response = lambda_handler({"version": "1.0"}, None)

        self.assertEqual(400, response["statusCode"])
        self.assertFalse(response["isBase64Encoded"])


if __name__ == "__main__":
    unittest.main()
