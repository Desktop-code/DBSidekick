<template>
  <div class="ds-panel">
    <div class="panel-head">
      <span class="panel-title">数据源</span>
      <div class="head-actions">
        <el-button size="small" text type="primary" @click="openCreate">+ 新建连接</el-button>
        <el-button size="small" text :icon="Refresh" :loading="refreshing" title="刷新" @click="onRefresh" />
      </div>
    </div>

    <div class="tree-wrap">
    <el-tree
      v-if="rootReady"
      :key="treeKey"
      ref="treeRef"
      lazy
      :load="loadNode"
      :data="rootNodes"
      node-key="id"
      :props="treeProps"
      highlight-current
      @node-click="onNodeClick"
      @node-contextmenu="onContextMenu"
    >
      <template #default="{ data }">
        <span
          v-if="data.nodeType === 'datasource'"
          class="ds-node"
          :class="{ busy: busyId === data.datasourceId }"
        >
          <span class="status-dot" :class="dsDot(data)" />
          <el-icon class="type-icon" :class="dsKind(data)"><Coin /></el-icon>
          <span class="name">{{ data.label }}</span>
          <span v-if="dbCount(data) != null" class="meta">{{ dbCount(data) }} 个库</span>
          <el-icon v-if="busyId === data.datasourceId" class="spin"><Loading /></el-icon>
          <el-tag v-if="data.unsynced" type="danger" size="small" effect="plain" class="unsync">未同步</el-tag>
          <button type="button" class="node-refresh" title="刷新" @click.stop="onRefreshNode(data)">
            <el-icon><Refresh /></el-icon>
          </button>
        </span>
        <span
          v-else-if="data.nodeType === 'database'"
          class="ds-node db-node"
          :class="{ current: isCurrentDb(data), busy: busyId === data.id }"
        >
          <span class="name">{{ data.label }}</span>
          <span v-if="data.tableCount != null" class="meta">{{ data.tableCount }} 张表</span>
          <el-icon v-if="busyId === data.id" class="spin"><Loading /></el-icon>
          <button type="button" class="node-refresh" title="同步此库" @click.stop="onRefreshDatabase(data)">
            <el-icon><Refresh /></el-icon>
          </button>
        </span>
        <span v-else class="tree-node">
          <span class="node-main">
            <span class="icon">{{ data.icon || '' }}</span>
            <span class="label">{{ data.label }}</span>
          </span>
          <span v-if="data.nodeType === 'table' && data.tableComment" class="comment">
            {{ data.tableComment }}
          </span>
        </span>
      </template>
    </el-tree>
    <div v-else-if="!refreshing" class="empty">暂无数据源</div>
    <div v-else class="empty">加载中…</div>
    </div>

    <div class="history-block" :class="{ collapsed: !historyOpen }">
      <div class="history-head">
        <button type="button" class="history-toggle" @click="historyOpen = !historyOpen">
          <el-icon :size="12">
            <ArrowDown v-if="historyOpen" />
            <ArrowRight v-else />
          </el-icon>
          <span class="panel-title history">历史会话</span>
        </button>
        <div class="head-actions">
          <el-button
            size="small"
            text
            :type="searchOpen ? 'primary' : ''"
            :icon="Search"
            title="搜索会话"
            @click="toggleSearch"
          />
          <el-button size="small" text type="primary" @click="onNewSession">+ 新建会话</el-button>
        </div>
      </div>
      <el-input
        v-show="historyOpen && searchOpen"
        ref="sessionSearchRef"
        v-model="sessionKeyword"
        size="small"
        clearable
        placeholder="搜索会话…"
        class="session-search"
      />
      <ul v-show="historyOpen" class="session-list" v-loading="sessionLoading">
        <template v-for="group in sessionGroups" :key="group.label">
          <li class="session-group">{{ group.label }}</li>
          <li
            v-for="s in group.items"
            :key="s.id"
            class="session-item"
            :class="{ active: s.id === appStore.currentSessionId }"
            @click="openSession(s)"
            @contextmenu.prevent="onSessionCtx($event, s)"
          >
            <span class="title">{{ s.title || '新会话' }}</span>
            <span class="meta">{{ s.messageCount || 0 }} 条 · {{ formatRelative(s.updatedAt) }}</span>
            <button type="button" class="more" title="更多" @click.stop="onSessionCtx($event, s)">⋯</button>
          </li>
        </template>
        <li v-if="!sessionLoading && !sessions.length" class="empty muted">暂无历史会话</li>
      </ul>
    </div>

    <!-- 右键菜单：数据源 -->
    <teleport to="body">
      <div
        v-if="ctx.visible"
        class="ctx-mask"
        @click="closeCtx"
        @contextmenu.prevent="closeCtx"
      >
        <ul
          class="ctx-menu"
          :style="{ left: ctx.x + 'px', top: ctx.y + 'px' }"
          @click.stop
        >
          <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxEdit()">编辑连接</li>
          <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxTest()">测试连接</li>
          <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxSync()">同步 Schema</li>
          <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxIndex()">索引到 Milvus</li>
          <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxAliases()">生成表别名</li>
          <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxInfer()">推断表关系</li>
          <li @click="onCtxRelations()">管理表关系</li>
          <li class="danger" :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxDelete()">删除连接</li>
        </ul>
      </div>
    </teleport>

    <!-- 右键菜单：会话 -->
    <teleport to="body">
      <div
        v-if="sessionCtx.visible"
        class="ctx-mask"
        @click="closeSessionCtx"
        @contextmenu.prevent="closeSessionCtx"
      >
        <ul
          class="ctx-menu"
          :style="{ left: sessionCtx.x + 'px', top: sessionCtx.y + 'px' }"
          @click.stop
        >
          <li @click="onSessionRename">重命名</li>
          <li class="danger" @click="onSessionDelete">删除</li>
        </ul>
      </div>
    </teleport>

    <DbConfigDialog
      v-model="dialogVisible"
      :mode="dialogMode"
      :initial="dialogInitial"
      @saved="onDialogSaved"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowRight, Coin, Loading, Refresh, Search } from '@element-plus/icons-vue'
import DbConfigDialog from './DbConfigDialog.vue'
import { removeDatasource, testDatasource, deleteCheckDatasource } from '../api/datasource'
import { generateAliases, indexSchema, inferRelations, listDatabases, listTables, syncSchema } from '../api/schema'
import { deleteSession, listSessions, renameSession } from '../api/session'
import { useAppStore } from '../stores/app'
import { useTabsStore } from '../stores/tabs'

const appStore = useAppStore()
const tabsStore = useTabsStore()

const treeRef = ref(null)
const refreshing = ref(false)
const rootReady = ref(false)
const rootNodes = ref([])
const treeKey = ref(0)
const busyId = ref('')
const tablesCache = reactive({})
const dbCountCache = reactive({})

const dialogVisible = ref(false)
const dialogMode = ref('create')
const dialogInitial = ref(null)

const ctx = reactive({
  visible: false,
  x: 0,
  y: 0,
  data: null
})

const sessions = ref([])
const sessionLoading = ref(false)
const sessionKeyword = ref('')
const sessionSearchRef = ref(null)
const HISTORY_KEY = 'dbsidekick.historyOpen'
const historyOpen = ref(localStorage.getItem(HISTORY_KEY) !== '0')
const searchOpen = ref(false)
let sessionDebounce = null

const sessionCtx = reactive({
  visible: false,
  x: 0,
  y: 0,
  item: null
})

const ctxBusy = computed(() => {
  const id = ctx.data?.datasourceId
  return !!id && busyId.value === id
})

const treeProps = {
  label: 'label',
  children: 'children',
  isLeaf: 'isLeaf'
}

function dsKind(data) {
  const t = (data?.raw?.type || '').toUpperCase()
  return t.includes('POSTGRES') ? 'postgres' : 'mysql'
}

function dsDot(data) {
  const status = appStore.datasourceStatus[data?.datasourceId]
  if (status === 'up') return 'green'
  if (status === 'down') return 'red'
  return 'gray'
}

function dbCount(data) {
  const n = dbCountCache[data?.datasourceId]
  return typeof n === 'number' ? n : null
}

function isCurrentDb(data) {
  return appStore.currentDatasourceId === data?.datasourceId
    && appStore.currentDatabase === (data?.database || data?.label)
}

function workingDatabase(data) {
  if (data?.nodeType === 'database') return data.database || data.label || ''
  return appStore.currentDatabase || data?.raw?.database || ''
}

async function onRefreshNode(data) {
  if (!data?.datasourceId) return
  delete dbCountCache[data.datasourceId]
  await probeDatabases(data)
  await refreshTreeChildren(data.id)
}

async function onRefreshDatabase(data) {
  const db = data?.database || data?.label
  if (!data?.datasourceId || !db) return
  busyId.value = data.id
  try {
    await syncSchema(data.datasourceId, db)
    data.tableCount = null
    await refreshTreeChildren(data.id)
    ElMessage.success(`已同步 ${db}`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '同步失败')
  } finally {
    busyId.value = ''
  }
}

const sessionGroups = computed(() => {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfYesterday = startOfToday - 86400000
  const weekday = now.getDay() || 7
  const startOfWeek = startOfToday - (weekday - 1) * 86400000
  const startOfMonth = new Date(now.getFullYear(), now.getMonth(), 1).getTime()
  const buckets = [
    { label: '今天', items: [] },
    { label: '昨天', items: [] },
    { label: '本周', items: [] },
    { label: '本月', items: [] },
    { label: '更早', items: [] }
  ]
  for (const s of sessions.value) {
    const t = Date.parse(String(s.updatedAt || '').replace(' ', 'T'))
    if (Number.isNaN(t) || t >= startOfToday) buckets[0].items.push(s)
    else if (t >= startOfYesterday) buckets[1].items.push(s)
    else if (t >= startOfWeek) buckets[2].items.push(s)
    else if (t >= startOfMonth) buckets[3].items.push(s)
    else buckets[4].items.push(s)
  }
  return buckets.filter((b) => b.items.length)
})

let skipDsWatch = false

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

async function toggleSearch() {
  if (!historyOpen.value) {
    historyOpen.value = true
  }
  searchOpen.value = !searchOpen.value
  if (!searchOpen.value) {
    sessionKeyword.value = ''
    return
  }
  await nextTick()
  sessionSearchRef.value?.focus?.()
}

watch(historyOpen, (open) => {
  localStorage.setItem(HISTORY_KEY, open ? '1' : '0')
  if (!open) {
    searchOpen.value = false
    sessionKeyword.value = ''
  }
})

async function loadSessions() {
  sessionLoading.value = true
  try {
    const list = await listSessions({ keyword: sessionKeyword.value.trim() || undefined })
    sessions.value = Array.isArray(list) ? list : []
  } catch (e) {
    ElMessage.error(e?.message || '加载会话失败')
  } finally {
    sessionLoading.value = false
  }
}

function scheduleLoadSessions() {
  clearTimeout(sessionDebounce)
  sessionDebounce = setTimeout(() => loadSessions(), 250)
}

function openSession(s) {
  if (!s?.id) return
  appStore.setCurrentDatasource(s.datasourceId || null)
  appStore.setCurrentSessionId(s.id)
  tabsStore.openAiChat({
    datasourceId: s.datasourceId || null,
    sessionId: s.id,
    title: `AI会话: ${s.title || '新会话'}`
  })
}

async function onNewSession() {
  const id = appStore.currentDatasourceId
  if (!id) {
    ElMessage.warning('请先选择数据源')
    return
  }
  appStore.setCurrentSessionId(null)
  tabsStore.openAiChat({
    datasourceId: id,
    title: 'AI会话: 新会话'
  })
}

function onSessionCtx(e, item) {
  sessionCtx.item = item
  sessionCtx.x = e.clientX
  sessionCtx.y = e.clientY
  sessionCtx.visible = true
}

function closeSessionCtx() {
  sessionCtx.visible = false
}

async function onSessionRename() {
  const item = sessionCtx.item
  closeSessionCtx()
  if (!item) return
  try {
    const { value } = await ElMessageBox.prompt('请输入会话标题', '重命名会话', {
      inputValue: item.title || '新会话',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValidator: (v) => !!(v && v.trim()) || '标题不能为空'
    })
    await renameSession(item.id, value.trim())
    ElMessage.success('已重命名')
    await loadSessions()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(e?.response?.data?.message || e?.message || '重命名失败')
  }
}

async function onSessionDelete() {
  const item = sessionCtx.item
  closeSessionCtx()
  if (!item) return
  try {
    await ElMessageBox.confirm(`确定删除会话「${item.title || '新会话'}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteSession(item.id)
    if (appStore.currentSessionId === item.id) {
      appStore.setCurrentSessionId(null)
    }
    ElMessage.success('已删除')
    await loadSessions()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '删除失败')
  }
}

function buildRootNodes() {
  return (appStore.datasources || []).map((d) => ({
    id: `ds:${d.id}`,
    nodeType: 'datasource',
    datasourceId: d.id,
    label: d.name || d.id,
    icon: '📁',
    raw: d,
    unsynced: false,
    isLeaf: false
  }))
}

async function reloadRoots() {
  refreshing.value = true
  skipDsWatch = true
  rootReady.value = false
  try {
    await appStore.loadDatasources()
    rootNodes.value = buildRootNodes()
    treeKey.value += 1
    await nextTick()
    rootReady.value = rootNodes.value.length > 0
    for (const n of rootNodes.value) {
      probeDatabases(n)
    }
  } catch (e) {
    console.warn('加载数据源失败', e)
    rootNodes.value = []
    rootReady.value = false
  } finally {
    refreshing.value = false
    skipDsWatch = false
  }
}

async function probeDatabases(node) {
  try {
    const names = await listDatabases(node.datasourceId)
    const list = Array.isArray(names) ? names : []
    dbCountCache[node.datasourceId] = list.length
    node.unsynced = false
  } catch {
    dbCountCache[node.datasourceId] = null
  }
}

async function onRefresh() {
  Object.keys(tablesCache).forEach((k) => delete tablesCache[k])
  await reloadRoots()
}

function openCreate() {
  dialogMode.value = 'create'
  dialogInitial.value = null
  dialogVisible.value = true
}

function openEdit(ds) {
  dialogMode.value = 'edit'
  dialogInitial.value = { ...ds }
  dialogVisible.value = true
}

async function onDialogSaved() {
  await reloadRoots()
}

async function loadNode(node, resolve) {
  if (node.level === 0) {
    resolve(rootNodes.value)
    return
  }
  const data = node.data
  if (data.nodeType === 'datasource') {
    let names = []
    try {
      names = await listDatabases(data.datasourceId)
      names = Array.isArray(names) ? names : []
    } catch (e) {
      ElMessage.error(e?.response?.data?.message || e?.message || '读取数据库列表失败')
      names = []
    }
    dbCountCache[data.datasourceId] = names.length
    data.unsynced = false
    resolve(names.map((name) => ({
      id: `db:${data.datasourceId}:${name}`,
      nodeType: 'database',
      datasourceId: data.datasourceId,
      database: name,
      label: name,
      icon: '🗄',
      isLeaf: false
    })))
    return
  }
  if (data.nodeType === 'database') {
    const db = data.database || data.label
    let tables = []
    try {
      tables = await listTables(data.datasourceId, db)
      tables = Array.isArray(tables) ? tables : []
      if (tables.length === 0) {
        busyId.value = data.id
        await syncSchema(data.datasourceId, db)
        tables = await listTables(data.datasourceId, db)
        tables = Array.isArray(tables) ? tables : []
      }
    } catch (e) {
      ElMessage.error(e?.response?.data?.message || e?.message || '同步数据库失败')
      tables = []
    } finally {
      if (busyId.value === data.id) busyId.value = ''
    }
    data.tableCount = tables.length
    resolve(
      tables.map((t) => ({
        id: `tbl:${data.datasourceId}:${db}:${t.tableName}`,
        nodeType: 'table',
        datasourceId: data.datasourceId,
        label: t.tableName,
        icon: '📄',
        tableComment: t.tableComment || '',
        raw: t,
        isLeaf: true
      }))
    )
    return
  }
  resolve([])
}

function onNodeClick(data) {
  if (data?.nodeType === 'datasource') {
    appStore.setCurrentDatasource(data.datasourceId)
    if (data.raw?.database) {
      appStore.setCurrentDatabase(data.raw.database)
    }
    return
  }
  if (data?.nodeType === 'database') {
    appStore.setCurrentDatasource(data.datasourceId)
    appStore.setCurrentDatabase(data.database || data.label)
  }
}

function onContextMenu(event, data) {
  if (data?.nodeType !== 'datasource' && data?.nodeType !== 'database') return
  event.preventDefault()
  event.stopPropagation()
  ctx.data = data
  ctx.x = event.clientX
  ctx.y = event.clientY
  ctx.visible = true
}

function closeCtx() {
  ctx.visible = false
}

function onCtxEdit() {
  const ds = ctx.data?.raw
  closeCtx()
  if (ds) openEdit(ds)
}

async function onCtxTest() {
  const data = ctx.data
  closeCtx()
  if (!data) return
  busyId.value = data.datasourceId
  try {
    const ds = data.raw
    const res = await testDatasource({
      id: ds.id,
      name: ds.name,
      type: ds.type,
      host: ds.host,
      port: ds.port,
      database: ds.database,
      schema: ds.schema,
      username: ds.username,
      password: ''
    })
    appStore.setDatasourceStatus(ds.id, res?.success ? 'up' : 'down')
    if (res?.success) {
      ElMessage.success(`连接成功（${res.latencyMs ?? 0} ms）`)
    } else {
      ElMessage.error(res?.message || '连接失败')
    }
  } catch (e) {
    appStore.setDatasourceStatus(data.datasourceId, 'down')
    ElMessage.error(e?.message || '连接失败')
  } finally {
    busyId.value = ''
  }
}

async function onCtxSync() {
  const data = ctx.data
  closeCtx()
  if (!data) return
  const db = workingDatabase(data)
  if (!db) {
    ElMessage.warning('请先在左侧点选一个数据库')
    return
  }
  busyId.value = data.datasourceId
  const t0 = Date.now()
  try {
    const res = await syncSchema(data.datasourceId, db)
    const n = res?.tableCount ?? 0
    const m = res?.columnCount ?? 0
    const ms = res?.elapsedMs ?? Date.now() - t0
    ElMessage.success(`已同步 ${db}：${n} 张表，${m} 个字段（${ms} ms）`)
    const key = data.nodeType === 'database' ? data.id : `db:${data.datasourceId}:${db}`
    await refreshTreeChildren(key)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '同步失败')
  } finally {
    busyId.value = ''
  }
}

async function onCtxIndex() {
  const data = ctx.data
  closeCtx()
  if (!data) return
  const db = workingDatabase(data)
  if (!db) {
    ElMessage.warning('请先在左侧点选一个数据库')
    return
  }
  busyId.value = data.datasourceId
  const t0 = Date.now()
  try {
    const res = await indexSchema(data.datasourceId, db)
    const n = res?.indexedCount ?? 0
    const ms = res?.elapsedMs ?? Date.now() - t0
    appStore.setDatasourceIndexed(data.datasourceId, true)
    ElMessage.success(`已索引 ${db} 的 ${n} 张表（${ms} ms）`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '索引失败')
  } finally {
    busyId.value = ''
  }
}

async function onCtxAliases() {
  const data = ctx.data
  closeCtx()
  if (!data) return
  busyId.value = data.datasourceId
  const loading = ElMessage({
    message: '生成表别名中，预计 1-2 分钟...',
    duration: 0,
    type: 'info'
  })
  try {
    const res = await generateAliases(data.datasourceId, false)
    const n = res?.generated ?? 0
    const skipped = res?.skipped ?? 0
    const failed = res?.failed ?? 0
    ElMessage.success(`生成 ${n} 张表别名（跳过 ${skipped}，失败 ${failed}）`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '生成别名失败')
  } finally {
    loading.close?.()
    busyId.value = ''
  }
}

function onCtxRelations() {
  const data = ctx.data
  closeCtx()
  if (!data?.datasourceId) return
  tabsStore.openRelationManage({ datasourceId: data.datasourceId })
}

async function onCtxInfer() {
  const data = ctx.data
  closeCtx()
  if (!data) return
  const db = workingDatabase(data)
  if (!db) {
    ElMessage.warning('请先在左侧点选一个数据库')
    return
  }
  busyId.value = data.datasourceId
  try {
    const res = await inferRelations(data.datasourceId, db)
    const n = res?.inferredRelations ?? 0
    const t = res?.tablesUpdated ?? 0
    const ms = res?.elapsedMs ?? 0
    ElMessage.success(`推断 ${n} 条关系，涉及 ${t} 张表（${ms} ms）`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '推断表关系失败')
  } finally {
    busyId.value = ''
  }
}

async function onCtxDelete() {
  const data = ctx.data
  closeCtx()
  if (!data) return
  let check = null
  try {
    check = await deleteCheckDatasource(data.datasourceId)
  } catch (e) {
    ElMessage.error(e?.message || '删除预检失败')
    return
  }
  const schemaN = Number(check?.schemaTableCount || 0)
  const milvusN = Number(check?.milvusVectorCount || 0)
  const sessionN = Number(check?.sessionCount || 0)
  const scriptN = Number(check?.scriptCount || 0)
  const hasRelated = schemaN + milvusN + sessionN + scriptN > 0

  try {
    if (hasRelated) {
      const lines = [
        '该连接关联了以下数据，删除后不可恢复：',
        `- ${schemaN} 张表的结构元数据`,
        `- ${milvusN} 条 Milvus 向量索引`,
        `- ${sessionN} 个会话记录（将失去关联）`,
        `- ${scriptN} 个脚本（将失去关联）`
      ]
      await ElMessageBox.confirm(lines.join('\n'), `确认删除连接「${data.label}」？`, {
        type: 'warning',
        confirmButtonText: '仍然删除',
        cancelButtonText: '取消',
        distinguishCancelAndClose: true
      })
    } else {
      await ElMessageBox.confirm(`确定删除连接「${data.label}」？`, '删除确认', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      })
    }
  } catch {
    return
  }

  busyId.value = data.datasourceId
  try {
    await removeDatasource(data.datasourceId)
    if (appStore.currentDatasourceId === data.datasourceId) {
      appStore.setCurrentDatasource(null)
    }
    ElMessage.success('已删除连接及其关联数据')
    await reloadRoots()
  } catch (e) {
    if (!e?.__sidekickHandled) {
      ElMessage.error(e?.message || '删除失败')
    }
  } finally {
    busyId.value = ''
  }
}

async function refreshTreeChildren(nodeKey) {
  const tree = treeRef.value
  if (!tree) {
    await reloadRoots()
    return
  }
  const storeNode = tree.getNode(nodeKey)
  if (!storeNode) {
    await reloadRoots()
    return
  }
  storeNode.loaded = false
  storeNode.expand()
}

onMounted(() => {
  reloadRoots()
  loadSessions()
})

onUnmounted(() => {
  clearTimeout(sessionDebounce)
})

watch(sessionKeyword, () => scheduleLoadSessions())

watch(
  () => appStore.sessionRefreshTrigger,
  () => loadSessions()
)

watch(
  () => (appStore.datasources || []).map((d) => d.id + ':' + d.name).join('|'),
  async (next, prev) => {
    if (skipDsWatch || next === prev) return
    Object.keys(tablesCache).forEach((k) => delete tablesCache[k])
    rootNodes.value = buildRootNodes()
    treeKey.value += 1
    await nextTick()
    rootReady.value = rootNodes.value.length > 0
    for (const n of rootNodes.value) {
      probeDatabases(n)
    }
  }
)
</script>

<style scoped>
.ds-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 12px;
  overflow: hidden;
  box-sizing: border-box;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
  flex-shrink: 0;
}
.tree-wrap {
  flex: 1;
  min-height: 80px;
  overflow: auto;
}
.tree-wrap :deep(.el-tree-node__content) {
  height: 30px;
}
.tree-wrap :deep(.el-tree-node__content > .el-tree-node__expand-icon) {
  padding: 4px;
}
.panel-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--sk-text-primary);
  margin: 0 0 8px;
}
.panel-title.history {
  margin: 0;
  padding: 0;
  border: none;
}
.history-block {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 0 1 46%;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--sk-border);
}
.history-block.collapsed {
  flex: 0 0 auto;
  margin-top: 4px;
  padding-top: 0;
  border-top: none;
}
.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  flex-shrink: 0;
  gap: 4px;
}
.history-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
  min-width: 0;
}
.session-search {
  margin-bottom: 6px;
  flex-shrink: 0;
}
.session-list {
  list-style: none;
  margin: 0;
  padding: 0 2px 0 0;
  overflow: auto;
  flex: 1;
  min-height: 0;
  scrollbar-width: thin;
  scrollbar-color: #c5c8ce transparent;
}
.session-list::-webkit-scrollbar {
  width: 6px;
}
.session-list::-webkit-scrollbar-track {
  background: transparent;
}
.session-list::-webkit-scrollbar-thumb {
  background: #c5c8ce;
  border-radius: 6px;
}
.session-list::-webkit-scrollbar-thumb:hover {
  background: #909399;
}
.session-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 28px;
  padding: 4px 22px 4px 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}
.session-item:hover {
  background: var(--sk-bg);
}
.session-item.active {
  background: var(--sk-primary-light);
  box-shadow: inset 3px 0 0 var(--sk-primary);
}
.session-item .title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--sk-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-item .meta {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--sk-text-tertiary);
}
.session-item .more {
  position: absolute;
  right: 2px;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--sk-text-tertiary);
  cursor: pointer;
  opacity: 0;
  line-height: 1;
  transition: opacity 0.15s, background 0.15s;
}
.session-item:hover .more {
  opacity: 1;
}
.session-item .more:hover {
  background: var(--sk-card);
  color: var(--sk-text-secondary);
}
.session-group {
  list-style: none;
  margin: 8px 0 4px;
  padding: 0 6px;
  font-size: 12px;
  color: var(--sk-text-tertiary);
}
.session-group:first-child {
  margin-top: 0;
}
.ds-node {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-width: 0;
  padding-right: 2px;
  font-size: 13px;
}
.ds-node.current .name {
  color: var(--sk-primary, #2563eb);
  font-weight: 600;
}
.ds-node .name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--sk-text-primary);
}
.ds-node .meta {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--sk-text-tertiary);
}
.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--sk-text-tertiary);
}
.status-dot.green {
  background: var(--sk-success);
}
.status-dot.red {
  background: var(--sk-danger);
}
.status-dot.gray {
  background: var(--sk-text-tertiary);
}
.type-icon {
  font-size: 14px;
  flex-shrink: 0;
}
.type-icon.mysql {
  color: #3b82f6;
}
.type-icon.postgres {
  color: #1e3a8a;
}
.node-refresh {
  margin-left: auto;
  width: 18px;
  height: 18px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--sk-text-tertiary);
  cursor: pointer;
  opacity: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.15s, background 0.15s;
}
.ds-node:hover .node-refresh {
  opacity: 1;
}
.node-refresh:hover {
  background: var(--sk-bg);
  color: var(--sk-text-secondary);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 0;
}
.tree-node {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.35;
  padding-right: 4px;
  font-size: 13px;
}
.node-main {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.icon {
  font-size: 12px;
}
.comment {
  font-size: 11px;
  color: #909399;
  margin-left: 18px;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.unsync {
  margin-left: 4px;
  transform: scale(0.85);
}
.spin {
  animation: spin 1s linear infinite;
  margin-left: 4px;
}
@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
.empty {
  font-size: 12px;
  color: #909399;
  padding: 8px 4px;
}
.empty.muted {
  opacity: 0.8;
}
</style>

<style>
.ctx-mask {
  position: fixed;
  inset: 0;
  z-index: 4000;
}
.ctx-menu {
  position: fixed;
  min-width: 150px;
  margin: 0;
  padding: 4px 0;
  list-style: none;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.12);
  z-index: 4001;
}
.ctx-menu li {
  padding: 8px 14px;
  font-size: 13px;
  color: #303133;
  cursor: pointer;
}
.ctx-menu li:hover:not(.disabled) {
  background: #f5f7fa;
}
.ctx-menu li.danger {
  color: #f56c6c;
}
.ctx-menu li.disabled {
  color: #c0c4cc;
  cursor: not-allowed;
}
</style>
