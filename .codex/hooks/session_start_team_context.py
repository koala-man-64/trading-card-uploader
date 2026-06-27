from hook_utils import (
    additional_context,
    branch_header,
    current_branch,
    dirty_summary,
    emit_json,
    repo_name,
    repo_root,
    skill_status,
)


def main() -> int:
    root = repo_root()
    _present, missing = skill_status(root)
    missing_text = ", ".join(missing) if missing else "none"
    header = branch_header(root) or current_branch(root)

    context = "\n".join(
        [
            "Codex compact workflow context:",
            f"- Repo: {repo_name(root)}",
            f"- Root: {root}",
            f"- Branch: {header}",
            f"- Working tree: {dirty_summary(root)}",
            f"- Core repo-local agents missing: {missing_text}",
            "- Follow AGENTS.md and prefer repo-local .codex/skills instructions.",
            "- Start substantive work through delivery-orchestrator-agent.",
            "- Use gateway-bookkeeper only for delegated, multi-repo, tracked PR/CI/CD, deployment, or Azure DevOps work.",
            "- Apply strict-branch-and-merge-discipline before edits, branches, commits, pushes, or PRs.",
            "- Classify shared API, schema, serialization, or mirrored contract-shape changes before editing.",
            "- Do not mutate external tracking systems unless explicitly requested or already in a tracked-work context.",
        ]
    )
    return emit_json(additional_context("SessionStart", context))


if __name__ == "__main__":
    raise SystemExit(main())
