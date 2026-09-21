(() => {
  'use strict';
  const project = DEMO;
  const params = new URLSearchParams(location.search);
  let language = params.get('lang') === 'zh' ? 1 : 0;
  let scenarioId = Object.hasOwn(project.scenarios, params.get('scene')) ? params.get('scene') : Object.keys(project.scenarios)[0];
  let scenario, position = 0, playing = false, speed = 1, lastFrame = 0, frameId = 0, renderedStep = -1;
  const secondsPerStep = 6;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const narrow = matchMedia('(max-width: 500px)');
  const tr = value => Array.isArray(value) ? value[language] : value;
  const text = value => String(tr(value)).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const copy = (en, zh) => language ? zh : en;
  const github = path => `https://github.com/kennyufish/${project.repo}/blob/${project.revision}/${path}`;
  let elements, paths = [], nodeElements = [], packetElements = [];

  function syncUrl() {
    const url = new URL(location.href);
    url.searchParams.set('lang', language ? 'zh' : 'en');
    url.searchParams.set('scene', scenarioId);
    try { history.replaceState(null, '', url); } catch { /* Local file previews may restrict history updates. */ }
  }

  function renderPage() {
    scenario = project.scenarios[scenarioId];
    document.documentElement.lang = language ? 'zh-CN' : 'en';
    document.querySelector('.skip').textContent = copy('Skip to demo', '跳到演示');
    const langParam = language ? 'zh' : 'en';
    const evidence = scenario.evidence || project.evidence;
    document.querySelector('#app').innerHTML = `<div class="shell">
      <header class="topbar"><a class="wordmark" href="index.html?lang=${langParam}">Kenny Yu <span>/ ${text(project.name)}</span></a>
        <button class="language" id="language" aria-label="${copy('Switch to Chinese','切换到英文')}">${copy('中文','English')}</button>
      </header>
      <main id="main"><div class="intro"><div><p class="project-label">${text(project.label)}</p><h1>${tr(project.title)}</h1></div><div><p class="intro-summary">${text(project.intro)}</p><p class="tech">${text(project.tech)}</p></div></div>
      <section class="demo" aria-label="${copy('Interactive architecture walkthrough','交互式架构演示')}">
        <div class="demo-toolbar"><div class="scenarios" role="group" aria-label="${copy('Choose scenario','选择情景')}">${Object.entries(project.scenarios).map(([id,s]) => `<button type="button" data-scenario="${id}" aria-pressed="${id === scenarioId}">${text(s.name)}</button>`).join('')}</div><span class="simulation">${copy('SOURCE-BASED SIMULATION','基于源码的模拟演示')}</span></div>
        <div class="workspace"><div class="visual"><div class="scene-heading"><div class="scene-title">${text(scenario.title)}</div><span class="packet-key"><i></i>${copy('request / event','请求 / 事件')}</span></div>
          <svg class="diagram" id="diagram" role="img" aria-label="${text(scenario.title)}"></svg>
          <div class="visual-foot"><span>${text(scenario.footer)}</span><code>${copy('Time not to scale','时间非实际比例')}</code></div>
        </div><div class="narrative"><div class="step-meta"><span class="step-number" id="step-number">01</span><span id="step-total"></span></div><h2 id="step-title"></h2><p id="step-copy"></p><dl class="state-list" id="state"></dl><a class="source-link" id="step-source" target="_blank" rel="noopener">${copy('Inspect this step in source ↗','查看这一步的源码 ↗')}</a></div></div>
        <div class="player"><button class="play" id="play" type="button"></button><button class="step-button" id="previous" aria-label="${copy('Previous step','上一步')}" title="${copy('Previous step','上一步')}">←</button><button class="step-button" id="next" aria-label="${copy('Next step','下一步')}" title="${copy('Next step','下一步')}">→</button><div class="timeline-wrap"><label class="sr-only" for="timeline">${copy('Animation progress','动画进度')}</label><input class="timeline" id="timeline" type="range" min="0" max="${scenario.steps.length}" step="0.001" value="${position}"></div><span class="time" id="time"></span><label class="sr-only" for="speed">${copy('Playback speed','播放速度')}</label><select class="speed" id="speed"><option value="0.5">0.5×</option><option value="1">1×</option><option value="2">2×</option></select></div>
        <nav class="chapter-track" aria-label="${copy('Jump to a step','跳到指定步骤')}">${scenario.steps.map((s,i) => `<button data-step="${i}" aria-label="${text(s.title)}" title="${text(s.title)}">${String(i+1).padStart(2,'0')}</button>`).join('')}</nav>
        <div id="announcement" class="sr-only" role="status" aria-live="polite"></div>
      </section>
      <section class="evidence" aria-label="${copy('Evidence and scope','证据与范围')}">${evidence.map(item => `<div class="evidence-block"><p class="evidence-label">${text(item.label)}</p><p class="metric">${item.before ? `${text(item.before)} <span>→</span> <strong>${text(item.after)}</strong>` : text(item.value)} <span>${text(item.unit)}</span></p><p class="evidence-note">${text(item.note)}</p></div>`).join('')}<div class="evidence-block"><p class="scope-title">${copy('What this demo represents','这个 demo 展示的是什么')}</p><p class="scope-copy">${text(project.boundary)}</p><a class="source-link" href="${github('docs/ARCHITECTURE.md')}" target="_blank" rel="noopener">${copy('Read the evidence & method ↗','查看证据与方法 ↗')}</a></div></section></main>
      <footer class="footer"><span>${copy('Built by Kenny Yu · Independent portfolio project','Kenny Yu 制作 · 独立作品集项目')}</span><a href="https://github.com/kennyufish/${project.repo}" target="_blank" rel="noopener">${copy('Explore the repository ↗','查看完整仓库 ↗')}</a></footer></div>`;
    elements = Object.fromEntries(['diagram','step-number','step-total','step-title','step-copy','step-source','state','play','previous','next','timeline','time','speed','announcement'].map(id => [id,document.getElementById(id)]));
    elements.speed.value = String(speed);
    document.querySelector('#language').onclick = () => { language = 1-language; renderPage(); syncUrl(); document.querySelector('#language').focus(); };
    document.querySelectorAll('[data-scenario]').forEach(button => button.onclick = () => {
      pause(); scenarioId = button.dataset.scenario; position = 0; renderPage(); syncUrl(); document.querySelector(`[data-scenario="${scenarioId}"]`).focus();
    });
    elements.play.onclick = () => playing ? pause() : play();
    elements.previous.onclick = () => seek(Math.max(0, Math.floor(position)-1));
    elements.next.onclick = () => seek(Math.min(scenario.steps.length-1, Math.floor(position)+1));
    elements.timeline.oninput = event => seek(Number(event.target.value));
    elements.speed.onchange = event => { speed = Number(event.target.value); };
    document.querySelectorAll('[data-step]').forEach(button => button.onclick = () => seek(Number(button.dataset.step)));
    renderDiagram(); renderState();
  }

  function renderDiagram() {
    const isLending = scenario.kind === 'lending';
    const mobile = narrow.matches;
    const nodes = scenario.nodes || project.nodes;
    const width = mobile ? 440 : 840;
    const height = isLending ? (mobile ? 620 : 450) : (mobile ? 565 : 320);
    elements.diagram.setAttribute('viewBox', `0 0 ${width} ${height}`);
    elements.diagram.style.aspectRatio = `${width}/${height}`;
    const boxWidth = mobile ? 187 : isLending ? 220 : 176;
    const boxHeight = isLending ? 65 : 62;
    const lendingPositions = mobile ? [[12,42],[241,42],[241,194],[12,194],[12,346],[241,346],[241,498],[12,498]] : [[18,55],[310,55],[602,55],[602,200],[310,200],[18,200],[310,355],[18,355]];
    const positions = Object.fromEntries(nodes.map(([id],i) => {
      if(isLending) return [id,lendingPositions[i]];
      const column = i%4, lane = Math.floor(i/4);
      return [id, mobile ? [column%2 ? 241 : 12, lane*277 + (column < 2 ? 48 : 163)] : [12+column*212, lane*153+58]];
    }));
    const edges = isLending ? [['client','api'],['api','db'],['db','outbox'],['outbox','queue'],['queue','worker'],['worker','api'],['queue','dlq'],['db','result']] : [['a0','a1'],['a1','a2'],['a2','a3'],['b0','b1'],['b1','b2'],['b2','b3']];
    const pathData = (from,to) => {
      const [x1,y1] = positions[from], [x2,y2] = positions[to];
      if(from === 'worker' && to === 'api' && !mobile) return `M ${x1+boxWidth} ${y1+boxHeight/2} H 279 V ${y2+boxHeight/2} H ${x2}`;
      if(from === 'worker' && to === 'api' && mobile) return `M ${x1+boxWidth/2+25} ${y1} H 436 V ${y2+boxHeight/2} H ${x2+boxWidth}`;
      if(from === 'db' && to === 'result' && !mobile) return `M ${x1+boxWidth/2} ${y1+boxHeight} H 833 V ${y2+boxHeight/2} H ${x2+boxWidth}`;
      if(from === 'db' && to === 'result' && mobile) return `M ${x1} ${y1+boxHeight/2} H 223 V ${y2+boxHeight/2} H ${x2}`;
      if (y1 === y2) return x1 < x2 ? `M ${x1+boxWidth} ${y1+boxHeight/2} H ${x2}` : `M ${x1} ${y1+boxHeight/2} H ${x2+boxWidth}`;
      const down = y1 < y2, startY = down ? y1+boxHeight : y1, endY = down ? y2 : y2+boxHeight;
      return `M ${x1+boxWidth/2} ${startY} V ${(startY+endY)/2} H ${x2+boxWidth/2} V ${endY}`;
    };
    let backdrop = '';
    if(!isLending) backdrop = scenario.lanes.map((label,i) => `<rect class="lane" x="0" y="${i*(mobile?277:153)+8}" width="${width}" height="${mobile?260:138}" rx="5"/><text class="lane-label" x="14" y="${i*(mobile?277:153)+31}">${text(label)}</text>`).join('');
    const baseEdges = edges.map(([from,to]) => `<path class="edge" d="${pathData(from,to)}"/>`).join('');
    const nodeMarkup = nodes.map(([id,label,sub],i) => {
      const [x,y] = positions[id];
      return `<g class="node" data-node="${id}" transform="translate(${x} ${y})"><rect class="node-box" width="${boxWidth}" height="${boxHeight}" rx="4"/><text class="node-mark" x="12" y="19">${String((isLending?i:i%4)+1).padStart(2,'0')}</text><text class="node-label" x="12" y="39">${text(label)}</text><text class="node-sub" x="12" y="${boxHeight+17}">${text(sub)}</text></g>`;
    }).join('');
    const workCells = scenario.kind === 'index' ? ['a','b'].map((lane,i) => `<g data-cells="${lane}" transform="translate(14 ${mobile ? i*277+251 : i*153+141})">${Array.from({length:24},(_,j) => `<rect class="micro-cell" x="${j*(mobile?16:32)}" y="0" width="${mobile?10:22}" height="4" rx="1"/>`).join('')}</g>`).join('') : '';
    elements.diagram.innerHTML = `<title>${text(scenario.title)}</title><desc>${text(scenario.steps[Math.min(Math.floor(position),scenario.steps.length-1)].copy)}</desc>${backdrop}<g>${baseEdges}</g><g id="active-paths"></g>${nodeMarkup}${workCells}<g id="packets"></g>`;
    nodeElements = [...elements.diagram.querySelectorAll('[data-node]')];
    // Paths are derived from the same geometry as the visible nodes, including narrow layouts.
    paths = []; packetElements = [];
    elements.diagram._pathData = pathData;
    renderedStep = -1;
  }

  function renderState() {
    const count = scenario.steps.length;
    const index = Math.min(Math.floor(position), count-1);
    const current = scenario.steps[index];
    if(renderedStep !== index) {
      renderedStep = index;
      elements['step-number'].textContent = String(index+1).padStart(2,'0');
      elements['step-total'].textContent = copy(`of ${String(count).padStart(2,'0')}`,`/ ${String(count).padStart(2,'0')} 步`);
      elements['step-title'].textContent = tr(current.title);
      elements['step-copy'].textContent = tr(current.copy);
      elements['step-source'].href = github(current.source);
      elements.state.innerHTML = `<div class="state-row"><dt>${text(current.state[0])}</dt><dd>${text(current.state[1])}</dd></div>`;
      elements.announcement.textContent = `${index+1}/${count}: ${tr(current.title)} ${tr(current.copy)}`;
      elements.diagram.querySelector('desc').textContent = tr(current.copy);
      const visited = new Set(scenario.steps.slice(0,index).flatMap(s => s.active));
      nodeElements.forEach(node => {node.classList.toggle('active',current.active.includes(node.dataset.node)); node.classList.toggle('visited',visited.has(node.dataset.node));});
      elements.diagram.querySelector('#active-paths').innerHTML = current.flows.map(([from,to]) => `<path class="edge hot" d="${elements.diagram._pathData(from,to)}"/>`).join('');
      elements.diagram.querySelector('#packets').innerHTML = current.flows.map(() => '<g><circle class="packet-ring" r="13"/><circle class="packet" r="6"/></g>').join('');
      paths = [...elements.diagram.querySelectorAll('.edge.hot')].map(path => ({path,length:path.getTotalLength()}));
      packetElements = [...elements.diagram.querySelector('#packets').children];
      document.querySelectorAll('[data-step]').forEach(button => {if(Number(button.dataset.step) === index) button.setAttribute('aria-current','step'); else button.removeAttribute('aria-current');});
    }
    const phase = position >= count ? 1 : position-index;
    paths.forEach(({path,length},i) => {
      const travel = (phase-.1)/.75;
      const offset = reduced.matches ? 1 : Math.max(0,Math.min(1,scenario.kind === 'lending' ? travel*paths.length-i : travel));
      const point = path.getPointAtLength(length*offset);
      packetElements[i].setAttribute('transform',`translate(${point.x} ${point.y})`);
      packetElements[i].style.opacity = scenario.kind !== 'lending' || reduced.matches || i === Math.max(0,Math.min(paths.length-1,Math.floor(travel*paths.length))) ? '1' : '0';
    });
    if(scenario.kind === 'index') {
      ['a','b'].forEach(lane => elements.diagram.querySelectorAll(`[data-cells="${lane}"] rect`).forEach((cell,i) => {
        const start = lane === 'a' ? 1 : 2;
        const progress = Math.max(0,Math.min(1,position-start));
        const amount = lane === 'a' ? 24 : 3;
        cell.classList.toggle('on',i<Math.floor(amount*progress));
      }));
    }
    elements.timeline.value = String(position);
    elements.timeline.setAttribute('aria-valuetext',`${index+1}/${count}: ${tr(current.title)}`);
    const seconds = Math.min(Math.floor(position*secondsPerStep),count*secondsPerStep);
    const format = s => `${String(Math.floor(s/60)).padStart(2,'0')}:${String(s%60).padStart(2,'0')}`;
    elements.time.textContent = `${format(seconds)} / ${format(count*secondsPerStep)}`;
    elements.play.textContent = playing ? copy('Ⅱ Pause','Ⅱ 暂停') : position >= count ? copy('↺ Replay','↺ 重播') : copy('▶ Play','▶ 播放');
    elements.play.setAttribute('aria-label',playing ? copy('Pause walkthrough','暂停演示') : position >= count ? copy('Replay walkthrough','重新播放演示') : copy('Play walkthrough','播放演示'));
    elements.previous.disabled = position <= 0;
    elements.next.disabled = index === count-1;
  }
  function seek(value) { pause(); position = Math.max(0, Math.min(scenario.steps.length,Number.isFinite(value) ? value : 0)); renderState(); }
  function pause() { playing = false; cancelAnimationFrame(frameId); lastFrame = 0; if(elements) renderState(); }
  function play() { if(position >= scenario.steps.length) position=0; playing=true; lastFrame=0; renderState(); frameId=requestAnimationFrame(tick); }
  function tick(now) {
    if(!playing) return;
    if(lastFrame) position = Math.min(scenario.steps.length, position+(now-lastFrame)/1000/secondsPerStep*speed);
    lastFrame=now;
    if(position >= scenario.steps.length) playing=false;
    renderState();
    if(playing) frameId=requestAnimationFrame(tick);
  }
  document.addEventListener('visibilitychange',() => { if(document.hidden) pause(); });
  reduced.addEventListener('change',() => { pause(); renderState(); });
  narrow.addEventListener('change',() => { renderDiagram(); renderState(); });
  document.addEventListener('keydown',event => {
    if(!event.target.closest('.demo') || /INPUT|SELECT|BUTTON|A/.test(event.target.tagName)) return;
    if(event.code === 'Space') {event.preventDefault(); playing ? pause() : play();}
  });
  renderPage();
})();
