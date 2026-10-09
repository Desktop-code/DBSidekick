<template>
  <div class="sql-list">
    <div class="head">
      <span class="title">SQL 清单</span>
      <el-button size="small" text type="primary" @click="onNew">+ 新建</el-button>
    </div>
    <el-input
      ref="searchInputRef"
      v-model="keyword"
      size="small"
      clearable
      :prefix-icon="Search"
      placeholder="搜索脚本..."
      class="search"
    />
    <ul class="list" v-loading="loading">
      <li
        v-for="item in items"
        :key="item.id"
        class="sql-item"
        :class="{ active: previewVisible && preview?.id === item.id }"
        @click="onPreview(item)"
        @contextmenu.prevent="onCtx($event, item)"
      >
        <div class="item-body">
          <div class="item-title">
            {{ item.name }}
            <el-icon v-if="item.favorited" class="star"><StarFilled /></el-icon>
          </div>
          <div class="item-meta">
            <span class="badge">{{ dsName(item.datasourceId) }}</span>
            <span class="time">{{ formatRelative(item.updatedAt) }}</span>
          </div>
        </div>
        <button type="button" class="more" title="更多" @click.stop="onCtx($event, item)">
          <el-icon><MoreFilled /></el-icon>
        </button>
      </li>
      <li v-if="!loading && !items.length" class="empty">暂无脚本</li>
    </ul>

    <!-- 预览 -->
    <el-dialog v-model="previewVisible" :title="preview?.name || '预览'" width="640px" destroy-on-close>
      <pre class="preview-sql">{{ preview?.content || '' }}</pre>
      <template #footer>
        <el-button @click="copyPreview">复制</el-button>
        <el-button @click="openInEditor">在查询编辑器中打开</el-button>
        <el-button type="primary" @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 重命名 -->
    <el-dialog v-model="renameVisible" title="重命名" width="400px" destroy-on-close>
      <el-input v-model="renameName" placeholder="脚本名称" @keyup.enter="confirmRename" />
      <template #footer>
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" :loading="renaming" @click="confirmRename">确定</el-button>
      </template>
    </el-dialog>

    <!-- 右键菜单 -->
    <teleport to="body">
      <div v-if="ctx.visible" class="script-ctx-mask" @click="closeCtx" @contextmenu.prevent="closeCtx">
        <ul class="script-ctx-menu" :style="{ left: ctx.x + 'px', top: ctx.y + 'px' }" @click.stop>
          <li @click="onRename">重命名</li>
          <li @click="onToggleFav">{{ ctx.item?.favorited ? '取消收藏' : '收藏' }}</li>
          <li class="danger" @click="onDelete">删除</li>
        </ul>
      </div>
    </teleport>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled, Search, StarFilled } from '@element-plus/icons-vue'
import {
  deleteScript,
  getScript,
  listScripts,
  saveScript,
  toggleScriptFavorite
} from '../api/script'
import { useAppStore } from '../stores/app'
import { useTabsStore } from '../stores/tabs'

const appStore = useAppStore()
const tabsStore = useTabsStore()

const keyword = ref('')
const searchInputRef = ref(null)
const items = ref([])
const loading = ref(false)
let debounceTimer = null

const previewVisible = ref(false)
const preview = ref(null)

const renameVisible = ref(false)
const renameName = ref('')
const renameId = ref('')
const renaming = ref(false)

const ctx = reactive({
  visible: false,
  x: 0,
  y: 0,
  item: null
})

function dsName(id) {
  if (!id) return '未绑定'
  const d = appStore.datasources.find((x) => x.id === id)
  return d?.name || id.slice(0, 8)
}

function formatRelative(raw) {
  if (!raw) return '-'
  const t = Date.parse(String(raw).replace(' ', 'T'))
  if (Number.isNaN(t)) return raw
  const diff = Date.now() - t
  const sec = Math.floor(diff / 1000)
  if (sec < 60) return '刚刚'
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour}小时前`
  const day = Math.floor(hour / 24)
  if (day === 1) return '昨天'
  if (day < 7) return `${day}天前`
  if (day < 30) return `${Math.floor(day / 7)}周前`
  return String(raw).slice(0, 10)
}

async function loadList() {
  loading.value = true
  try {
    const list = await listScripts({ keyword: keyword.value.trim() || undefined })
    items.value = Array.isArray(list) ? list : []
  } catch (e) {
    ElMessage.error(e?.message || '加载脚本失败')
  } finally {
    loading.value = false
  }
}

function scheduleLoad() {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => loadList(), 300)
}

function onNew() {
  tabsStore.openQueryEditor()
}

async function onPreview(item) {
  try {
    const detail = await getScript(item.id)
    preview.value = detail
    previewVisible.value = true
  } catch (e) {
    ElMessage.error(e?.message || '加载失败')
  }
}

function copyPreview() {
  const sql = preview.value?.content || ''
  navigator.clipboard?.writeText(sql).then(
    () => ElMessage.success('已复制'),
    () => ElMessage.warning('复制失败')
  )
}

async function openInEditor() {
  const id = preview.value?.id
  if (!id) {
    ElMessage.warning('没有可打开的 SQL')
    return
  }
  try {
    const detail = await getScript(id)
    if (!detail?.content) {
      ElMessage.warning('没有可打开的 SQL')
      return
    }
    tabsStore.openQueryEditor({
      initialSql: detail.content,
      initialDatasourceId: detail.datasourceId || null,
      scriptId: detail.id || null,
      title: detail.name ? `查询: ${detail.name}` : '查询编辑器'
    })
    previewVisible.value = false
  } catch (e) {
    ElMessage.error(e?.message || '加载脚本失败')
  }
}

function onCtx(e, item) {
  ctx.item = item
  ctx.x = e.clientX
  ctx.y = e.clientY
  ctx.visible = true
}

function closeCtx() {
  ctx.visible = false
}

function onRename() {
  const item = ctx.item
  closeCtx()
  if (!item) return
  renameId.value = item.id
  renameName.value = item.name
  renameVisible.value = true
}

async function confirmRename() {
  const name = renameName.value.trim()
  if (!name) {
    ElMessage.warning('名称不能为空')
    return
  }
  renaming.value = true
  try {
    const detail = await getScript(renameId.value)
    detail.name = name
    await saveScript(detail)
    renameVisible.value = false
    ElMessage.success('已重命名')
    await loadList()
  } catch (e) {
    ElMessage.error(e?.message || '重命名失败')
  } finally {
    renaming.value = false
  }
}

async function onToggleFav() {
  const item = ctx.item
  closeCtx()
  if (!item) return
  try {
    await toggleScriptFavorite(item.id)
    await loadList()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

async function onDelete() {
  const item = ctx.item
  closeCtx()
  if (!item) return
  try {
    await ElMessageBox.confirm(`确定删除脚本「${item.name}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteScript(item.id)
    ElMessage.success('已删除')
    await loadList()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

watch(keyword, () => scheduleLoad())
watch(
  () => appStore.scriptRefreshTrigger,
  () => loadList()
)
watch(
  () => appStore.scriptFocusToken,
  async () => {
    await loadList()
    await nextTick()
    searchInputRef.value?.focus?.()
    const root = searchInputRef.value?.$el
    root?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' })
  }
)

onMounted(() => {
  if (!appStore.datasources.length) {
    appStore.loadDatasources().catch(() => {})
  }
  loadList()
})

onUnmounted(() => {
  clearTimeout(debounceTimer)
})
</script>

<style scoped>
.sql-list {
  height: 100%;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  box-sizing: border-box;
  min-height: 0;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}
.title {
  font-size: 12px;
  font-weight: 600;
  color: var(--sk-text-primary);
}
.search {
  flex-shrink: 0;
}
.list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow: auto;
  flex: 1;
  min-height: 0;
}
.sql-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 6px;
  margin-bottom: 4px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, box-shadow 0.15s;
}
.sql-item:hover {
  background: var(--sk-card);
  border-color: var(--sk-border);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}
.sql-item.active {
  background: var(--sk-primary-light);
  border-color: var(--sk-primary);
}
.item-body {
  min-width: 0;
  flex: 1;
}
.item-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--sk-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.star {
  color: var(--sk-warning);
  font-size: 13px;
}
.item-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--sk-text-tertiary);
}
.badge {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--sk-bg);
  color: var(--sk-text-secondary);
  line-height: 18px;
}
.more {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--sk-text-tertiary);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s, background 0.15s;
}
.sql-item:hover .more {
  opacity: 1;
}
.more:hover {
  background: var(--sk-bg);
  color: var(--sk-text-secondary);
}
.empty {
  padding: 16px 8px;
  font-size: 12px;
  color: var(--sk-text-tertiary);
  text-align: center;
}
.preview-sql {
  margin: 0;
  padding: 12px;
  background: #1e1e2e;
  color: #cdd6f4;
  border-radius: 6px;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  max-height: 420px;
  overflow: auto;
}
</style>

<style>
.script-ctx-mask {
  position: fixed;
  inset: 0;
  z-index: 4000;
}
.script-ctx-menu {
  position: fixed;
  min-width: 140px;
  margin: 0;
  padding: 4px 0;
  list-style: none;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.12);
  z-index: 4001;
}
.script-ctx-menu li {
  padding: 8px 14px;
  font-size: 13px;
  color: #303133;
  cursor: pointer;
}
.script-ctx-menu li:hover {
  background: #f5f7fa;
}
.script-ctx-menu li.danger {
  color: #f56c6c;
}
</style>
