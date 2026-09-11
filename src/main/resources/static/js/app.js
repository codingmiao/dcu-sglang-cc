// 公共工具 + 根应用（最后加载：注册各视图组件并挂载）
// 站点统一挂在 /dcu-sglang-cc 前缀下（见 application.yml 的 server.servlet.context-path），
// 所有接口调用都经 Dcu.get/post/put/del，这里统一拼上前缀。
const BASE = '/dcu-sglang-cc';
const Dcu = {
  fmt(v) { return (v == null || isNaN(v)) ? 0 : Math.round(Number(v)).toLocaleString(); },
  fmtMs(v) { return (v == null || isNaN(v)) ? 0 : Math.round(Number(v)).toLocaleString(); },
  ts(v) { return v ? new Date(Number(v)).toLocaleString() : '-'; },
  shortId(id) { return id ? String(id).slice(0, 8) : ''; },

  async get(url) {
    const r = await fetch(BASE + url);
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.json();
  },
  async post(url, body) {
    const r = await fetch(BASE + url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body || {}),
    });
    return r.json();
  },
  async put(url, body) {
    const r = await fetch(BASE + url, {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body || {}),
    });
    return r.json();
  },
  async del(url) {
    const r = await fetch(BASE + url, { method: 'DELETE' });
    return r.json();
  },

  // 柱状图（时间桶 / 模型）
  barChart(el, data, xKey, yKey, color) {
    if (!el) return;
    el.innerHTML = '';
    if (!data.length) { el.innerHTML = '<div class="muted">暂无数据</div>'; return; }
    const W = el.clientWidth || 800, H = 220, m = { t: 10, r: 10, b: 34, l: 48 };
    const svg = d3.select(el).append('svg').attr('width', W).attr('height', H);
    const x = d3.scaleBand().domain(data.map(d => d[xKey])).range([m.l, W - m.r]).padding(0.2);
    const y = d3.scaleLinear().domain([0, d3.max(data, d => d[yKey]) || 1]).nice().range([H - m.b, m.t]);
    svg.append('g').attr('transform', `translate(0,${H - m.b})`)
      .call(d3.axisBottom(x).tickFormat(d => this.tickLabel(xKey, d)).tickSizeOuter(0))
      .selectAll('text').style('font-size', '10px');
    svg.append('g').attr('transform', `translate(${m.l},0)`)
      .call(d3.axisLeft(y).ticks(4)).selectAll('text').style('font-size', '10px');
    svg.selectAll('rect').data(data).enter().append('rect')
      .attr('x', d => x(d[xKey])).attr('width', x.bandwidth())
      .attr('y', d => y(d[yKey])).attr('height', d => (H - m.b) - y(d[yKey]))
      .attr('fill', color || '#4f8ef7').attr('rx', 2);
  },

  // 折线图（多序列 + 可点击图例）。series: [{key,label,color?}]
  // opts.onClick(dataPoint)：在图上点击时回调最近的数据点（用于下钻）
  lineChart(el, data, xKey, series, opts = {}) {
    if (!el) return;
    el.innerHTML = '';
    if (!data.length) { el.innerHTML = '<div class="muted">暂无数据</div>'; return; }
    const W = el.clientWidth || 800, H = 240, m = { t: 12, r: 12, b: 34, l: 52 };
    const palette = ['#4f8ef7', '#2ea043', '#d1242f', '#bf8700', '#8250df'];
    const sers = series.map((s, i) => ({
      key: s.key, label: s.label || s.key,
      color: s.color || palette[i % palette.length],
      visible: s.visible !== false,
    }));
    const legend = document.createElement('div');
    legend.className = 'legend';
    const wrap = document.createElement('div');
    el.appendChild(legend);
    el.appendChild(wrap);

    const render = () => {
      wrap.innerHTML = '';
      const visible = sers.filter(s => s.visible);
      const svg = d3.select(wrap).append('svg').attr('width', W).attr('height', H);
      let [x0, x1] = opts.xDomain || d3.extent(data, d => d[xKey]);
      if (x0 === x1) { x0 -= 1; x1 += 1; }
      const x = d3.scaleLinear().domain([x0, x1]).range([m.l, W - m.r]);
      const maxV = visible.length
        ? (d3.max(data, d => d3.max(visible, s => d[s.key] || 0)) || 1)
        : 1;
      const y = d3.scaleLinear().domain([0, maxV]).nice().range([H - m.b, m.t]);
      svg.append('g').attr('transform', `translate(0,${H - m.b})`)
        .call(d3.axisBottom(x).ticks(6).tickFormat(d => this.tickTime(x.domain(), d)).tickSizeOuter(0))
        .selectAll('text').style('font-size', '10px');
      svg.append('g').attr('transform', `translate(${m.l},0)`)
        .call(d3.axisLeft(y).ticks(4)).selectAll('text').style('font-size', '10px');
      visible.forEach(s => {
        const line = d3.line().x(d => x(d[xKey])).y(d => y(d[s.key] || 0)).curve(d3.curveMonotoneX);
        svg.append('path').datum(data).attr('fill', 'none')
          .attr('stroke', s.color).attr('stroke-width', 2).attr('d', line);
      });
      if (opts.onClick) {
        const overlay = svg.append('rect')
          .attr('x', m.l).attr('y', m.t)
          .attr('width', Math.max(0, W - m.l - m.r)).attr('height', Math.max(0, H - m.t - m.b))
          .attr('fill', 'transparent').style('cursor', 'pointer');
        overlay.on('click', (event) => {
          const [mx] = d3.pointer(event);
          const xv = x.invert(mx);
          let best = data[0], bd = Infinity;
          data.forEach(d => { const dd = Math.abs(d[xKey] - xv); if (dd < bd) { bd = dd; best = d; } });
          opts.onClick(best);
        });
      }
    };

    sers.forEach(s => {
      const item = document.createElement('span');
      item.className = 'legend-item' + (s.visible ? '' : ' off');
      const sw = document.createElement('i');
      sw.style.background = s.color;
      item.appendChild(sw);
      item.appendChild(document.createTextNode(s.label));
      item.onclick = () => { s.visible = !s.visible; item.classList.toggle('off', !s.visible); render(); if (opts.onToggle) opts.onToggle(s.key, s.visible); };
      legend.appendChild(item);
    });

    render();
  },

  // 分组柱状图（多序列 + 可点击图例）。series: [{key,label,color?}]
  // 每个时间点并排画各序列的柱子；x 轴用 band 刻度（只画有数据的桶，允许空桶缺口）。
  // opts.onClick(dataPoint)：点击下钻最近的时间桶；opts.onToggle(key,vis)：图例切换
  groupedBarChart(el, data, xKey, series, opts = {}) {
    if (!el) return;
    el.innerHTML = '';
    if (!data.length) { el.innerHTML = '<div class="muted">暂无数据</div>'; return; }
    const W = el.clientWidth || 800, H = 240, m = { t: 12, r: 12, b: 34, l: 52 };
    const palette = ['#4f8ef7', '#2ea043', '#d1242f', '#bf8700', '#8250df', '#0ca4a4'];
    const sers = series.map((s, i) => ({
      key: s.key, label: s.label || s.key,
      color: s.color || palette[i % palette.length],
      visible: s.visible !== false,
    }));
    const legend = document.createElement('div');
    legend.className = 'legend';
    const wrap = document.createElement('div');
    el.appendChild(legend);
    el.appendChild(wrap);

    const render = () => {
      wrap.innerHTML = '';
      const visible = sers.filter(s => s.visible);
      const svg = d3.select(wrap).append('svg').attr('width', W).attr('height', H);
      const x = d3.scaleBand().domain(data.map(d => d[xKey])).range([m.l, W - m.r]).padding(0.15);
      const x0 = d3.scaleBand().domain(visible.map(s => s.key)).range([0, x.bandwidth()]).padding(0.05);
      const maxV = visible.length
        ? (d3.max(data, d => d3.max(visible, s => d[s.key] || 0)) || 1)
        : 1;
      const y = d3.scaleLinear().domain([0, maxV]).nice().range([H - m.b, m.t]);
      // 桶多时只取部分刻度，避免 x 轴标签挤成一团
      const n = data.length, step = Math.max(1, Math.ceil(n / 8));
      const tickVals = data.filter((_, i) => i % step === 0).map(d => d[xKey]);
      const domain = [Number(data[0][xKey]), Number(data[n - 1][xKey])];
      svg.append('g').attr('transform', `translate(0,${H - m.b})`)
        .call(d3.axisBottom(x).tickValues(tickVals).tickFormat(d => this.tickTime(domain, d)).tickSizeOuter(0))
        .selectAll('text').style('font-size', '10px');
      svg.append('g').attr('transform', `translate(${m.l},0)`)
        .call(d3.axisLeft(y).ticks(4)).selectAll('text').style('font-size', '10px');
      visible.forEach(s => {
        svg.append('g').selectAll('rect').data(data).enter().append('rect')
          .attr('x', d => x(d[xKey]) + x0(s.key))
          .attr('width', Math.max(0, x0.bandwidth()))
          .attr('y', d => y(d[s.key] || 0))
          .attr('height', d => (H - m.b) - y(d[s.key] || 0))
          .attr('fill', s.color).attr('rx', 1)
          .append('title').text(d => `${s.label}：${this.fmt(d[s.key] || 0)}\n${new Date(Number(d[xKey])).toLocaleString()}`);
      });
      if (opts.onClick) {
        const overlay = svg.append('rect')
          .attr('x', m.l).attr('y', m.t)
          .attr('width', Math.max(0, W - m.l - m.r)).attr('height', Math.max(0, H - m.t - m.b))
          .attr('fill', 'transparent').style('cursor', 'pointer');
        overlay.on('click', (event) => {
          const [mx] = d3.pointer(event);
          let best = data[0], bd = Infinity;
          data.forEach(d => {
            const center = x(d[xKey]) + x.bandwidth() / 2;
            const dd = Math.abs(center - mx);
            if (dd < bd) { bd = dd; best = d; }
          });
          opts.onClick(best);
        });
      }
    };

    sers.forEach(s => {
      const item = document.createElement('span');
      item.className = 'legend-item' + (s.visible ? '' : ' off');
      const sw = document.createElement('i');
      sw.style.background = s.color;
      item.appendChild(sw);
      item.appendChild(document.createTextNode(s.label));
      item.onclick = () => { s.visible = !s.visible; item.classList.toggle('off', !s.visible); render(); if (opts.onToggle) opts.onToggle(s.key, s.visible); };
      legend.appendChild(item);
    });

    render();
  },

  // 折线图 x 轴刻度：按坐标域跨度决定格式（跨度大显示日期，否则显示时刻）
  tickTime(domain, d) {
    const span = ((domain[1] || 0) - (domain[0] || 0)) / 1000;
    const dt = new Date(d); // bucket 为毫秒时间戳
    if (span > 2 * 86400) return (dt.getMonth() + 1) + '/' + dt.getDate();
    return dt.toLocaleTimeString();
  },

  tickLabel(xKey, d) {
    if (xKey === 'bucket') return new Date(Number(d)).toLocaleTimeString();
    return String(d);
  },

  toast(msg) {
    const t = document.createElement('div');
    t.className = 'toast';
    t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(() => t.remove(), 2200);
  },
};

const { createApp } = Vue;
const app = createApp({
  data() {
    return {
      view: 'service', online: true,
      // 登录态：未登录仅服务统计（无明细）；登录后才有明细 / 用户统计 / 用户管理
      loggedIn: false, showLogin: false, username: '', password: '', loginErr: '',
    };
  },
  methods: {
    async checkOnline() {
      try {
        const ov = await Dcu.get('/stats/overview');
        this.online = ov.status === 'online';
      } catch (e) {
        this.online = false;
      }
    },
    async checkMe() {
      try {
        const r = await Dcu.get('/admin/me');
        this.loggedIn = !!r.loggedIn;
      } catch (e) {
        this.loggedIn = false;
      }
    },
    async doLogin() {
      this.loginErr = '';
      const r = await Dcu.post('/admin/login', { username: this.username, password: this.password });
      if (r.ok) {
        this.loggedIn = true;
        this.showLogin = false;
        this.password = '';
      } else {
        this.loginErr = r.message || '登录失败';
      }
    },
    async logout() {
      await Dcu.post('/admin/logout', {});
      this.loggedIn = false;
      this.view = 'service';
    },
  },
  mounted() {
    this.checkMe();
    this.checkOnline();
    setInterval(this.checkOnline, 5000);
  },
});

// 注册各视图组件（此构建未暴露全局 Vue.component，改用 app.component）
app.component('record-modal', window.RecordModal);
app.component('service-view', window.ServiceView);
app.component('user-view', window.UserView);
app.component('admin-view', window.AdminView);
// 模板里用到的公共工具 Dcu 需挂到全局属性，模板作用域才能访问
app.config.globalProperties.Dcu = Dcu;
app.mount('#app');
