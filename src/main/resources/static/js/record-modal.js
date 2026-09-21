// 记录详情弹层：按 logId 从 jsonl 拉完整 request/response
// 两个选项卡：「关键信息」（对话 + AI 响应，折叠系统提示词/工具定义）与「原始 JSON」
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
          <div v-if="data.error" class="notice" style="margin-bottom:8px">
            <b>失败原因：</b>{{ data.error }}
          </div>
          <div class="tabs">
            <button class="tab" :class="{active: tab==='key'}" @click="tab='key'">关键信息</button>
            <button class="tab" :class="{active: tab==='raw'}" @click="tab='raw'">原始 JSON</button>
          </div>

          <!-- 关键信息：对话 + AI 响应，系统提示词与工具定义折叠 -->
          <div v-if="tab==='key'">
            <h3 class="sec">请求</h3>
            <div class="kv">
              <span v-if="req.model">模型 {{ req.model }}</span>
              <span v-if="req.max_tokens">max_tokens {{ req.max_tokens }}</span>
              <span v-if="req.stream">stream</span>
              <span v-if="req.temperature != null">temp {{ req.temperature }}</span>
            </div>
            <details v-if="hasSystem" class="fold">
              <summary>系统提示词（{{ systemText.length }} 字符）</summary>
              <pre class="json">{{ systemText }}</pre>
            </details>
            <details v-if="hasTools" class="fold">
              <summary>工具定义（{{ req.tools.length }} 个：{{ toolNames(req.tools) }}）</summary>
              <pre class="json">{{ pretty(req.tools) }}</pre>
            </details>
            <h3 class="sec">对话</h3>
            <div v-for="(m,i) in (req.messages||[])" :key="i" class="msg" :class="m.role">
              <div class="role">{{ m.role }}</div>
              <div class="content">{{ msgContentView(m.content) }}</div>
            </div>
            <div v-if="!(req.messages||[]).length" class="muted">（无消息）</div>

            <h3 class="sec">响应</h3>
            <div v-if="resp" class="kv">
              <span v-if="resp.stop_reason">stop {{ resp.stop_reason }}</span>
              <span v-if="resp.usage">输入 {{ Dcu.fmt(resp.usage.input_tokens) }} · 输出 {{ Dcu.fmt(resp.usage.output_tokens) }}</span>
              <span v-if="resp.usage && resp.usage.cache_read_input_tokens" class="cache-hit">缓存命中 {{ Dcu.fmt(resp.usage.cache_read_input_tokens) }}</span>
            </div>
            <div v-if="resp && (resp.content||[]).length">
              <div v-for="(b,i) in resp.content" :key="i" class="msg resp">
                <div class="role">{{ respBlockLabel(b) }}</div>
                <div class="content">{{ respBlockView(b) }}</div>
              </div>
            </div>
            <div v-else-if="resp" class="muted">（无内容块）</div>
            <div v-else class="muted">（无响应）</div>
          </div>

          <!-- 原始 JSON -->
          <div v-else>
            <h3 class="sec">请求</h3>
            <pre class="json">{{ pretty(data.request) }}</pre>
            <h3 class="sec">响应</h3>
            <pre class="json">{{ pretty(data.response) }}</pre>
          </div>
        </template>
      </div>
    </div>
  </div>
  `,
  data() {
    return { loading: true, found: false, data: null, notice: '', tab: 'key' };
  },
  computed: {
    req() { return (this.data && this.data.request) || {}; },
    resp() { return (this.data && this.data.response) || null; },
    hasSystem() { return this.req.system != null; },
    hasTools() { return Array.isArray(this.req.tools) && this.req.tools.length > 0; },
    systemText() { return this.systemView(this.req.system); },
  },
  methods: {
    pretty(v) {
      if (v == null) return '(空)';
      try { return JSON.stringify(v, null, 2); } catch (e) { return String(v); }
    },
    // 单个内容块 -> 可读文本（text/thinking/tool_use/tool_result/image）
    blockView(b) {
      if (b == null) return '';
      if (typeof b !== 'object') return String(b);
      switch (b.type) {
        case 'text': return b.text || '';
        case 'thinking': return b.thinking || '';
        case 'tool_use': return '🔧 ' + (b.name || 'tool') + '\n' + this.pretty(b.input);
        case 'tool_result': {
          const c = b.content;
          if (Array.isArray(c)) return c.map(x => this.blockView(x)).join('\n');
          if (typeof c === 'string') return c;
          return this.pretty(c);
        }
        case 'image': return '[图片]';
        default: return this.pretty(b);
      }
    },
    // 消息 content：字符串 / 内容块数组 / 其它
    msgContentView(content) {
      if (content == null) return '';
      if (typeof content === 'string') return content;
      if (Array.isArray(content)) return content.map(b => this.blockView(b)).join('\n\n');
      return this.pretty(content);
    },
    // system：字符串 / 文本块数组
    systemView(system) {
      if (system == null) return '';
      if (typeof system === 'string') return system;
      if (Array.isArray(system)) {
        return system.map(b => (b && typeof b === 'object' && b.text != null) ? b.text : this.pretty(b)).join('\n');
      }
      return this.pretty(system);
    },
    toolNames(tools) {
      if (!Array.isArray(tools)) return '';
      return tools.map(t => t && t.name).filter(Boolean).join(', ');
    },
    respBlockLabel(b) {
      switch (b.type) {
        case 'text': return '回复';
        case 'thinking': return '思考';
        case 'tool_use': return '工具调用 · ' + (b.name || '');
        default: return b.type || '内容';
      }
    },
    respBlockView(b) {
      if (b.type === 'tool_use') return this.pretty(b.input);
      if (b.type === 'thinking') return b.thinking || '';
      if (b.type === 'text') return b.text || '';
      return this.pretty(b);
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
