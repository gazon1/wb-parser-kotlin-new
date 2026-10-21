# =============================================================================
# The AdsPower browser runtime.
#
# The crawler cannot fetch a Wildberries page without a real browser. This image
# is that browser: a virtual display (Xvfb), a window manager, the AdsPower CLI
# and the Chrome kernel it downloads.
#
# It is an image, and not something the VPS installs, for the same reason the
# crawler is an image: the VPS is not a build machine. It pulls this image from
# the registry and runs it.
#
# The Chrome kernel (520 MB) is NOT baked in. The runtime downloads it on first
# start, from AdsPower's own CDN, and keeps it in the `adspower-state` volume.
# Baking it would have put the AdsPower API key into a build layer (the kernel
# can only be fetched through a running runtime, which needs a key), and it
# would have made the image change every time AdsPower published a kernel.
#
# The CLI version is pinned: the runtime is the contract between the crawler and
# Wildberries, and "latest" is not a version. It is the same version the
# Caddy-vps role installs natively, so the two paths cannot drift apart.
# =============================================================================
FROM node:20-bookworm

ARG ADSPOWER_BROWSER_VERSION=2.1.1

RUN apt-get update && apt-get install -y \
    xvfb \
    x11vnc \
    fluxbox \
    dbus-x11 \
    libnss3 \
    libgbm1 \
    libasound2 \
    libatk-bridge2.0-0 \
    libgtk-3-0 \
    libx11-xcb1 \
    procps \
    net-tools \
    wget \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g "adspower-browser@${ADSPOWER_BROWSER_VERSION}"

WORKDIR /root
ENV DISPLAY=:99
# Where the CLI keeps the Chrome kernel and the per-profile browser directories.
# The compose file mounts a volume here, so the kernel survives a redeploy.
ENV ADSPOWER_HOME=/root/.adspowerCli

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
