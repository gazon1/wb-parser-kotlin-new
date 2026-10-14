# ==============================================================================
# 🚀 APP — build, run, docker
# Recipes available as app::<recipe>.
# SPRING_PROFILES can be set via env var (default: local).
# ==============================================================================

SPRING_PROFILES_ACTIVE := env_var_or_default("SPRING_PROFILES_ACTIVE", "local")

import 'build.just'
import 'run.just'
