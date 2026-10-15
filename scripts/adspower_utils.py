#!/usr/bin/env python3
"""
AdsPower Local API utilities for Python scripts.
Handles browser start/stop and CDP WebSocket URL retrieval.
"""

import subprocess, json, time, sys
from typing import Optional

ADSPOWER_API_BASE = "http://127.0.0.1:50325"

# Known working profiles
PROFILE_IDS = {
    "k18a6v75": "k18a6v75",
    "k18a6j35": "k18a6j35",
    "k187fu14": "k187fu14",
    "k17bnqsi": "k17bnqsi",
    "kxpu63n": "kxpu63n",  # Permanently locked
}


def get_api_key() -> str:
    """Read AdsPower API key from config."""
    import os
    config_path = os.path.expanduser("~/.minimax/mcp.json")
    try:
        with open(config_path) as f:
            config = json.load(f)
            for c in config.get("mcpServers", []):
                if "adspower" in c.get("command", "").lower():
                    for arg in c.get("args", []):
                        if "--api-key" in arg:
                            return arg.split("=")[1].strip()
    except Exception:
        pass
    # Fallback: environment variable. The key is a real credential for the AdsPower
    # account, so it is never hardcoded here — a literal in source leaks to every clone.
    return os.environ.get("ADSPOWER_API_KEY", "")


def get_browser_status(profile_id: str) -> dict:
    """Get current browser status for a profile."""
    import httpx
    api_key = get_api_key()
    r = httpx.get(
        f"{ADSPOWER_API_BASE}/api/v1/browser/active",
        params={"profile_id": profile_id, "api-key": api_key},
        timeout=10
    )
    return r.json()


def _find_cdp_ws_by_port_scan() -> Optional[str]:
    """
    Scan common CDP ports to find an open AdsPower Chrome.
    Fallback when API is out of sync with actual browser state.
    """
    import subprocess, re, httpx
    try:
        result = subprocess.run(
            ["ss", "-tlnp"], capture_output=True, text=True, timeout=5
        )
        # Extract all listening ports
        ports = set()
        for line in result.stdout.split('\n'):
            if 'LISTEN' not in line:
                continue
            # Find port numbers (4-5 digits)
            for m in re.finditer(r':(\d{4,5})\b', line):
                ports.add(int(m.group(1)))
        
        # Try each port
        for port in sorted(ports):
            try:
                r = httpx.get(f"http://localhost:{port}/json/version", timeout=2)
                if r.status_code == 200:
                    data = r.json()
                    ua = data.get('User-Agent', '')
                    if 'Chrome/1' in ua and 'HeadlessChrome' not in ua:
                        ws_url = data.get('webSocketDebuggerUrl')
                        if ws_url:
                            return ws_url
            except:
                pass
    except Exception as e:
        print(f"Port scan error: {e}", file=sys.stderr)
    return None


def get_cdp_ws_url(profile_id: str) -> Optional[str]:
    """
    Get CDP WebSocket URL for an already-open browser profile.
    Returns None if browser is not running.
    Uses API first, falls back to port scan.
    """
    status = get_browser_status(profile_id)
    data = status.get("data", {})
    
    if data.get("status") == 0:
        ws = data.get("ws", {})
        url = ws.get("selenium") or ws.get("devtools")
        if url:
            return url
    
    # Fallback: scan for CDP ports
    return _find_cdp_ws_by_port_scan()


def get_browser_ws_url(profile_id: str, timeout: int = 0) -> Optional[str]:
    """
    Get CDP WS URL for a profile. If timeout > 0 and browser is not open,
    starts it and waits up to `timeout` seconds.
    """
    ws_url = get_cdp_ws_url(profile_id)
    if ws_url:
        return ws_url
    
    if timeout <= 0:
        return None
    
    if profile_id == "kxpu63n":
        raise RuntimeError("Profile kxpu63n is permanently locked")
    
    # Start browser via CLI
    print(f"Starting AdsPower browser {profile_id}...", file=sys.stderr)
    subprocess.run(
        ["npx", "--yes", "local-api-mcp-typescript", "open-browser", "--profile-id", profile_id],
        capture_output=True, text=True, timeout=30
    )
    
    start = time.time()
    while time.time() - start < timeout:
        ws_url = get_cdp_ws_url(profile_id)
        if ws_url:
            return ws_url
        time.sleep(2)
    
    raise RuntimeError(f"Browser {profile_id} did not start within {timeout}s")


# Alias for backwards compatibility
ensure_browser_open = lambda pid: get_browser_ws_url(pid, timeout=60)


def close_browser(profile_id: str):
    """Close a browser profile."""
    import httpx
    api_key = get_api_key()
    r = httpx.get(
        f"{ADSPOWER_API_BASE}/api/v1/browser/stop",
        params={"profile_id": profile_id, "api-key": api_key},
        timeout=10
    )
    return r.json()
