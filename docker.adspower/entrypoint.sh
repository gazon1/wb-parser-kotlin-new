#!/bin/bash
set -e

# 1. Сброс старых сокетов X11
rm -f /tmp/.X*-lock /tmp/.X11-unix/X* 2>/dev/null || true

export DISPLAY=:99

echo "[1/4] Starting Xvfb on :99..."
Xvfb :99 -screen 0 1920x1080x24 -ac +extension GLX +render -noreset &
sleep 2

echo "[2/4] Starting Fluxbox..."
fluxbox > /tmp/fluxbox.log 2>&1 &
sleep 1

echo "[3/4] Starting x11vnc on 0.0.0.0:5900..."
# Запускаем x11vnc через nohup в фоновом режиме
nohup x11vnc -display :99 -forever -shared -rfbport 5900 -nopw -listen 0.0.0.0 > /tmp/x11vnc.log 2>&1 &
sleep 2

# Проверяем, поднялся ли x11vnc
if ps aux | grep -v grep | grep -q x11vnc; then
    echo "✅ x11vnc started successfully!"
else
    echo "❌ x11vnc failed to start! Log contents:"
    cat /tmp/x11vnc.log
    exit 1
fi

echo "[4/4] Starting AdsPower Runtime..."
if [ -n "$ADSPOWER_API_KEY" ]; then
    yes y | adspower-browser start -k "$ADSPOWER_API_KEY" || true
else
    echo "[!] WARNING: ADSPOWER_API_KEY is not set!"
fi

exec tail -f /dev/null