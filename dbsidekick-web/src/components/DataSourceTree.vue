<template>
  <div class="ds-panel">
    <section ref="dsBlockRef" class="block ds-block" :class="{ collapsed: !dsOpen }">
    <div class="panel-head">
      <button type="button" class="section-toggle" @click="dsOpen = !dsOpen">
        <el-icon :size="12">
          <ArrowDown v-if="dsOpen" />
          <ArrowRight v-else />
        </el-icon>
        <span class="panel-title">数据源</span>
      </button>
      <div class="head-actions">
        <el-button size="small" text type="primary" @click="openCreate">+ 新建连接</el-button>
        <el-button size="small" text :icon="Refresh" :loading="refreshing" title="刷新" @click="onRefresh" />
        <el-tooltip content="收起" placement="bottom">
          <button type="button" class="fold-btn" aria-label="收起" @click="emit('collapse')">
            <el-icon :size="16"><Fold /></el-icon>
          </button>
        </el-tooltip>
      </div>
    </div>

    <div v-show="dsOpen" class="tree-wrap">
      <el-tree
      v-if="rootReady"
      :key="treeKey"
      ref="treeRef"
      lazy
      :indent="18"
      :load="loadNode"
      :data="rootNodes"
      node-key="id"
      :props="treeProps"
      highlight-current
      @node-click="onNodeClick"
      @node-contextmenu="onContextMenu"
    >
      <template #default="{ data }">
        <el-tooltip
          v-if="data.nodeType === 'datasource'"
          :content="String(data.label || '')"
          placement="right"
          :show-after="500"
          :disabled="!data.label"
        >
          <span
            class="ds-node"
            :class="{ busy: busyId === data.datasourceId }"
          >
            <span class="status-dot" :class="dsDot(data)" />
            <el-icon class="type-icon" :class="dsKind(data)"><Coin /></el-icon>
            <span class="name">{{ data.label }}</span>
            <span v-if="dbCount(data) != null" class="db-badge" :title="`${dbCount(data)} 个库`">{{ dbCount(data) }}</span>
            <el-icon v-if="busyId === data.datasourceId" class="spin"><Loading /></el-icon>
            <el-tag v-if="data.unsynced" type="danger" size="small" effect="plain" class="unsync">未同步</el-tag>
            <button type="button" class="node-refresh" title="刷新" @click.stop="onRefreshNode(data)">
              <el-icon><Refresh /></el-icon>
            </button>
          </span>
        </el-tooltip>
        <el-tooltip
          v-else-if="data.nodeType === 'database'"
          :content="String(data.label || '')"
          placement="right"
          :show-after="500"
          :disabled="!data.label"
        >
          <span
            class="ds-node db-node"
            :class="{ current: isCurrentDb(data), busy: busyId === data.id }"
          >
            <el-icon class="node-kind"><Folder /></el-icon>
            <span class="name">{{ data.label }}</span>
            <span v-if="data.tableCount != null" class="meta">{{ data.tableCount }} 张表</span>
            <el-icon v-if="busyId === data.id" class="spin"><Loading /></el-icon>
            <button type="button" class="node-refresh" title="同步此库" @click.stop="onRefreshDatabase(data)">
              <el-icon><Refresh /></el-icon>
            </button>
          </span>
        </el-tooltip>
        <el-tooltip
          v-else
          placement="right"
          :show-after="500"
          :disabled="!treeNodeTip(data)"
        >
          <template #content>
            <div class="tree-tip">{{ treeNodeTip(data) }}</div>
          </template>
          <span class="tree-node">
            <el-icon class="node-kind"><Document /></el-icon>
            <span class="label">{{ data.label }}</span>
            <span v-if="data.nodeType === 'table' && data.tableComment" class="comment">
              {{ data.tableComment }}
            </span>
          </span>
        </el-tooltip>
      </template>
    </el-tree>
    <div v-else-if="!refreshing" class="empty">
      还没有数据源，点击
      <button type="button" class="empty-link" @click="openCreate">+ 新建连接</button>
      开始
    </div>
    <div v-else class="empty">加载中…</div>
    </div>
    </section>

    <section ref="historyBlockRef" class="block history-block" :class="{ collapsed: !historyOpen }">
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
            <span class="title-wrap">
              <span class="title" :data-full="s.title || '新会话'">{{ s.title || '新会话' }}</span>
            </span>
            <span class="meta">{{ s.messageCount || 0 }} 条 · {{ formatRelative(s.updatedAt) }}</span>
            <button type="button" class="more" title="更多" @click.stop="onSessionCtx($event, s)">⋯</button>
          </li>
        </template>
        <li v-if="!sessionLoading && !filteredSessions.length && sessionKeyword.trim()" class="empty">没有找到匹配的会话</li>
        <li v-else-if="!sessionLoading && !sessions.length" class="empty">还没有会话记录</li>
      </ul>
    </section>

    <!-- 右键菜单：数据源 -->
    <teleport to="body">
      <div
        v-if="ctx.visible"
        class="ctx-mask"
        @click="closeCtx"
        @contextmenu.prevent="closeCtx"
      >
        <ul
          ref="menuRef"
          class="ctx-menu"
          :style="{ left: ctx.x + 'px', top: ctx.y + 'px' }"
          @click.stop
        >
          <template v-if="ctx.data?.nodeType === 'datasource'">
            <li @click="onDsNewSession()"><el-icon class="mi" :size="14"><ChatDotRound /></el-icon>新建会话</li>
            <li @click="onDsNewQuery()"><el-icon class="mi" :size="14"><EditPen /></el-icon>新建查询</li>
            <li class="sep" />
            <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxEdit()"><el-icon class="mi" :size="14"><Edit /></el-icon>编辑连接</li>
            <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxTest()"><el-icon class="mi" :size="14"><CircleCheck /></el-icon>测试连接</li>
            <li class="sep" />
            <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxSyncInstance()"><el-icon class="mi" :size="14"><Refresh /></el-icon>同步整个实例的 Schema</li>
            <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxIndexInstance()"><el-icon class="mi" :size="14"><Coin /></el-icon>索引整个实例到 Milvus</li>
            <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxAliases()"><el-icon class="mi" :size="14"><CollectionTag /></el-icon>生成所有表别名</li>
            <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxInferInstance()"><el-icon class="mi" :size="14"><Share /></el-icon>推断所有表关系</li>
            <li @click="onCtxRelations()"><el-icon class="mi" :size="14"><Connection /></el-icon>管理表关系</li>
            <li class="sep" />
            <li @click="onCtxBackup()"><el-icon class="mi" :size="14"><Download /></el-icon>备份此连接的数据</li>
            <li class="sep" />
            <li class="danger" :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxDelete()"><el-icon class="mi" :size="14"><Delete /></el-icon>删除连接</li>
          </template>
          <template v-else-if="ctx.data?.nodeType === 'database'">
            <li @click="onCtxNewSession()"><el-icon class="mi" :size="14"><ChatDotRound /></el-icon>新建会话（绑定此库）</li>
            <li @click="onCtxNewQuery()"><el-icon class="mi" :size="14"><EditPen /></el-icon>新建查询（绑定此库）</li>
            <li class="sep" />
            <li @click="onCtxRefreshTables()"><el-icon class="mi" :size="14"><Refresh /></el-icon>刷新表列表</li>
            <li class="sep" />
            <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxSyncDatabase()"><el-icon class="mi" :size="14"><Refresh /></el-icon>同步此库的 Schema</li>
            <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxIndexDatabase()"><el-icon class="mi" :size="14"><Coin /></el-icon>索引此库到 Milvus</li>
            <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxAliases()"><el-icon class="mi" :size="14"><CollectionTag /></el-icon>生成此库的表别名</li>
            <li :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxInferDatabase()"><el-icon class="mi" :size="14"><Share /></el-icon>推断此库的表关系</li>
            <li class="sep" />
            <li @click="onCtxCopyDbName()"><el-icon class="mi" :size="14"><DocumentCopy /></el-icon>复制库名</li>
            <li class="sep" />
            <li class="warn" :class="{ disabled: ctxBusy }" @click="!ctxBusy && onCtxClearIndex()"><el-icon class="mi" :size="14"><Delete /></el-icon>清空此库的索引</li>
          </template>
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
import {
  ArrowDown,
  ArrowRight,
  ChatDotRound,
  CircleCheck,
  Coin,
  CollectionTag,
  Connection,
  Delete,
  Document,
  DocumentCopy,
  Download,
  Edit,
  EditPen,
  Fold,
  Folder,
  Loading,
  Refresh,
  Search,
  Share
} from '@element-plus/icons-vue'
import DbConfigDialog from './DbConfigDialog.vue'
import { removeDatasource, testDatasource, deleteCheckDatasource } from '../api/datasource'
import { clearSchemaIndex, generateAliases, indexSchema, inferRelations, listDatabases, listTables, syncSchema } from '../api/schema'
import { backupData, chooseSystemFile } from '../api/system'
import { deleteSession, listSessions, renameSession } from '../api/session'
import { useAppStore } from '../stores/app'
import { useTabsStore } from '../stores/tabs'

const emit = defineEmits(['collapse'])

const appStore = useAppStore()
const tabsStore = useTabsStore()

const treeRef = ref(null)
const menuRef = ref(null)
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
const DS_SECTION_KEY = 'dbsidekick.dsSectionOpen'
const historyOpen = ref(localStorage.getItem(HISTORY_KEY) !== '0')
const dsOpen = ref(localStorage.getItem(DS_SECTION_KEY) !== '0')
const dsBlockRef = ref(null)
const historyBlockRef = ref(null)
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
  return 'hollow'
}

function dbCount(data) {
  const n = dbCountCache[data?.datasourceId]
  return typeof n === 'number' ? n : null
}

function isCurrentDb(data) {
  return appStore.currentDatasourceId === data?.datasourceId
    && appStore.currentDatabase === (data?.database || data?.label)
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

const filteredSessions = computed(() => {
  const q = sessionKeyword.value.trim().toLowerCase()
  if (!q) return sessions.value
  return sessions.value.filter((s) => String(s.title || '新会话').toLowerCase().includes(q))
})

function treeNodeTip(data) {
  if (!data) return ''
  const name = String(data.label || '').trim()
  const comment = String(data.tableComment || data.comment || '').trim()
  if (data.nodeType === 'table') {
    if (name && comment) return `${name}\n${comment}`
    return name || comment
  }
  return name
}

const sessionGroups = computed(() => {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfYesterday = startOfToday - 86400000
  const weekday = now.getDay() || 7
  const startOfWeek = startOfToday - (weekday - 1) * 86400000
  const buckets = [
    { label: '今天', items: [] },
    { label: '昨天', items: [] },
    { label: '本周', items: [] },
    { label: '更早', items: [] }
  ]
  for (const s of filteredSessions.value) {
    const t = Date.parse(String(s.updatedAt || '').replace(' ', 'T'))
    if (Number.isNaN(t) || t >= startOfToday) buckets[0].items.push(s)
    else if (t >= startOfYesterday) buckets[1].items.push(s)
    else if (t >= startOfWeek) buckets[2].items.push(s)
    else buckets[3].items.push(s)
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
  if (min < 60) return `${min}分`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour}h`
  const day = Math.floor(hour / 24)
  if (day === 1) return '昨天'
  if (day < 7) return `${day}天`
  if (day < 30) return `${Math.floor(day / 7)}周`
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

watch(dsOpen, (open) => {
  localStorage.setItem(DS_SECTION_KEY, open ? '1' : '0')
})

watch(
  () => appStore.sidebarFocusToken,
  async (token) => {
    if (!token) return
    if (appStore.sidebarFocus === 'datasource') {
      dsOpen.value = true
      await nextTick()
      dsBlockRef.value?.scrollIntoView?.({ block: 'nearest' })
    } else if (appStore.sidebarFocus === 'history') {
      historyOpen.value = true
      await nextTick()
      historyBlockRef.value?.scrollIntoView?.({ block: 'nearest' })
    }
  }
)

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
  if (s.dbName) {
    appStore.setCurrentDatabase(s.dbName)
  }
  appStore.setCurrentSessionId(s.id)
  tabsStore.openAiChat({
    datasourceId: s.datasourceId || null,
    dbName: s.dbName || '',
    sessionId: s.id,
    title: `AI会话: ${s.title || '新会话'}`
  })
}

function onNewSession() {
  appStore.openSessionDialog({
    datasourceId: appStore.currentDatasourceId || '',
    dbName: appStore.currentDatabase || ''
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
    testConnectionNode(data)
    return
  }
  if (data?.nodeType === 'database') {
    appStore.setCurrentDatasource(data.datasourceId)
    appStore.setCurrentDatabase(data.database || data.label)
  }
}

function schemaScope() {
  const data = ctx.data
  if (data?.nodeType !== 'database' || !data.datasourceId) return null
  return {
    datasourceId: data.datasourceId,
    dbName: data.database || data.label || ''
  }
}

function onCtxNewSession() {
  const scope = schemaScope()
  closeCtx()
  if (!scope?.dbName) return
  appStore.setCurrentDatasource(scope.datasourceId)
  appStore.setCurrentDatabase(scope.dbName)
  appStore.openSessionDialog({ datasourceId: scope.datasourceId, dbName: scope.dbName })
}

function onCtxNewQuery() {
  const scope = schemaScope()
  closeCtx()
  if (!scope) return
  appStore.setCurrentDatasource(scope.datasourceId)
  if (scope.dbName) appStore.setCurrentDatabase(scope.dbName)
  tabsStore.openQueryEditor({ initialDatasourceId: scope.datasourceId })
}

function onContextMenu(event, data) {
  if (data?.nodeType !== 'datasource' && data?.nodeType !== 'database') return
  event.preventDefault()
  event.stopPropagation()
  ctx.data = data
  ctx.x = event.clientX
  ctx.y = event.clientY
  ctx.visible = true
  nextTick(() => {
    const el = menuRef.value
    if (!el) return
    const rect = el.getBoundingClientRect()
    const pad = 8
    let x = ctx.x
    let y = ctx.y
    if (x + rect.width > window.innerWidth - pad) {
      x = Math.max(pad, window.innerWidth - rect.width - pad)
    }
    if (y + rect.height > window.innerHeight - pad) {
      y = Math.max(pad, window.innerHeight - rect.height - pad)
    }
    ctx.x = x
    ctx.y = y
  })
}

function onDsNewSession() {
  const data = ctx.data
  closeCtx()
  if (!data?.datasourceId) return
  const db = data.raw?.database || ''
  appStore.setCurrentDatasource(data.datasourceId)
  if (db) appStore.setCurrentDatabase(db)
  appStore.openSessionDialog({ datasourceId: data.datasourceId, dbName: db })
}

function onDsNewQuery() {
  const data = ctx.data
  closeCtx()
  if (!data?.datasourceId) return
  appStore.setCurrentDatasource(data.datasourceId)
  if (data.raw?.database) appStore.setCurrentDatabase(data.raw.database)
  tabsStore.openQueryEditor({ initialDatasourceId: data.datasourceId })
}

function onCtxRefreshTables() {
  const id = ctx.data?.id
  closeCtx()
  if (id) refreshTreeChildren(id)
}

async function onCtxCopyDbName() {
  const name = schemaScope()?.dbName || ''
  closeCtx()
  if (!name) return
  try {
    await navigator.clipboard.writeText(name)
    ElMessage.success('已复制库名')
  } catch {
    ElMessage.error('复制失败')
  }
}

function closeCtx() {
  ctx.visible = false
}

function onCtxEdit() {
  const ds = ctx.data?.raw
  closeCtx()
  if (ds) openEdit(ds)
}

async function testConnectionNode(data) {
  const ds = data?.raw
  if (!ds?.id) return
  if (busyId.value === ds.id) return
  busyId.value = ds.id
  try {
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
    appStore.setDatasourceStatus(ds.id, 'down')
    ElMessage.error(e?.message || '连接失败')
  } finally {
    if (busyId.value === ds.id) busyId.value = ''
  }
}

async function onCtxTest() {
  const data = ctx.data
  closeCtx()
  if (!data) return
  await testConnectionNode(data)
}

async function onCtxSyncDatabase() {
  const data = ctx.data
  const db = data?.database || data?.label
  closeCtx()
  if (!data?.datasourceId || !db) return
  busyId.value = data.datasourceId
  const t0 = Date.now()
  try {
    const res = await syncSchema(data.datasourceId, db)
    const n = res?.tableCount ?? 0
    const m = res?.columnCount ?? 0
    const ms = res?.elapsedMs ?? Date.now() - t0
    ElMessage.success(`已同步 ${db}：${n} 张表，${m} 个字段（${ms} ms）`)
    await refreshTreeChildren(data.id)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '同步失败')
  } finally {
    busyId.value = ''
  }
}

async function onCtxSyncInstance() {
  const data = ctx.data
  closeCtx()
  if (!data?.datasourceId) return
  busyId.value = data.datasourceId
  const loading = ElMessage({ message: '正在同步整个实例...', duration: 0, type: 'info' })
  try {
    const names = await listDatabases(data.datasourceId)
    const list = Array.isArray(names) ? names : []
    if (!list.length) {
      ElMessage.warning('没有可同步的数据库')
      return
    }
    let tables = 0
    let cols = 0
    let failed = 0
    for (const db of list) {
      try {
        const res = await syncSchema(data.datasourceId, db)
        tables += res?.tableCount ?? 0
        cols += res?.columnCount ?? 0
      } catch {
        failed += 1
      }
    }
    const ok = list.length - failed
    ElMessage.success(`已同步 ${ok} 个库，${tables} 张表，${cols} 个字段${failed ? `，失败 ${failed} 个` : ''}`)
    await refreshTreeChildren(data.id)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '同步失败')
  } finally {
    loading.close?.()
    busyId.value = ''
  }
}

async function onCtxIndexDatabase() {
  const data = ctx.data
  const db = data?.database || data?.label
  closeCtx()
  if (!data?.datasourceId || !db) return
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

async function onCtxIndexInstance() {
  const data = ctx.data
  closeCtx()
  if (!data?.datasourceId) return
  busyId.value = data.datasourceId
  const loading = ElMessage({ message: '正在索引整个实例...', duration: 0, type: 'info' })
  try {
    const names = await listDatabases(data.datasourceId)
    const list = Array.isArray(names) ? names : []
    if (!list.length) {
      ElMessage.warning('没有可索引的数据库')
      return
    }
    let indexed = 0
    let failed = 0
    for (const db of list) {
      try {
        const res = await indexSchema(data.datasourceId, db)
        indexed += res?.indexedCount ?? 0
      } catch {
        failed += 1
      }
    }
    if (indexed > 0) appStore.setDatasourceIndexed(data.datasourceId, true)
    ElMessage.success(`已索引 ${indexed} 张表${failed ? `，${failed} 个库跳过或失败` : ''}`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '索引失败')
  } finally {
    loading.close?.()
    busyId.value = ''
  }
}

async function onCtxAliases() {
  const data = ctx.data
  const db = data?.nodeType === 'database' ? (data.database || data.label) : ''
  closeCtx()
  if (!data?.datasourceId) return
  busyId.value = data.datasourceId
  const loading = ElMessage({
    message: '生成表别名中，预计 1-2 分钟...',
    duration: 0,
    type: 'info'
  })
  try {
    const res = await generateAliases(data.datasourceId, false, db || undefined)
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
  const db =
    data.nodeType === 'database'
      ? data.database || data.label
      : appStore.currentDatabase || undefined
  tabsStore.openRelationManage({
    datasourceId: data.datasourceId,
    dbName: db || undefined
  })
}

async function onCtxInferDatabase() {
  const data = ctx.data
  const db = data?.database || data?.label
  closeCtx()
  if (!data?.datasourceId || !db) return
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

async function onCtxInferInstance() {
  const data = ctx.data
  closeCtx()
  if (!data?.datasourceId) return
  busyId.value = data.datasourceId
  try {
    const res = await inferRelations(data.datasourceId)
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

function backupFileName() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `dbsidekick-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.db`
}

async function onCtxBackup() {
  closeCtx()
  try {
    const picked = await chooseSystemFile({
      title: '保存备份',
      mode: 'save',
      defaultName: backupFileName()
    })
    if (picked?.canceled || !picked?.path) return
    const res = await backupData(picked.path)
    ElMessage.success(`备份成功：${res?.path || picked.path}`)
  } catch {
    // 拦截器已提示
  }
}

async function onCtxClearIndex() {
  const data = ctx.data
  const db = data?.database || data?.label
  closeCtx()
  if (!data?.datasourceId || !db) return
  try {
    await ElMessageBox.confirm(`将清空库「${db}」的向量索引，表结构不受影响。确定继续？`, '清空索引', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  busyId.value = data.datasourceId
  try {
    const res = await clearSchemaIndex(data.datasourceId, db)
    ElMessage.success(`已清空 ${res?.deleted ?? 0} 条索引`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '清空索引失败')
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
  gap: 8px;
  padding: 8px;
  background: var(--sk-bg);
  overflow: hidden;
  box-sizing: border-box;
}
.block {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 12px;
  background: var(--sk-bg-elevated);
  border-radius: 8px;
  box-sizing: border-box;
}
.ds-block {
  flex: 1 1 0;
}
.ds-block.collapsed {
  flex: 0 0 auto;
}
.panel-head,
.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  flex-shrink: 0;
  gap: 4px;
}
.ds-block.collapsed .panel-head,
.history-block.collapsed .history-head {
  margin-bottom: 0;
}
.section-toggle,
.history-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--sk-text-secondary);
  cursor: pointer;
  min-width: 0;
}
.tree-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.tree-wrap :deep(.el-tree) {
  background: transparent;
  color: var(--sk-text-primary);
}
.tree-wrap :deep(.el-tree-node__content) {
  height: 32px;
  border-radius: 6px;
}
.tree-wrap :deep(.el-tree-node__content:hover) {
  background: var(--sk-hover-bg);
}
.tree-wrap :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: var(--sk-active-bg);
}
.tree-wrap :deep(.el-tree-node__content > .el-tree-node__expand-icon) {
  padding: 9px 6px;
  margin-left: -2px;
  font-size: 14px;
  color: var(--sk-text-secondary);
}
.tree-wrap :deep(.el-tree-node__expand-icon svg) {
  width: 14px;
  height: 14px;
}
.tree-wrap :deep(.el-tree-node__expand-icon.is-leaf) {
  color: transparent;
}
.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sk-text-primary);
  margin: 0;
}
.history-block {
  flex: 1 1 0;
  margin: 0;
  border: none;
}
.history-block.collapsed {
  flex: 0 0 auto;
}
.session-search {
  margin-bottom: 6px;
  flex-shrink: 0;
}
.session-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow: auto;
  flex: 1;
  min-height: 0;
}
.session-item {
  position: relative;
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 8px;
  height: 32px;
  max-height: 32px;
  padding: 0 8px 0 12px;
  border-radius: 6px;
  cursor: pointer;
  box-sizing: border-box;
  overflow: hidden;
  white-space: nowrap;
  transition: background 0.15s;
}
.session-item:hover {
  background: var(--sk-hover-bg);
  overflow: visible;
  z-index: 2;
}
.session-item.active {
  background: var(--sk-active-bg);
  box-shadow: inset 3px 0 0 var(--sk-primary);
}
.session-item .title-wrap {
  flex: 1 1 auto;
  min-width: 0;
  position: relative;
  height: 32px;
  overflow: hidden;
}
.session-item .title {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  line-height: 32px;
  color: var(--sk-text-primary);
}
/* 方案 A：悬停时标题横向伸展盖住右侧元信息 */
.session-item:hover .title {
  position: absolute;
  left: 0;
  top: 0;
  width: max-content;
  max-width: calc(100% + 108px);
  padding-right: 28px;
  overflow: visible;
  text-overflow: clip;
  background: var(--sk-hover-bg);
  z-index: 2;
  box-shadow: 8px 0 8px -4px var(--sk-hover-bg);
}
.session-item.active:hover .title {
  background: var(--sk-active-bg);
  box-shadow: 8px 0 8px -4px var(--sk-active-bg);
}
.session-item .meta {
  flex: 0 0 88px;
  width: 88px;
  font-size: 12px;
  line-height: 32px;
  color: var(--sk-text-tertiary);
  text-align: right;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: opacity 0.15s;
}
.session-item:hover .meta {
  opacity: 0;
}
.session-item .more {
  width: 0;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--sk-text-secondary);
  cursor: pointer;
  opacity: 0;
  overflow: hidden;
  line-height: 20px;
  flex-shrink: 0;
  position: relative;
  z-index: 3;
}
.session-item:hover .more,
.session-item:focus-within .more {
  width: 20px;
  opacity: 1;
}
.session-item .more:hover {
  background: var(--sk-bg-elevated);
  color: var(--sk-text-primary);
}
.session-group {
  display: flex;
  align-items: center;
  gap: 8px;
  list-style: none;
  margin: 12px 0 4px;
  padding: 0 4px;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--sk-text-strong);
}
.session-group::before {
  content: '';
  width: 3px;
  height: 12px;
  border-radius: 2px;
  background: var(--sk-primary);
  flex-shrink: 0;
}
.ds-node {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  width: 100%;
  min-width: 0;
  overflow: hidden;
  padding-right: 2px;
  font-size: 13px;
}
.ds-node.current .name {
  color: var(--sk-primary, var(--sk-primary-strong));
  font-weight: 600;
}
.ds-node .name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--sk-text-primary);
  font-size: 14px;
  font-weight: 600;
}
.db-node .name,
.tree-node .label {
  font-size: 13px;
  font-weight: 400;
}
.db-badge {
  flex-shrink: 0;
  margin-left: auto;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--sk-hover-bg);
  color: var(--sk-text-secondary);
  font-size: 12px;
  font-weight: 500;
  line-height: 16px;
}
.ds-node .meta {
  flex-shrink: 0;
  margin-left: auto;
  font-size: 12px;
  color: var(--sk-text-tertiary);
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  box-sizing: border-box;
  background: transparent;
}
.status-dot.green {
  background: var(--sk-success);
}
.status-dot.red {
  background: var(--sk-danger);
}
.status-dot.hollow {
  background: transparent;
  box-shadow: inset 0 0 0 1.5px var(--sk-text-tertiary);
}
.type-icon,
.node-kind {
  font-size: 14px;
  flex-shrink: 0;
}
.type-icon.mysql {
  color: var(--sk-primary);
}
.type-icon.postgres {
  color: var(--sk-postgres);
}
.node-kind {
  color: var(--sk-text-tertiary);
}
.node-refresh {
  margin-left: 4px;
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
.ds-node:hover .node-refresh,
.tree-wrap :deep(.el-tree-node__content:hover) .node-refresh {
  opacity: 1;
}
.tree-wrap :deep(.el-tooltip__trigger) {
  flex: 1;
  min-width: 0;
  display: flex;
  overflow: hidden;
}
.tree-tip {
  max-width: 320px;
  white-space: pre-line;
  line-height: 1.45;
  font-size: 12px;
}
.node-refresh:hover {
  background: var(--sk-bg);
  color: var(--sk-text-secondary);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.fold-btn {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 1px solid var(--sk-border);
  border-radius: 4px;
  background: var(--sk-card);
  color: var(--sk-text-secondary);
  cursor: pointer;
}
.fold-btn:hover {
  color: var(--sk-primary);
  background: var(--sk-primary-light);
}
.tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  width: 100%;
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  color: var(--sk-text-primary);
}
.tree-node .label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.comment {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--sk-text-tertiary);
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
  line-height: 1.5;
  color: var(--sk-text-tertiary);
  padding: 8px 4px;
}
.empty-link {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--sk-primary);
  font-size: 12px;
  cursor: pointer;
}
.head-actions :deep(.el-button) {
  padding: 0 2px;
  font-size: 12px;
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
  min-width: 168px;
  width: max-content;
  margin: 0;
  padding: 4px 0;
  list-style: none;
  background: var(--sk-bg-elevated);
  border: 1px solid var(--sk-border);
  border-radius: 6px;
  box-shadow: 0 6px 16px var(--sk-shadow-pop);
  z-index: 4001;
}
.ctx-menu li {
  height: 32px;
  margin: 0;
  padding: 0 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--sk-text-primary);
  cursor: pointer;
  white-space: nowrap;
  box-sizing: border-box;
}
.ctx-menu li .mi {
  flex-shrink: 0;
  color: var(--sk-text-secondary);
}
.ctx-menu li:hover:not(.disabled):not(.sep) {
  background: var(--sk-hover-bg);
}
.ctx-menu li.danger,
.ctx-menu li.danger .mi {
  color: var(--sk-danger);
}
.ctx-menu li.warn,
.ctx-menu li.warn .mi {
  color: var(--sk-warning);
}
.ctx-menu li.disabled {
  color: var(--sk-text-tertiary);
  cursor: not-allowed;
}
.ctx-menu li.disabled .mi {
  color: var(--sk-text-tertiary);
}
.ctx-menu li.sep {
  height: 1px;
  margin: 6px 0;
  padding: 0;
  background: var(--sk-border-strong);
  cursor: default;
  pointer-events: none;
}
</style>
