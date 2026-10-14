# ==============================================================================
# 📋 CONFIG — shared variables for all modules
# Usage in other modules: {{SPRING_PROFILES_ACTIVE}}, etc.
# ==============================================================================

SPRING_PROFILES_ACTIVE := env_var_or_default("SPRING_PROFILES_ACTIVE", "local")
