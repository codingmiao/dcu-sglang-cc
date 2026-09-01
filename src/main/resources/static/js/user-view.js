// 用户统计页：分页用户表 -> 点用户展开(最近记录 + 折线图) -> 点记录看详情
window.UserView = {
  template: `
  <div>
    <div class="panel">
      <h2>用户统计</h2>
      <div class="hint">点击某用户行，展开其最近调用记录与趋势</div>
      <table>
        <thead><tr><th>用户</th><th>请求数</th><th>输入</th><th>输出</th><th>总tokens</th><th>平均耗时</th><th>成功率</th><th>最近活跃</th></tr></thead>
        <tbody>
          <tr v-for="u in users" :key="u.user" class="clickable"
              :class="{expanded: expanded===u.user}" @click="toggle(u.user)">
            <td>{{ u.user }}</td>
            <td>{{ Dcu.fmt(u.requests) }}</td>
            <td>{{ Dcu.fmt(u.input_tokens) }}</td>
            <td>{{ Dcu.fmt(u.output_tokens) }}</td>
            <td>{{ Dcu.fmt(u.input_tokens + u.output_tokens) }}</td>
            <td>{{ Dcu.fmtMs(u.avg_cost) }} ms</td>
            <td>{{ successRate(u) }}%</td>
            <td>{{ Dcu.ts(u.last_ts) }}</td>
          </tr>
        </tbody>
      </table>
      <div class="pager">
        <button :disabled="page<=0" @click="loadUsers(page-1)">上一页</button>
        <span>第 {{ page+1 }} 页</span>
        <button :disabled="users.length<size" @click="loadUsers(page+1)">下一页</button>
      </div>
    </div>

    <!-- 下钻：某用户 -->
    <div class="panel" v-if="expanded">
      <h2>用户 {{ expanded }} <span class="muted">最近调用</span>
        <a class="muted" style="float:right;cursor:pointer" @click="expanded=null">收起</a></h2>
      <div class="drill">
        <div class="row">
          <div class="col">
            <h3>按时间趋势
              <span class="range">
                <button v-for="r in ranges" :key="r.key" class="btn small" :class="{active: range===r.key}" @click="setRange(r.key)">{{ r.label }}</button>
              </span>
            </h3>
            <div class="hint">点击图例可显示/隐藏序列</div>
            <div ref="trend" style="width:100%;height:300px"></div>
          </div>
        </div>
        <h3 style="margin-top:14px">最近调用记录</h3>
        <table>
          <thead><tr><th>时间</th><th>logId</th><th>模型</th><th>流式</th><th>输入</th><th>输出</th><th>耗时</th><th>stop</th><th>结果</th></tr></thead>
          <tbody>
            <tr v-for="r in records" :key="r.log_id" class="clickable" @click="openRecord(r.log_id)">
              <td>{{ Dcu.ts(r.ts) }}</td>
              <td class="muted">{{ Dcu.shortId(r.log_id) }}</td>
              <td>{{ r.model }}</td>
              <td><span class="tag stream" v-if="r.stream==1">stream</span><span v-else class="muted">-</span></td>
              <td>{{ Dcu.fmt(r.input_tokens) }}</td>
              <td>{{ Dcu.fmt(r.output_tokens) }}</td>
              <td>{{ Dcu.fmtMs(r.cost) }} ms</td>
              <td class="muted">{{ r.stop_reason || '-' }}</td>
              <td><span class="tag" :class="r.success==1?'ok':'bad'">{{ r.success==1?'成功':'失败' }}</span></td>
            </tr>
          </tbody>
        </table>
        <div class="pager">
          <button :disabled="recPage<=0" @click="loadRecords(recPage-1)">上一页</button>
          <span>第 {{ recPage+1 }} 页</span>
          <button :disabled="records.length<recSize" @click="loadRecords(recPage+1)">下一页</button>
        </div>
      </div>
    </div>

    <record-modal v-if="modalLogId" :log-id="modalLogId" @close="modalLogId=null"></record-modal>
  </div>
  `,
  data() {
    return {
      users: [], page: 0, size: 10,
      expanded: null, records: [], recPage: 0, recSize: 10,
      ranges: [
        { key: '60s', label: '每60秒', bucket: 60, from: null },
        { key: '24h', label: '近24小时', bucket: 3600, from: 24 * 3600 * 1000 },
        { key: '7d', label: '近7天', bucket: 4 * 3600 * 1000, from: 7 * 86400 * 1000 },
        { key: '30d', label: '近30天', bucket: 86400 * 1000, from: 30 * 86400 * 1000 },
      ],
      range: '24h',
      series: [
        { key: 'requests', label: '请求数' },
        { key: 'input_tokens', label: '输入token' },
        { key: 'output_tokens', label: '输出token' },
        { key: 'avg_cost', label: '平均耗时(ms)' },
        { key: 'tps', label: '每秒输出token' },
      ],
      hidden: {},
      modalLogId: null, timer: null,
    };
  },
  methods: {
    successRate(u) {
      if (!u.requests) return 0;
      return Math.round(u.success_count / u.requests * 100);
    },
    setRange(key) { this.range = key; this.loadTrend(); },
    async loadUsers(p) {
      this.page = p;
      const r = await Dcu.get('/stats/users?page=' + p + '&size=' + this.size);
      this.users = r.data || [];
    },
    async toggle(user) {
      if (this.expanded === user) { this.expanded = null; return; }
      this.expanded = user;
      this.recPage = 0;
      await Promise.all([this.loadRecords(0), this.loadTrend()]);
    },
    async loadRecords(p) {
      this.recPage = p;
      const r = await Dcu.get('/stats/users/' + encodeURIComponent(this.expanded) +
        '/records?page=' + p + '&size=' + this.recSize);
      this.records = r.data || [];
    },
    async loadTrend() {
      const r = this.ranges.find(x => x.key === this.range);
      const from = r.from ? (Date.now() - r.from) : null;
      const res = await Dcu.get('/stats/users/' + encodeURIComponent(this.expanded) +
        '/trend?bucketSeconds=' + r.bucket + (from ? '&from=' + from : ''));
      this.$nextTick(() => {
        const series = this.series.map(s => ({ ...s, visible: !this.hidden[s.key] }));
        const now = Date.now();
        const xDomain = from ? [from, now] : null;
        Dcu.lineChart(this.$refs.trend, res.data || [], 'bucket', series, {
          xDomain,
          onToggle: (key, vis) => { this.hidden[key] = !vis; },
        });
      });
    },
    openRecord(logId) { this.modalLogId = logId; },
  },
  mounted() {
    this.loadUsers(0);
    this.timer = setInterval(() => { if (!this.expanded) this.loadUsers(this.page); }, 5000);
  },
  beforeUnmount() {
    if (this.timer) clearInterval(this.timer);
  },
};
