require 'json'
require 'minitest/autorun'
require_relative '../details'

class DetailsHandlerTest < Minitest::Test
  def http_api_event(method, raw_path)
    {
      'version' => '2.0',
      'routeKey' => '$default',
      'rawPath' => raw_path,
      'rawQueryString' => '',
      'headers' => {},
      'requestContext' => {
        'http' => {
          'method' => method,
          'path' => raw_path,
          'protocol' => 'HTTP/1.1',
          'sourceIp' => '127.0.0.1',
          'userAgent' => 'unit-test'
        }
      },
      'isBase64Encoded' => false
    }
  end

  def invoke(method, path)
    lambda_handler(event: http_api_event(method, path), context: nil)
  end

  def test_get_details_zero_returns_original_book_information
    response = invoke('GET', '/details/0')

    assert_equal 200, response[:statusCode]
    assert_equal 'application/json', response[:headers]['content-type']
    assert_equal(
      {
        'id' => 0,
        'author' => 'William Shakespeare',
        'year' => 1595,
        'type' => 'paperback',
        'pages' => 200,
        'publisher' => 'PublisherA',
        'language' => 'English',
        'ISBN-10' => '1234567890',
        'ISBN-13' => '123-1234567890'
      },
      JSON.parse(response[:body])
    )
  end

  def test_get_health_reports_service_health
    response = invoke('GET', '/health')

    assert_equal 200, response[:statusCode]
    assert_equal({ 'status' => 'Details is healthy' }, JSON.parse(response[:body]))
  end

  def test_non_numeric_product_ids_return_bad_request
    response = invoke('GET', '/details/not-a-number')

    assert_equal 400, response[:statusCode]
    assert_equal(
      { 'error' => 'please provide numeric product ID' },
      JSON.parse(response[:body])
    )
  end

  def test_missing_product_id_returns_bad_request
    response = invoke('GET', '/details')

    assert_equal 400, response[:statusCode]
    assert_equal(
      { 'error' => 'please provide numeric product ID' },
      JSON.parse(response[:body])
    )
  end

  def test_unknown_routes_return_not_found
    response = invoke('GET', '/unknown')

    assert_equal 404, response[:statusCode]
    assert_equal({ 'error' => 'not found' }, JSON.parse(response[:body]))
  end

  def test_unsupported_methods_return_method_not_allowed
    response = invoke('POST', '/details/0')

    assert_equal 405, response[:statusCode]
    assert_equal({ 'error' => 'method not allowed' }, JSON.parse(response[:body]))
  end

  def test_handler_uses_http_api_v2_method_and_raw_path
    response = invoke('GET', '/details/12')

    assert_equal 200, response[:statusCode]
    assert_equal 12, JSON.parse(response[:body])['id']
  end

  def test_invalid_http_api_event_returns_bad_request
    response = lambda_handler(event: {}, context: nil)

    assert_equal 400, response[:statusCode]
    assert_equal(
      { 'error' => 'invalid API Gateway HTTP API v2 event' },
      JSON.parse(response[:body])
    )
  end
end
