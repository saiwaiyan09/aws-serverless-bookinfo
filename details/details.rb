#!/usr/bin/ruby
#
# Copyright Istio Authors
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

require 'json'

def lambda_handler(event:, context:)
  method = event.dig('requestContext', 'http', 'method')
  path = event['rawPath']

  unless method.is_a?(String) && path.is_a?(String)
    return json_response(400, 'error' => 'invalid API Gateway HTTP API v2 event')
  end

  if ['/health', '/health/'].include?(path)
    return json_response(405, 'error' => 'method not allowed') unless method == 'GET'

    return json_response(200, 'status' => 'Details is healthy')
  end

  details_path = %r{\A/details(?:/([^/]*))?/?\z}.match(path)
  return json_response(404, 'error' => 'not found') unless details_path
  return json_response(405, 'error' => 'method not allowed') unless method == 'GET'

  product_id = details_path[1]
  unless product_id&.match?(/\A[0-9]+\z/)
    return json_response(400, 'error' => 'please provide numeric product ID')
  end

  json_response(200, get_book_details(Integer(product_id, 10)))
end

def get_book_details(id)
  {
    'id' => id,
    'author' => 'William Shakespeare',
    'year' => 1595,
    'type' => 'paperback',
    'pages' => 200,
    'publisher' => 'PublisherA',
    'language' => 'English',
    'ISBN-10' => '1234567890',
    'ISBN-13' => '123-1234567890'
  }
end

def json_response(status_code, payload)
  {
    statusCode: status_code,
    headers: { 'content-type' => 'application/json' },
    body: JSON.generate(payload)
  }
end
