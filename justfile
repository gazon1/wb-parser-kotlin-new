# =============================================================================
# wb-parser-kotlin — Build recipes
# Run `just --list` for all available commands.
# Run `just tests::check` before every commit.
#
# Module recipes: just <module>::<recipe>
#   just app::build bootJar
#   just app::run
#   just browser::test
#   just wbparser::test
#   just wbparser::lint
#   just tests::check
#   just deps::health
#   just scripts::refresh-decisions
#   just lint::autofix
# =============================================================================

mod config   '.just/config'
mod app      '.just/app'
mod browser  '.just/browser'
mod wbparser '.just/wbparser'
mod tests    '.just/tests'
mod scripts  '.just/scripts'
mod deploy   '.just/deploy'
mod catalog  '.just/catalog'
mod deps     '.just/deps'
mod lint     '.just/lint'

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

# ----- Deploy -----
alias d     := deploy::deploy

# ----- Module tests (domain + infrastructure merged into :wbparser) -----
alias td    := wbparser::test
alias ti    := wbparser::test-verbose

# ----- Lint shortcuts -----
alias ld    := wbparser::lint
alias li    := wbparser::lint
alias lf    := lint::autofix

# ----- Scripts shortcuts -----
alias rd    := scripts::refresh-decisions
