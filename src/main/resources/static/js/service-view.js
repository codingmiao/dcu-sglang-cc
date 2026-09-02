// 服务统计页
window.ServiceView = {
  props: { loggedIn: { type: Boolean, default: false } },
  template: `
  <div>
    <!-- 概览卡片（6 个指标，等宽一行） -->
    <div class="cards">
      <div class="card"><div class="label">总请求数</div><div class="value">{{ Dcu.fmt(ov.total_requests) }}</div></div>
      <div class="card"><div class="label">成功 / 失败</div>
        <div class="value">{{ Dcu.fmt(ov.success_count) }} / {{ Dcu.fmt(ov.total_requests) - Dcu.fmt(ov.success_count) }}</div></div>
      <div class="card"><div class="label">输入 tokens</div><div class="value">{{ Dcu.fmt(ov.total_input_tokens) }}</div></div>
      <div class="card"><div class="label">输出 tokens</div><div class="value">{{ Dcu.fmt(ov.total_output_tokens) }}</div></div>
      <div class="card"><div class="label">改动行数</div><div class="value">{{ Dcu.fmt(ov.total_lines_changed) }}</div></div>
      <div class="card"><div class="label">平均耗时</div><div class="value">{{ Dcu.fmtMs(ov.avg_cost) }} ms</div></div>
    </div>

    <!-- 服务状态条 -->
    <div class="status-bar">
      <span class="dot" :class="svc.status === 'online' ? 'on' : 'off'"></span>
      <span class="st">{{ svc.status }}</span>
      <span class="muted">运行 {{ uptime }} · 在途 {{ svc.inFlight }} · 队列 {{ svc.queueDepth }}</span>
    </div>

    <!-- 请求趋势（折线图，可下钻） -->
    <div class="panel">
      <h2>请求趋势
        <span class="range">
          <button v-for="r in ranges" :key="r.key" class="btn small" :class="{active: range===r.key}" @click="setRange(r.key)">{{ r.label }}</button>
        </span>
      </h2>
      <div class="hint" v-if="loggedIn">点击图例可显示/隐藏序列；点击图表查看该时间点的请求明细</div>
      <div class="hint" v-else>点击图例可显示/隐藏序列；<a style="cursor:pointer;color:#1f6feb" @click="$root.showLogin=true">登录</a>后可查看请求明细</div>
      <div ref="trend" style="width:100%;height:300px"></div>
    </div>

    <!-- 按模型分布（可下钻） -->
    <div class="panel">
      <h2>按模型分布</h2>
      <div class="hint" v-if="loggedIn">点击柱子查看该模型的请求明细</div>
      <div class="hint" v-else>登录后可查看请求明细</div>
      <div ref="model" style="width:100%;height:220px"></div>
    </div>

    <!-- 下钻明细 -->
    <div class="panel" v-if="drill">
      <h2>请求明细 <span class="muted">（{{ drill.title }}）</span>
        <a class="muted" style="float:right;cursor:pointer" @click="drill=null">关闭</a></h2>
      <table>
        <thead><tr><th>时间</th><th>用户</th><th>模型</th><th>流式</th><th>输入</th><th>输出</th><th>改动</th><th>耗时</th><th>结果</th></tr></thead>
        <tbody>
          <tr v-for="r in drill.rows" :key="r.log_id" class="clickable" @click="openRecord(r.log_id)">
            <td>{{ Dcu.ts(r.ts) }}</td>
            <td>{{ r.user }}</td>
            <td>{{ r.model }}</td>
            <td><span class="tag stream" v-if="r.stream==1">stream</span><span v-else class="muted">-</span></td>
            <td>{{ Dcu.fmt(r.input_tokens) }}</td>
            <td>{{ Dcu.fmt(r.output_tokens) }}</td>
            <td>{{ Dcu.fmt(r.lines_changed) }}</td>
            <td>{{ Dcu.fmtMs(r.cost) }} ms</td>
            <td><span class="tag" :class="r.success==1?'ok':'bad'">{{ r.success==1?'成功':'失败' }}</span></td>
          </tr>
        </tbody>
      </table>
      <div class="pager">
        <button :disabled="drill.page<=0" @click="loadDrill(drill.page-1)">上一页</button>
        <span>第 {{ drill.page+1 }} 页</span>
        <button :disabled="drill.rows.length<drill.size" @click="loadDrill(drill.page+1)">下一页</button>
      </div>
    </div>

    <record-modal v-if="modalLogId" :log-id="modalLogId" @close="modalLogId=null"></record-modal>
  </div>
  `,
  data() {
    return {
      ov: {}, svc: {}, byTime: [], byModel: [],
      // 时间范围：bucket 单位秒，from 单位毫秒（时间窗长度，null=全部）
      ranges: [
        { key: '1h', label: '近一小时', bucket: 60, from: 3600 * 1000 },
        { key: '24h', label: '近24小时', bucket: 3600, from: 24 * 3600 * 1000 },
        { key: '7d', label: '近7天', bucket: 4 * 3600 * 1000, from: 7 * 86400 * 1000 },
        { key: '30d', label: '近30天', bucket: 86400 * 1000, from: 30 * 86400 * 1000 },
      ],
      range: '24h',
      series: [
        { key: 'requests', label: '请求数' },
        { key: 'input_tokens', label: '输入token' },
        { key: 'output_tokens', label: '输出token' },
        { key: 'lines_changed', label: '改动行数' },
        { key: 'avg_cost', label: '平均耗时(ms)' },
        { key: 'tps', label: '每秒输出token' },
      ],
      hidden: {},
      drill: null, modalLogId: null, timer: null, _gen: 0,
    };
  },
  computed: {
    uptime() {
      const ms = this.svc.uptimeMillis || 0;
      const s = Math.floor(ms / 1000);
      const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
      return (h ? h + 'h ' : '') + (m ? m + 'm ' : '') + sec + 's';
    },
  },
  methods: {
    setRange(key) { this.range = key; this.load(); },
    async load() {
      const gen = ++this._gen;
      try {
        const r = this.ranges.find(x => x.key === this.range);
        const from = r.from ? (Date.now() - r.from) : null;
        const [ov, byTime, byModel] = await Promise.all([
          Dcu.get('/stats/overview'),
          Dcu.get('/stats/by-time?bucketSeconds=' + r.bucket + (from ? '&from=' + from : '')),
          Dcu.get('/stats/by-model'),
        ]);
        if (gen !== this._gen) return; // 已有更新的加载，丢弃本次结果
        this.ov = ov.data || {};
        this.svc = ov.service || {};
        this.byTime = byTime.data || [];
        this.byModel = byModel.data || [];
        this.$nextTick(() => {
          if (gen !== this._gen) return;
          const series = this.series.map(s => ({ ...s, visible: !this.hidden[s.key] }));
          const now = Date.now();
          const xDomain = from ? [from, now] : null;
          Dcu.lineChart(this.$refs.trend, this.byTime, 'bucket', series, {
            xDomain,
            onClick: (d) => this.drillTime(d),
            onToggle: (key, vis) => { this.hidden[key] = !vis; },
          });
          Dcu.barChart(this.$refs.model, this.byModel, 'model', 'requests', '#2ea043');
          this.bindModelDrill();
        });
      } catch (e) { /* offline */ }
    },
    bindModelDrill() {
      const model = this.$refs.model;
      if (!model) return;
      model.querySelectorAll('rect').forEach((rect, i) => {
        const d = this.byModel[i];
        if (!d) return;
        if (!this.loggedIn) return; // 未登录不绑定明细下钻
        rect.style.cursor = 'pointer';
        rect.addEventListener('click', () => this.drillModel(d));
      });
    },
    drillTime(d) {
      if (!this.loggedIn) return; // 未登录不可下钻明细
      const r = this.ranges.find(x => x.key === this.range);
      this.drill = { title: `时间窗 ${Dcu.ts(d.bucket)}`, from: d.bucket, to: d.bucket + r.bucket * 1000, model: null, page: 0, size: 10, rows: [] };
      this.loadDrill(0);
    },
    drillModel(d) {
      if (!this.loggedIn) return; // 未登录不可下钻明细
      this.drill = { title: `模型 ${d.model}`, from: null, to: null, model: d.model, page: 0, size: 10, rows: [] };
      this.loadDrill(0);
    },
    async loadDrill(page) {
      if (!this.drill) return;
      this.drill.page = page;
      let url = '/stats/records?page=' + page + '&size=' + this.drill.size;
      if (this.drill.from != null) url += '&from=' + this.drill.from;
      if (this.drill.to != null) url += '&to=' + this.drill.to;
      if (this.drill.model) url += '&model=' + encodeURIComponent(this.drill.model);
      const r = await Dcu.get(url);
      this.drill.rows = r.data || [];
    },
    openRecord(logId) { this.modalLogId = logId; },
  },
  mounted() {
    this.load();
    this.timer = setInterval(this.load, 5000);
  },
  beforeUnmount() {
    if (this.timer) clearInterval(this.timer);
  },
};
