---
name: security-review
description: Security review checklist for security-sensitive changes — credentials, tokens, auth, data export, encryption. Use before merging any auth, encryption, or data-export change.
---

# Security Review

## When to use

Use this skill when reviewing a PR that touches:
- Credential storage (`SecureStoragePort`, `EncryptedSharedPreferences`)
- Auth flows (OAuth, token refresh, session management)
- Data export or backup file format
- Encryption or cipher changes
- New Android permissions
- Network calls that transmit credentials

Also use this skill when authoring a PR with such changes.

## Step-by-step

### Step 1 — Identify the change type

```
CHANGE TYPE
  │
  ├─── Credential storage → go to Credential Storage Checklist
  ├─── Auth flow → go to Auth Flow Checklist
  ├─── Data export / backup → go to Data Export Checklist
  ├─── Encryption change → go to Encryption Checklist
  ├─── New permission → go to Permission Checklist
  └─── Network call → go to Network Security Checklist
```

### Step 2 — Credential Storage Checklist

For any change that stores or retrieves credentials:

- [ ] Credentials are stored in `SecureStoragePort`, not plain DataStore or Room
- [ ] Credentials are never logged (check all `log.*` calls in the diff)
- [ ] Credentials are never in crash reporting (check no `Throwable` with credential fields)
- [ ] On app uninstall, credentials are wiped (Android: `MODE_PRIVATE` + encrypted storage)
- [ ] No hardcoded fallback credentials for "testing" in production code
- [ ] If using `EncryptedSharedPreferences`: key derived from user password or device-bound key

### Step 3 — Auth Flow Checklist

For OAuth, token refresh, session management:

- [ ] Tokens are stored in `SecureStoragePort`
- [ ] Token refresh handles 401 correctly (does not retry infinitely)
- [ ] Logout clears all tokens from memory and storage
- [ ] No token appears in URL query parameters
- [ ] No token in `WebView` local storage unless explicitly required
- [ ] State parameter used in OAuth to prevent CSRF

### Step 4 — Data Export / Backup Checklist

For backup file format, export API:

- [ ] Backup file is encrypted with user-derived key (not hardcoded)
- [ ] Backup file does not contain credentials (only user data)
- [ ] Backup decryption validates the key before writing
- [ ] Export does not bypass profile isolation
- [ ] Export includes only the current profile's data

### Step 5 — Encryption Checklist

For new cipher, key rotation, key derivation:

- [ ] Keys are stored in `SecureStoragePort`, not in code or config
- [ ] Key derivation uses PBKDF2 or Argon2 (not MD5, SHA1 for keys)
- [ ] IV/nonce is unique per encryption operation (never reused)
- [ ] Symmetric keys are not used across different data types
- [ ] If implementing key rotation: old key can decrypt, new key encrypts

### Step 6 — Permission Checklist

For new Android permissions:

- [ ] Permission is required for a user-visible feature (not "might be useful later")
- [ ] Permission rationale is shown in UI before the system permission dialog
- [ ] App handles `PERMISSION_DENIED` gracefully (no crash, no silent failure)
- [ ] Permission is not requested at app startup unless critical
- [ ] `POST_NOTIFICATIONS` is requested only when user takes a reminder-related action

### Step 7 — Network Security Checklist

For network calls that transmit credentials:

- [ ] Credentials sent only over HTTPS (no HTTP)
- [ ] No credentials in URL query parameters (use request body or headers)
- [ ] Certificate pinning is used for critical endpoints (auth, sync)
- [ ] No credentials in custom headers that might be logged by proxies
- [ ] Response errors do not echo credentials back to client

## What to do if a finding fails

1. **Block the PR** with a specific comment: which item failed and why
2. **Propose a fix** in the same comment
3. **If the risk is acceptable** (e.g., test-only code): document the exception and get explicit sign-off from senior engineer

## Common security mistakes

1. **Logging credentials** — `log.d("token", token)` compiles but is a critical vulnerability
2. **Storing tokens in SharedPreferences without encryption** — works in dev, trivial to extract in production
3. **Hardcoding test API keys** — test keys in code get extracted from APKs
4. **Not handling 401** — infinite retry loop on expired token
5. **Skipping permission rationale** — users deny the permission, app crashes
6. **Export bypasses profile isolation** — user A sees user B's data in exported backup

## Output template

When posting a security review:

```
## Security Review: PR-XXX

**Change type:** Credential storage / Auth flow / Data export / Encryption / Permission / Network

**Checklist:**
- [ ] Credential Storage Checklist: PASS
- [ ] Auth Flow Checklist: FAIL — tokens in URL query params
- [ ] Data Export Checklist: PASS
- [ ] Encryption Checklist: N/A
- [ ] Permission Checklist: N/A
- [ ] Network Security Checklist: FAIL — no cert pinning

**Blocking issues:**
1. **Tokens in URL**: `get("/api?token=...")` — move to Authorization header
2. **No cert pinning**: add pin for api.singularity.example.com

**Non-blocking recommendations:**
1. Consider adding logout confirmation dialog
```
