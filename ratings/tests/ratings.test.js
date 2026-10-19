'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const { handler } = require('../index');

function httpApiEvent(method, rawPath) {
  return {
    version: '2.0',
    routeKey: '$default',
    rawPath,
    rawQueryString: '',
    headers: {},
    requestContext: {
      http: {
        method,
        path: rawPath,
        protocol: 'HTTP/1.1',
        sourceIp: '127.0.0.1',
        userAgent: 'unit-test',
      },
    },
    isBase64Encoded: false,
  };
}

test('GET /ratings/0 returns the default Bookinfo ratings', async () => {
  const response = await handler(httpApiEvent('GET', '/ratings/0'));

  assert.equal(response.statusCode, 200);
  assert.equal(response.headers['content-type'], 'application/json');
  assert.deepEqual(JSON.parse(response.body), {
    id: 0,
    ratings: {
      Reviewer1: 5,
      Reviewer2: 4,
    },
  });
});

test('GET /health reports the service as healthy', async () => {
  const response = await handler(httpApiEvent('GET', '/health'));

  assert.equal(response.statusCode, 200);
  assert.deepEqual(JSON.parse(response.body), {
    status: 'Ratings is healthy',
  });
});

test('unknown routes return 404', async () => {
  const response = await handler(httpApiEvent('GET', '/unknown'));

  assert.equal(response.statusCode, 404);
  assert.deepEqual(JSON.parse(response.body), { error: 'not found' });
});

test('non-numeric product IDs return 400', async () => {
  const response = await handler(httpApiEvent('GET', '/ratings/not-a-number'));

  assert.equal(response.statusCode, 400);
  assert.deepEqual(JSON.parse(response.body), {
    error: 'please provide numeric product ID',
  });
});

test('handler reads the HTTP API payload format 2.0 method and rawPath', async () => {
  const response = await handler(httpApiEvent('GET', '/ratings/12'));

  assert.equal(response.statusCode, 200);
  assert.deepEqual(JSON.parse(response.body), {
    id: 12,
    ratings: {
      Reviewer1: 5,
      Reviewer2: 4,
    },
  });
});

test('unsafe numeric product IDs return 400', async () => {
  const response = await handler(httpApiEvent('GET', '/ratings/9007199254740992'));

  assert.equal(response.statusCode, 400);
});
