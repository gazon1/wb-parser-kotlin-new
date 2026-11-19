/* @meta
{
  "name": "wildberries/catalog-request",
  "description": "Inspect the Wildberries catalog request/response from a real Chrome session (read-only)",
  "domain": "www.wildberries.ru",
  "timeout": 60,
  "args": {
    "catalog": {"required": true, "description": "Catalog id (e.g. 131954) or a full catalog URL"},
    "page": {"required": false, "description": "Catalog page number (default 1)"},
    "query": {"required": false, "description": "Search token, e.g. 'menu_redirect_131954 колонки' (default: read from the catalog URL)"},
    "extraHeaders": {"required": false, "description": "JSON object of request headers to send on top of the defaults"}
  },
  "capabilities": ["network"],
  "readOnly": true,
  "example": "chrome-use site wildberries/catalog-request --catalog 131954 --query 'menu_redirect_131954 колонки' --json"
}
*/

async function (args) {
  // Fetch forbids page JavaScript from setting these headers: the browser owns them,
  // and a value passed here is dropped without an error. Listing them next to the
  // request is the point of this adapter — a header that looks sent but never is.
  // The names and the `proxy-` / `sec-` prefixes follow the Fetch Standard
  // ("forbidden request-header"); the list was checked against a real Chrome
  // request on the wire. `user-agent` is not on that list, but Chrome ignores a
  // page-supplied value for it, so it is listed here as well.
  const FORBIDDEN = [
    'accept-charset', 'accept-encoding', 'access-control-request-headers',
    'access-control-request-method', 'connection', 'content-length', 'cookie',
    'cookie2', 'date', 'dnt', 'expect', 'host', 'keep-alive', 'origin',
    'referer', 'set-cookie', 'te', 'trailer', 'transfer-encoding',
    'upgrade', 'via', 'user-agent'
  ];
  const FORBIDDEN_PREFIXES = ['proxy-', 'sec-'];
  const API_PATH = '__internal/u-search/exactmatch/ru/common/v18/search';
  const DEST = '1259570991';

  const raw = String(args.catalog || '').trim();
  const idMatch = raw.match(/catalog\/(\d+)/);
  const queryMatch = raw.match(/[?&]search=([^&]+)/);
  const catalogId = idMatch ? idMatch[1] : (/^\d+$/.test(raw) ? raw : null);
  if (!catalogId) {
    return {error: 'catalog_not_understood', hint: 'Pass a catalog id (131954) or a catalog URL'};
  }

  const page = Math.max(1, parseInt(args.page) || 1);
  const query = (args.query && String(args.query).trim()) ||
    (queryMatch ? decodeURIComponent(queryMatch[1].replace(/\+/g, ' ')) : '');

  const params = {
    ab_testing: 'false', appType: '1', curr: 'rub', dest: DEST,
    hide_vflags: '4294967296', lang: 'ru', locale: 'ru', page: String(page),
    query: query, resultset: 'catalog', sort: 'popular', spp: '30'
  };
  const qs = Object.keys(params).map((k) => k + '=' + encodeURIComponent(params[k])).join('&');
  const apiUrl = 'https://www.wildberries.ru/' + API_PATH + '?' + qs;

  // The same four headers the crawler sends (WbCatalogApi.buildWbHeaders), so the
  // adapter reproduces the production request instead of a different one: WB answers
  // 403 without `deviceid`.
  let headers = {
    'deviceid': 'site_a39361daf4ec45f2a68c5c6ca73c8adf',
    'x-requested-with': 'XMLHttpRequest',
    'x-spa-version': '14.28.3',
    'x-userid': '0'
  };
  if (args.extraHeaders) {
    try {
      headers = Object.assign(headers, JSON.parse(args.extraHeaders));
    } catch (e) {
      return {error: 'headers_not_json', message: e.message};
    }
  }

  const names = Object.keys(headers);
  const dropped = names.filter((h) => {
    const low = h.toLowerCase();
    return FORBIDDEN.indexOf(low) >= 0 ||
      FORBIDDEN_PREFIXES.some((prefix) => low.indexOf(prefix) === 0);
  });

  let resp;
  try {
    resp = await fetch(apiUrl, {method: 'GET', headers: headers, credentials: 'include'});
  } catch (e) {
    return {error: 'fetch_failed', message: String((e && e.message) || e)};
  }

  const text = await resp.text();
  const contentType = resp.headers.get('content-type') || '';

  const shape = {bodyBytes: text.length};
  try {
    // WB answers the catalog API with `text/plain`, so the content type says nothing
    // about the body: it is tried as JSON regardless.
    const data = JSON.parse(text);
    shape.isJson = true;
    shape.topLevelKeys = Object.keys(data);
    shape.productCount = Array.isArray(data.products) ? data.products.length : null;
    shape.total = typeof data.total === 'number' ? data.total : null;
    shape.name = data.metadata && data.metadata.name ? data.metadata.name : null;
  } catch (e) {
    shape.isJson = false;
    shape.parseError = e.message;
  }

  return {
    catalog: {id: catalogId, page: page, query: query},
    request: {
      url: apiUrl,
      method: 'GET',
      credentials: 'include',
      headersRequested: names,
      droppedByBrowser: dropped,
      sentByBrowser: names.filter((h) => dropped.indexOf(h) < 0)
    },
    response: {
      status: resp.status,
      ok: resp.ok,
      contentType: contentType,
      shape: shape
    }
  };
}
