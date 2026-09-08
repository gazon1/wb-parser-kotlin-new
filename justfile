# =============================================================================
# wb-parser-kotlin — Build recipes
# Run `just --list` for all available commands.
# Run `just tests::check` before every commit.
#
# Module recipes: just <module>::<recipe>
#   just app::build bootJar
#   just app::run
#   just domain::test
#   just infra::test
#   just tests::check
#   just scripts::refresh-decisions
# =============================================================================

mod config   '.just/config'
mod app      '.just/app'
mod domain   '.just/domain'
mod infra    '.just/infra'
mod tests    '.just/tests'
mod scripts  '.just/scripts'

set shell := ["bash", "-uc"]
set unstable

# ==============================================================================
# 🎯 DEFAULT
# ==============================================================================
[doc('Show available commands')]
default:
    @just --list --list-heading $'🎯 Available Commands:\n' --list-prefix '  • '

# ==============================================================================
# Super-aliases
# ==============================================================================

# ----- Build / run -----
alias b     := app::build
alias br    := app::run
alias bdocker := app::docker-build
alias rdocker := app::run-docker

# ----- Tests shortcuts -----
alias tc    := tests::common
alias t     := tests::check
alias tclean := tests::clean

# ----- Module tests -----
alias td    := domain::test
alias ti    := infra::test

# ----- Lint shortcuts -----
alias ld    := domain::lint
alias li    := infra::lint

# ----- Scripts shortcuts -----
alias rd    := scripts::refresh-decisions
