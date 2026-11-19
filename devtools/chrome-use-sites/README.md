# chrome-use site adapters (development only)

Local [chrome-use](https://chrome-use.leeguoo.com/en/) site adapters used to inspect the
Wildberries storefront in a real, logged-in Chrome session.

They are **development tools**. Nothing here is part of the crawler runtime: the production
crawler talks to WB through Camoufox/Playwright (`:browser`), and chrome-use is never a
Gradle dependency.

## What is here

| Adapter | What it does |
|---|---|
| `wildberries/catalog-request` | Issues one read-only catalog API call from the page and reports the request URL, the headers it asked for, the headers the browser drops, and the shape of the response. |

## Install

The adapter source is this directory. Register it as an extra site source once:

```bash
chrome-use site add /path/to/wb-parser-kotlin/devtools/chrome-use-sites
chrome-use site update
```

`site update` syncs the official `chrome-use-sites` pack, the community `bb-sites` pack and
this directory into `~/.chrome-use/sites`. Keep this directory in the repository (not in
`~/.chrome-use/sites`): a sync rewrites the latter.

Check that it is installed:

```bash
chrome-use site info wildberries
```

## Run

```bash
# The catalog id and the search token the crawler uses for it
chrome-use site wildberries/catalog-request \
  --catalog 131954 \
  --query "menu_redirect_131954 колонки" \
  --json
```

A full catalog URL works too — the id and the search token are read from it:

```bash
chrome-use site wildberries/catalog-request \
  --catalog "https://www.wildberries.ru/catalog/131954/list.aspx?search=menu_redirect_131954%20%D0%BA%D0%BE%D0%BB%D0%BE%D0%BD%D0%BA%D0%B8" \
  --json
```

The adapter sends the same four headers the crawler sends — `deviceid`, `x-requested-with`,
`x-spa-version`, `x-userid` — so it reproduces the production request rather than a
different one.

`--extraHeaders` sends additional request headers as a JSON object. It is how the forbidden
headers are demonstrated: pass `cookie`, `user-agent`, `referer`, a `sec-ch-ua*` hint or a
`proxy-*` header and watch them land in `droppedByBrowser`.

```bash
chrome-use site wildberries/catalog-request --catalog 131954 \
  --extraHeaders '{"cookie":"wb_session=…","user-agent":"Mozilla/5.0 … Chrome/143"}' --json
```

## What the wire actually carries

The adapter reports the headers it *asked for*. The browser decides what is actually sent, so
the wire view comes from chrome-use's own network capture:

```bash
chrome-use network requests --clear
chrome-use site wildberries/catalog-request --catalog 131954 --json
chrome-use network requests --filter "u-search"      # find the request id
chrome-use network request <id> --json               # the headers that left the browser
```

Observed on 2026-11-19 against a real Chrome session:

| Header | Set by page JavaScript? | Note |
|---|---|---|
| `deviceid` | yes | WB answers 403 without it |
| `x-requested-with`, `x-userid`, `x-spa-version` | yes | carried by the storefront's own request |
| `user-agent`, `referer`, `sec-ch-ua*` | no | forbidden headers — the browser sets its own |
| `cookie`, `set-cookie` | no | forbidden header — dropped without an error |
| `proxy-*` | no | forbidden prefix — dropped without an error |
| `permissions-policy`, `http2-settings` | yes | not forbidden — they do reach WB |
| `__apiUrl`, `__catalogId` | yes | internal metadata, sent as headers — a leak |

The forbidden set is the Fetch Standard's "forbidden request-header": the listed names, the
`proxy-` and `sec-` prefixes, and the `X-HTTP-Method*` names when their value is a forbidden
method (`CONNECT`, `TRACE`, `TRACK`). `user-agent` is not on that list, but Chrome ignores a
page-supplied value for it.

`credentials: 'include'` on a same-origin request is what carries the session cookies; a
`Cookie` header built from the browser context never leaves the page.

## Never

The adapter is read-only. It does not touch the cart, orders, likes or the account, and it
never returns cookie values, tokens or personal data.
