# Repository Rules and Skills

CodeAgent reads repository customization in the IntelliJ JVM capability gateway. Files must resolve inside the current project; symlinks that escape the project are ignored. Validated content is sent to the deployed backend for prompt composition only when a run starts.

## Rules

Place always-on project instructions in direct Markdown children of `.codeagent/rules/`:

```text
.codeagent/
  rules/
    testing.md
    architecture.md
```

```markdown
# Testing

Add a regression test for every bug fix. Use the repository's existing test framework.
```

Rules are sorted by path and included in every Agent and Ask run. Use them for stable repository conventions, not task-specific requests.

## Skills

Place reusable task methods in either supported location:

```text
.codeagent/skills/release/SKILL.md
.agents/skills/review/SKILL.md
```

```markdown
# Release workflow

Run the full verification suite, inspect the packaged artifact, and confirm CI before creating a tag.
```

The first level-one heading becomes the display name. The first non-heading body line becomes the description. YAML front matter may be present, but discovery metadata is intentionally derived from the Markdown body.

Skills are disabled by default and selected per task from the library button beside the prompt mode control. A task can enable at most eight skills. Select **Refresh rules and skills** after adding, removing, or renaming files.

## Limits and authority

- Up to 32 rules and 64 discovered skills are listed.
- Each file is read up to 16,000 characters.
- Rule content receives a 24,000-character prompt budget per run.
- Enabled skills receive a separate 32,000-character prompt budget per run.
- Root `AGENTS.md`, Rules, and Skills are lower priority than packaged safety and mode policy.
- Rules and Skills cannot add tools, bypass mutation approval, access credentials, or grant Ask mode write access.
