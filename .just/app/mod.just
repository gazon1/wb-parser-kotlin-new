# ==============================================================================
# 🚀 APP — build, run, docker
# Recipes available as app::<recipe>.
# SPRING_PROFILES can be set via env var (default: local).
# ==============================================================================

SPRING_PROFILES := env_var_or_default("SPRING_PROFILES", "local")

import 'build.just'
import 'run.just'
