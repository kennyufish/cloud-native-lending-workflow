"""Verify this repository's standalone site with Node and Python Playwright."""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Thread
import json
import shutil
import subprocess

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass


def check():
    site = ROOT / 'site'
    for filename in ['demo.js', 'scenarios.js']:
        subprocess.run(['node', '--check', str(site / filename)], check=True)
    result = subprocess.run(
        ['node', '-e', "require('./scenarios.js'); console.log(JSON.stringify(DEMO))"],
        cwd=site, check=True, capture_output=True, encoding='utf-8',
    )
    project = json.loads(result.stdout)
    for scene in project['scenarios'].values():
        for step in scene['steps']:
            assert (ROOT / step['source']).is_file(), step['source']
    if 'auth' in project['scenarios']:
        raw = json.loads((ROOT / 'benchmarks/results/auth-load-test/auth-comparison.json').read_text(encoding='utf-8-sig'))
        metrics = project['scenarios']['auth']['evidence']
        for i, key in enumerate(['mean', 'percentile95']):
            assert float(metrics[i]['before']) == raw['results'][0]['statistics']['responseTimeMs'][key]
            assert float(metrics[i]['after']) == raw['results'][1]['statistics']['responseTimeMs'][key]
        raw = json.loads((ROOT / 'benchmarks/results/postgresql-18.4/index-comparison.json').read_text(encoding='utf-8-sig'))
        metric = project['scenarios']['index']['evidence'][0]
        assert float(metric['before']) == raw['beforeIndex']['medianExecutionTimeMs']
        assert float(metric['after']) == raw['afterIndex']['medianExecutionTimeMs']

    with TemporaryDirectory(prefix='standalone-demo-') as temp:
        shutil.copytree(site, Path(temp) / project['repo'])
        server = ThreadingHTTPServer(('127.0.0.1', 0), partial(QuietHandler, directory=temp))
        Thread(target=server.serve_forever, daemon=True).start()
        base = f'http://127.0.0.1:{server.server_port}/{project["repo"]}/'
        evidence = ROOT / 'target/site-check'
        evidence.mkdir(parents=True, exist_ok=True)
        errors, failures, asset_urls = [], [], []
        try:
            with sync_playwright() as pw:
                browser = pw.chromium.launch()
                page = browser.new_page(viewport={'width':1440, 'height':1000})
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.on('console', lambda message: errors.append(message.text) if message.type == 'error' else None)
                page.on('response', lambda response: failures.append(response.url) if response.status >= 400 else None)
                page.on('requestfailed', lambda request: failures.append(request.url))
                page.on('request', lambda request: asset_urls.append(request.url))
                for locale, language in [('en',0), ('zh',1)]:
                    for scene_id, scene in project['scenarios'].items():
                        page.goto(f'{base}?lang={locale}&scene={scene_id}')
                        assert page.locator('.project-nav').count() == 0
                        assert page.locator('.wordmark').inner_text() == 'Kenny Yu / ' + project['name']
                        assert page.locator('[data-scenario]').count() == len(project['scenarios'])
                        for i, step in enumerate(scene['steps']):
                            page.locator(f'[data-step="{i}"]').click()
                            assert page.locator('#step-title').inner_text() == step['title'][language]
                            assert page.locator('#diagram .node.active').count() == len(step['active'])
                            assert page.locator('#step-source').get_attribute('href').endswith(step['source'])
                        assert page.locator('#next').is_disabled()
                        page.locator('#timeline').evaluate('(el) => {el.value=el.max;el.dispatchEvent(new Event("input",{bubbles:true}));}')
                        assert page.locator('#play').inner_text() == ('↺ Replay' if locale == 'en' else '↺ 重播')
                        page.locator('#play').click()
                        page.wait_for_timeout(100)
                        assert page.locator('#step-number').inner_text() == '01'
                        page.locator('#play').click()
                for width, height in [(390,844), (768,1024), (1280,720), (1440,1000)]:
                    page.set_viewport_size({'width':width, 'height':height})
                    for locale in ['en','zh']:
                        page.goto(f'{base}?lang={locale}')
                        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), (width,locale)
                        assert page.locator('#play').is_visible()
                        assert page.locator('#step-source').evaluate('(el) => el.getBoundingClientRect().width') > 100
                        assert not page.locator('#diagram').evaluate('''svg => [...svg.querySelectorAll('text')].filter(el => {
                            const r=el.getBoundingClientRect(), b=svg.getBoundingClientRect();
                            return r.right>b.right+1 || r.left<b.left-1;
                        }).map(el=>el.textContent)'''), (width,locale)
                        if width in [390,1440]:
                            page.screenshot(path=str(evidence / f'{locale}-{width}.png'), full_page=True)
                page.goto(f'{base}?scene=invalid&lang=invalid')
                assert page.locator('#previous').is_disabled()
                page.locator('#play').click()
                page.wait_for_timeout(200)
                page.locator('#play').click()
                position = float(page.locator('#timeline').input_value())
                assert position > 0
                page.wait_for_timeout(100)
                assert float(page.locator('#timeline').input_value()) == position
                page.locator('#next').click()
                page.locator('#language').click()
                assert page.locator('#step-number').inner_text() == '02'
                page.locator('html').get_attribute('lang') == 'zh-CN'
                page.locator('#timeline').focus()
                page.keyboard.press('ArrowRight')
                assert float(page.locator('#timeline').input_value()) > 1
                page.locator('.wordmark').click()
                assert page.url.startswith(base + 'index.html?lang=zh')
                assert page.locator('#step-number').inner_text() == '01'
                page.emulate_media(reduced_motion='reduce')
                page.goto(base)
                point = page.locator('#packets g').first.get_attribute('transform')
                page.locator('#play').click()
                page.wait_for_timeout(200)
                assert page.locator('#packets g').first.get_attribute('transform') == point
                page.locator('#play').click()
                page.emulate_media(reduced_motion='no-preference')
                page.clock.install()
                page.goto(base)
                page.locator('#play').click()
                page.clock.run_for(100)
                count = len(next(iter(project['scenarios'].values()))['steps'])
                page.clock.fast_forward(count*6000+1000)
                assert page.locator('#play').inner_text() == '↺ Replay'
                assert float(page.locator('#timeline').input_value()) == count
                assert asset_urls and all(url.startswith(base) for url in asset_urls), asset_urls
                assert not errors, errors
                assert not failures, failures
                browser.close()
        finally:
            server.shutdown()
            server.server_close()
    count = sum(len(s['steps']) for s in project['scenarios'].values())
    print(f'PASS: {project["repo"]}, standalone repository path, {len(project["scenarios"])} scenarios, {count} bilingual steps, playback, source links, reduced motion, 4 viewports; all assets independent; no browser errors.')


if __name__ == '__main__':
    check()
