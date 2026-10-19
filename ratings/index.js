'use strict';

const JSON_HEADERS = { 'content-type': 'application/json' };

function jsonResponse(statusCode, payload) {
  return {
    statusCode,
    headers: { ...JSON_HEADERS },
    body: JSON.stringify(payload),
  };
}

function parseProductId(value) {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) {
    return null;
  }

  const productId = Number(value);
  return Number.isSafeInteger(productId) ? productId : null;
}

async function handler(event) {
  const method = event?.requestContext?.http?.method;
  const path = event?.rawPath;

  if (typeof method !== 'string' || typeof path !== 'string') {
    return jsonResponse(400, { error: 'invalid API Gateway HTTP API v2 event' });
  }

  if (path === '/health' || path === '/health/') {
    if (method !== 'GET') {
      return jsonResponse(405, { error: 'method not allowed' });
    }
    return jsonResponse(200, { status: 'Ratings is healthy' });
  }

  const ratingsPath = /^\/ratings\/([^/]*)\/?$/.exec(path);
  if (ratingsPath) {
    if (method !== 'GET') {
      return jsonResponse(405, { error: 'method not allowed' });
    }

    const productId = parseProductId(ratingsPath[1]);
    if (productId === null) {
      return jsonResponse(400, { error: 'please provide numeric product ID' });
    }

    return jsonResponse(200, {
      id: productId,
      ratings: {
        Reviewer1: 5,
        Reviewer2: 4,
      },
    });
  }

  return jsonResponse(404, { error: 'not found' });
}

module.exports = { handler };
