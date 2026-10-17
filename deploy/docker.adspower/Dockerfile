FROM node:20-bookworm

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

RUN npm install -g adspower-browser

WORKDIR /root
ENV DISPLAY=:99

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]