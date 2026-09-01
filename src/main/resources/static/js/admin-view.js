// 用户管理页：用户增删改（启用/禁用、重置 key、删除）。登录态由根应用统一管理（右上角）。
window.AdminView = {
  template: `
  <div>
    <div class="panel">
      <h2>新增用户</h2>
      <div class="form">
        <div class="field"><label>用户名</label><input v-model="newName" placeholder="如 carol"></div>
        <div class="field"><label>apiKey（留空自动生成）</label><input v-model="newKey" placeholder="sk-..."></div>
        <button class="btn primary" @click="createUser">添加</button>
      </div>
    </div>

    <div class="panel">
      <h2>用户列表</h2>
      <table>
        <thead><tr><th>id</th><th>用户名</th><th>apiKey</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="u in users" :key="u.id">
            <td class="muted">{{ u.id }}</td>
            <td>{{ u.name }}</td>
            <td class="muted" style="font-family:monospace">{{ u.api_key }}</td>
            <td><span class="tag" :class="u.enabled==1?'ok':'bad'">{{ u.enabled==1?'启用':'禁用' }}</span></td>
            <td class="muted">{{ Dcu.ts(u.created_at) }}</td>
            <td>
              <div class="actions">
                <button class="btn small" @click="toggleEnabled(u)">{{ u.enabled==1?'禁用':'启用' }}</button>
                <button class="btn small" @click="resetKey(u)">重置key</button>
                <button class="btn small danger" @click="removeUser(u)">删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
  `,
  data() {
    return {
      users: [], newName: '', newKey: '',
    };
  },
  methods: {
    async loadUsers() {
      const r = await Dcu.get('/admin/users');
      if (r.ok) this.users = r.data || [];
    },
    async createUser() {
      if (!this.newName) { Dcu.toast('请输入用户名'); return; }
      const r = await Dcu.post('/admin/users', { name: this.newName, apiKey: this.newKey });
      if (r.ok) { Dcu.toast('已添加 ' + this.newName); this.newName = ''; this.newKey = ''; this.loadUsers(); }
      else Dcu.toast(r.message || '添加失败');
    },
    async toggleEnabled(u) {
      const enabled = u.enabled == 1 ? 0 : 1;
      const r = await Dcu.put('/admin/users/' + u.id, { enabled });
      if (r.ok) this.loadUsers();
    },
    async resetKey(u) {
      if (!confirm('重置 ' + u.name + ' 的 apiKey？旧 key 将失效。')) return;
      const r = await Dcu.put('/admin/users/' + u.id, { resetKey: true });
      if (r.ok) { Dcu.toast('新 key: ' + r.newKey); this.loadUsers(); }
    },
    async removeUser(u) {
      if (!confirm('删除用户 ' + u.name + '？')) return;
      const r = await Dcu.del('/admin/users/' + u.id);
      if (r.ok) this.loadUsers();
    },
  },
  mounted() { this.loadUsers(); },
};
