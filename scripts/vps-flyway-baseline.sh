#!/usr/bin/env bash
# ==============================================================================
# Run Flyway baseline + migrate on the VPS database before the first deploy.
#
# The VPS database (91.188.212.209) already has the old .NET schema.
# Flyway in the container is configured with baseline-on-migrate=false,
# so it refuses to touch a non-empty schema until it has a baseline mark.
#
# This script must be run ONCE, before the first deploy. After that the normal
# deploy pipeline (ansible + compose) runs Flyway migrate automatically.
#
# Usage (from the project root on your local machine):
#   ssh user@91.188.212.39 "bash -s" < scripts/vps-flyway-baseline.sh
#
# Or interactively:
#   ssh user@91.188.212.39
#   docker exec wb-parser java -cp app.jar org.flywaydb.commandline.Main baseline -baselineVersion=1 -baselineDescription="NET schema" && \
#   docker exec wb-parser java -cp app.jar org.flywaydb.commandline.Main migrate
# ==============================================================================

set -euo pipefail

VPS_HOST="${VPS_HOST:-91.188.212.39}"
SSH_USER="${SSH_USER:-user}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/ci_caddy_vps}"

# One-shot Flyway baseline + migrate inside the running wb-parser container.
# The container must be running (docker exec needs a live process).
FLYWAY_SCRIPT='/tmp/flyway-run.sh'
REMOTE_CMD="cat > $FLYWAY_SCRIPT << 'SCRIPT'
set -e
JAR=\$(find /app -name 'app.jar' | head -1)
if [ -z \"\$JAR\" ]; then
  echo 'ERROR: app.jar not found in /app'
  exit 1
fi
echo 'Running Flyway baseline...'
java -cp \"\$JAR\" org.flywaydb.commandline.Main baseline \
    -baselineVersion=1 \
    -baselineDescription=\"NET schema baseline\"
echo 'Running Flyway migrate...'
java -cp \"\$JAR\" org.flywaydb.commandline.Main migrate
echo 'Done.'
SCRIPT
chmod +x $FLYWAY_SCRIPT
docker exec wb-parser bash $FLYWAY_SCRIPT"

echo "Connecting to $VPS_USER@$VPS_HOST..."
ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$VPS_USER@$VPS_HOST" "$REMOTE_CMD"
