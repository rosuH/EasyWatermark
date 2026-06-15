# Issue tracker: GitHub

Issues and PRDs for this repo live as GitHub issues in `rosuH/EasyWatermark`. Use the `gh` CLI for all operations.

## Conventions

- Create an issue: `gh issue create --repo rosuH/EasyWatermark --title "..." --body "..."`
- Read an issue: `gh issue view <number> --repo rosuH/EasyWatermark --comments`
- List issues: `gh issue list --repo rosuH/EasyWatermark --state open --json number,title,body,labels,comments`
- Comment on an issue: `gh issue comment <number> --repo rosuH/EasyWatermark --body "..."`
- Apply/remove labels: `gh issue edit <number> --repo rosuH/EasyWatermark --add-label "..."` / `--remove-label "..."`
- Close: `gh issue close <number> --repo rosuH/EasyWatermark --comment "..."`

When a skill says "publish to the issue tracker", create a GitHub issue.
