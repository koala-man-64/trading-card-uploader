# AGENTS.md instructions for C:\Users\rdpro\Projects\trading-card-uploader

## Source of Truth

Repo-local Codex agents live under `.codex/skills`. Keep `.agent/skills` mirrored for compatibility and `.claude/agents` mirrored for Claude-side prompts when updating the shared agent inventory.

The reusable agent set is synced from `asset-allocation-ui` with trading and finance-specific agents intentionally excluded.

## Workflow Defaults

- Use `delivery-orchestrator-agent` for delegated, multi-step, risky, cross-repo, or tracked work.
- Use `gateway-bookkeeper` only for delegated, multi-repo, tracked PR/CI/CD, deployment, or Azure DevOps work. Default to read-only tracking unless mutation is explicitly requested or already in scope.
- Apply `strict-branch-and-merge-discipline` before edits, branches, commits, pushes, or PRs.
- Do not infer commit, push, PR, or merge authority from ordinary file-editing work.
- Classify shared API, schema, serialization, generated-client, or mirrored contract-shape changes before editing; route owner-side contract changes through the owning API, package, or repository.

## Hooks

Codex hooks live in `.codex/hooks.json` and `.codex/hooks`. Keep routing hooks generic for this repo; do not reintroduce asset-allocation or trading-specific lanes.
