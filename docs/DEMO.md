# Independent project demo

This repository owns its complete website in `site/`. It has its own `index.html`, CSS, player and scenario data. There is no project switcher, shared runtime, build step, or dependency on another checkout. The existing Java services do not need to run.

## Local preview

From this repository:

```powershell
python -m http.server 18923 --bind 127.0.0.1 --directory site
```

Open the displayed loopback URL. Use `?lang=zh` for Chinese; the default is English. Press Play to start. Pause, replay, seek, speed controls and reduced motion are supported.

## Publish this repository's homepage

1. Commit and push `site/` and `.github/workflows/pages.yml` to this repository's `main` branch.
2. In GitHub, open **Settings → Pages → Build and deployment → Source**, and choose **GitHub Actions**.
3. Open **Actions → Publish demo to GitHub Pages → Run workflow** for the initial publication.
4. Use the URL shown by the successful deployment as the repository's Website link and the portfolio's demo link: [Lending Workflow demo](https://kennyufish.github.io/cloud-native-lending-workflow/). The Pages workflow's deployment status confirms whether a revision has been published.

Future pushes that change `site/` or the Pages workflow update this site. Only `site/` is uploaded: Java source, local guides, test evidence and configuration are excluded from the Pages artifact. This workflow is separate from backend CI and the AWS deploy/destroy workflows.

All asset URLs are relative, so the site works under GitHub Pages' repository path. No custom domain, secrets or paid hosting are required for a public repository. See [GitHub's official Pages workflow documentation](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).

## Verification

With Node, Python and Python Playwright installed:

```powershell
python scripts/check-site.py
```

The check serves a temporary isolated copy of only this repository's `site/` at a repository-prefixed URL. It exercises every scenario in both languages, source links, playback and completion, language continuity, keyboard seeking, reduced motion and four screen widths. It also checks that every asset stays inside the standalone site. Screenshots go to the ignored `target/site-check/` directory.

The demo is a source-based simulation. Benchmark figures, where shown, are committed local measurements; animation duration is not measured backend latency. Source links are pinned to the implementation revision.
