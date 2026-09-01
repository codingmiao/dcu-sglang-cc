// 记录详情弹层：按 logId 从 jsonl 拉完整 request/response
window.RecordModal = {
  props: { logId: String },
  emits: ['close'],
  template: `
  <div class="modal-mask" @click.self="$emit('close')">
    <div class="modal">
      <div class="head">
        <h3>请求详情 <span class="muted">{{ Dcu.shortId(logId) }}</span></h3>
        <button class="close" @click="$emit('close')">×</button>
      </div>
      <div class="body">
        <div v-if="loading" class="muted">加载中…</div>
        <div v-else-if="!found" class="notice">{{ notice }}</div>
        <template v-else>
          <div class="muted" style="margin-bottom:8px">
            用户 {{ data.user }} · 耗时 {{ Dcu.fmtMs(data.cost) }} ms
            <span v-if="data.sourceFile" class="muted"> · 来源 {{ data.sourceFile }}</span>
          </div>
          <h3 style="font-size:13px;margin:10px 0 6px">请求</h3>
          <pre class="json">{{ pretty(data.request) }}</pre>
          <h3 style="font-size:13px;margin:10px 0 6px">响应</h3>
          <pre class="json">{{ pretty(data.response) }}</pre>
        </template>
      </div>
    </div>
  </div>
  `,
  data() {
    return { loading: true, found: false, data: null, notice: '' };
  },
  methods: {
    pretty(v) {
      if (v == null) return '(空)';
      try { return JSON.stringify(v, null, 2); } catch (e) { return String(v); }
    },
    async load() {
      this.loading = true;
      try {
        const r = await Dcu.get('/stats/records/' + encodeURIComponent(this.logId));
        this.found = r.found;
        this.data = r.data || null;
        if (!r.found) {
          this.notice = r.reason === 'compressed'
            ? '该对话已压缩/已清理，无法查看完整输入输出。'
            : '未找到该记录。';
        }
      } catch (e) {
        this.found = false;
        this.notice = '加载失败：' + e.message;
      } finally {
        this.loading = false;
      }
    },
  },
  mounted() { this.load(); },
};
