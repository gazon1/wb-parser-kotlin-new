# ==============================================================================
# 📋 CONFIG — shared variables for all modules
# Usage in other modules: {{SPRING_PROFILES}}, etc.
# ==============================================================================

SPRING_PROFILES := env_var_or_default("SPRING_PROFILES", "local")
