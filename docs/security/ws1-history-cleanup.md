# WS1 Secret History Cleanup Runbook

WS1 is not release-complete until the repository history has been rewritten and
rescanned. Current-tree deletion and ignore rules stop new leaks, but they do
not remove sensitive artifacts from earlier commits.

Run this only from a fresh disposable clone after the current worktree has been
committed or safely parked. Do not run history rewriting from a dirty working
copy.

## Scope

Remove committed demo artifacts and generated binaries from every reachable
commit:

- `code.txt`
- `app.jar`
- `uploads/`
- `.env`
- `.env.*`
- `application.properties`
- `application-*.properties`
- `*.pem`
- `*.key`
- `.trae/`

If gitleaks reports additional paths, add them to the same rewrite command
before publishing the rewritten history.

## Procedure

1. Announce a maintenance window and pause merges.
2. Create a fresh clone from the canonical remote.
3. Install `git-filter-repo` from the official package channel used by the
   team.
4. Rewrite all matching paths:

```powershell
git filter-repo `
  --path code.txt `
  --path app.jar `
  --path uploads/ `
  --path .env `
  --path .env.* `
  --path application.properties `
  --path-glob application-*.properties `
  --path-glob *.pem `
  --path-glob *.key `
  --path .trae/ `
  --invert-paths
```

5. Re-run the full WS1 gate from the rewritten clone:

```powershell
.\scripts\verify-ws1-security.ps1
```

6. Confirm these reports are empty or clean:

- `target/ws1-security/gitleaks-current.json`
- `target/ws1-security/gitleaks-history.json`
- `target/ws1-security/semgrep.json`
- `target/ws1-security/trivy.json`

7. Push the rewritten history after approvals:

```powershell
git push --force-with-lease --all
git push --force-with-lease --tags
```

8. Rotate any credentials that may have appeared in the old history. Treat
   rotation as mandatory even if scanners redact the value.
9. Ask every contributor and CI runner to reclone or reset to the rewritten
   branch tip.

## Release Evidence

Attach the following to the WS1 release record:

- the exact `git filter-repo` command used
- the rewritten commit SHA for `main`
- the clean `target/ws1-security/gitleaks-history.json` report
- the clean Semgrep and Trivy reports
- the credential rotation ticket references

Until this evidence exists, `.github/required-checks.yml` marks the secret
history rewrite as a release blocker.
