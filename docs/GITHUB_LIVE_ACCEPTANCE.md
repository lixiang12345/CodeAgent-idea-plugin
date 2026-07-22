# GitHub Live Acceptance Evidence

## Result

CodeAgent's GitHub read, review, Actions-control, and merge-readiness behavior
was exercised against the disposable public repository
[`lixiang12345/test`](https://github.com/lixiang12345/test) on 2026-07-22.
The live API behavior passed. The release gate remains **partial** because the
available credential was a broad classic personal access token rather than the
required repository-scoped fine-grained token.

Machine-readable evidence is committed in
`evaluation/github-live-acceptance.json`. No token, authorization header,
temporary Actions log URL, or downloaded log archive is recorded.

## Fixture

- Default branch: `main`.
- Test pull request: [`#1`](https://github.com/lixiang12345/test/pull/1).
- Required Check Run: `acceptance`, strict against the current base branch.
- Branch protection: one approval, stale-review dismissal, resolved
  conversations, and administrator enforcement.
- Active repository ruleset: `CodeAgent acceptance policy`.
- API version sent by CodeAgent and selected by GitHub: `2022-11-28`.

The fixture PR remains open and unmerged. It intentionally ends with a
requested-changes review and a merge conflict so an accidental merge cannot be
mistaken for a successful acceptance outcome.

## Live coverage

All reads went through the production `github_search` adapter in
`backend/src/integration-tools.mjs`:

| Area | Live result |
| --- | --- |
| Pull request, changed files, and commits | Passed |
| Reviews, line comments, and discussion comments | Passed |
| Head-SHA Check Runs | Passed |
| PR workflow runs, jobs, and failed steps | Passed |
| Temporary job-log redirect | Passed; URL deliberately not retained |
| Branch protection and repository rulesets | Passed |
| Merge-readiness audit | Passed |
| Bounded UTF-8 repository file read | Passed |

The production `github_manage` adapter submitted a `COMMENT` review with a
line-level comment on `acceptance.txt:4`. GitHub recorded the original commit
as `2d15faf0c9fe8f864157a35b8e8ee9d1a7f0f2f1`. A separate discussion comment
was also created and read back.

The production `github_actions_manage` adapter successfully exercised all five
supported disposable controls:

- rerun a complete workflow;
- rerun failed jobs;
- rerun one job;
- cancel a running workflow;
- force-cancel a separate running workflow.

## Merge safety

The production `github_merge_pull_request` adapter returned a pre-mutation
`409` for every tested unsafe state, and PR `#1` remained open:

- approved SHA no longer matched the live head;
- draft pull request;
- requested changes from `github-actions[bot]`;
- zero of one required approvals;
- missing required Check Run;
- failed required Check Run;
- `dirty` merge state caused by a real conflicting base-branch commit;
- required conversation resolution that the REST audit cannot prove.

Unit coverage separately asserts that these preflight failures never call the
GitHub merge endpoint. The live run corroborated the observable result: no
merge occurred and the protected branch remained intact.

## Credential limitation

The run used the GitHub CLI keyring's classic token. GitHub reported accepted
scope `repo` and the exact OAuth scopes recorded in the machine-readable
evidence. Those scopes are materially broader than CodeAgent needs, so this run
does not satisfy least-privilege credential acceptance.

To close the release gate, repeat this same suite with a fine-grained token
limited to `lixiang12345/test` and the permissions listed in
`docs/NEXT_STEPS.md`. The current evidence must remain `partial` until that
credential-only rerun succeeds.
