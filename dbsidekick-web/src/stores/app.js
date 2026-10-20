import { defineStore } from 'pinia'
import { listDatasources, testDatasource } from '../api/datasource'
import { text2sqlHealth, milvusHealth, embeddingHealth } from '../api/text2sql'

let milvusRecheckTimer = null

/** 左侧面板：无 localStorage 时默认展开 */
function readLeftPanelOpen() {
  try {
    const v = localStorage.getItem('dbsidekick.leftOpen')
    if (v === null) return true
    return v !== '0'
  } catch {
    return true
  }
}

/** 右侧 SQL 清单：无 localStorage 时默认收缩；有历史值则沿用 */
function readRightPanelOpen() {
  try {
    const v = localStorage.getItem('dbsidekick.rightOpen')
    if (v === null) return false
    return v !== '0'
  } catch {
    return false
  }
}

const LEFT_WIDTH_MIN = 180
const LEFT_WIDTH_MAX = 450
const LEFT_WIDTH_DEFAULT = 240
const RIGHT_WIDTH_MIN = 48
const RIGHT_WIDTH_MAX = 500
const RIGHT_WIDTH_DEFAULT = 240
const RIGHT_COLLAPSE_SNAP = 80

function clamp(n, min, max) {
  return Math.min(max, Math.max(min, n))
}

function readLeftPanelWidth() {
  try {
    const raw = localStorage.getItem('dbsidekick.leftWidth')
    if (raw == null) return LEFT_WIDTH_DEFAULT
    const n = Number(raw)
    if (!Number.isFinite(n)) return LEFT_WIDTH_DEFAULT
    return clamp(Math.round(n), LEFT_WIDTH_MIN, LEFT_WIDTH_MAX)
  } catch {
    return LEFT_WIDTH_DEFAULT
  }
}

function readRightPanelWidth() {
  try {
    const raw = localStorage.getItem('dbsidekick.rightWidth')
    if (raw == null) return RIGHT_WIDTH_DEFAULT
    const n = Number(raw)
    if (!Number.isFinite(n)) return RIGHT_WIDTH_DEFAULT
    return clamp(Math.round(n), RIGHT_WIDTH_MIN, RIGHT_WIDTH_MAX)
  } catch {
    return RIGHT_WIDTH_DEFAULT
  }
}

function readRailSection() {
  try {
    const value = localStorage.getItem('dbsidekick.railSection')
    if (value === 'history' || value === 'sql' || value === 'settings' || value === 'datasource') return value
  } catch {
    // 读不到时回到数据源
  }
  return 'datasource'
}

export const useAppStore = defineStore('app', {
  state: () => ({
    datasources: [],
    currentDatasourceId: null,
    /** @type {Record<string, 'up'|'down'|'unknown'>} */
    datasourceStatus: {},
    /** @type {Record<string, boolean>} */
    datasourceIndexed: {},
    /** @type {{ llm: 'up'|'down'|'unknown', embedding: 'up'|'down'|'unknown', milvus: 'up'|'down'|'unknown' }} */
    health: {
      llm: 'unknown',
      embedding: 'unknown',
      milvus: 'unknown'
    },
    healthDetail: {
      llm: 'LLM: 未检测',
      embedding: 'Embedding: 未检测',
      milvus: 'Milvus: 未检测'
    },
    lastRowCount: 0,
    lastElapsedMs: 0,
    statusText: '就绪',
    scriptRefreshTrigger: 0,
    scriptFocusToken: 0,
    currentSessionId: null,
    sessionRefreshTrigger: 0,
    /** 当前查询范围。旧连接在选中数据源时默认带回保存的库名。 */
    currentDatabase: null,
    sessionDialog: {
      open: false,
      datasourceId: '',
      dbName: '',
      token: 0
    },
    leftPanelOpen: readLeftPanelOpen(),
    rightPanelOpen: readRightPanelOpen(),
    /** 展开态宽度（折叠时 UI 仍用 48px） */
    leftPanelWidth: readLeftPanelWidth(),
    rightPanelWidth: readRightPanelWidth(),
    /** 折叠栏当前高亮：datasource / history / sql / settings */
    sidebarFocus: readRailSection(),
    sidebarFocusToken: 0,
    /** 设置弹窗（不再占用中间 Tab） */
    settingsDialog: {
      open: false,
      section: 'llm'
    }
  }),
  getters: {
    leftWidthMin: () => LEFT_WIDTH_MIN,
    leftWidthMax: () => LEFT_WIDTH_MAX,
    rightWidthMin: () => RIGHT_WIDTH_MIN,
    rightWidthMax: () => RIGHT_WIDTH_MAX,
    rightCollapseSnap: () => RIGHT_COLLAPSE_SNAP,
    currentDatasource(state) {
      return state.datasources.find((d) => d.id === state.currentDatasourceId) || null
    },
    currentDatasourceName(state) {
      const ds = state.datasources.find((d) => d.id === state.currentDatasourceId)
      return ds?.name || '未选择'
    },
    currentDialect(state) {
      const ds = state.datasources.find((d) => d.id === state.currentDatasourceId)
      if (!ds) return '-'
      const t = (ds.type || '').toString().toUpperCase()
      if (t.includes('POSTGRES')) return 'PostgreSQL'
      return 'MySQL'
    }
  },
  actions: {
    async loadDatasources() {
      const list = await listDatasources()
      this.datasources = Array.isArray(list) ? list : []
    },
    setCurrentDatasource(id) {
      if (this.currentDatasourceId !== id) {
        this.currentSessionId = null
        this.currentDatabase = null
        const ds = (this.datasources || []).find((d) => d.id === id)
        if (ds?.database) {
          this.currentDatabase = ds.database
        }
      }
      this.currentDatasourceId = id
    },
    setCurrentDatabase(name) {
      const text = name == null ? '' : String(name).trim()
      this.currentDatabase = text || null
    },
    setCurrentSessionId(id) {
      this.currentSessionId = id || null
    },
    openSessionDialog({ datasourceId, dbName } = {}) {
      this.sessionDialog = {
        open: true,
        datasourceId: datasourceId || '',
        dbName: dbName || '',
        token: Date.now()
      }
    },
    closeSessionDialog() {
      this.sessionDialog = { ...this.sessionDialog, open: false }
    },
    bumpSessionRefresh() {
      this.sessionRefreshTrigger += 1
    },
    setDatasourceStatus(id, status) {
      this.datasourceStatus = { ...this.datasourceStatus, [id]: status }
    },
    setDatasourceIndexed(id, indexed) {
      this.datasourceIndexed = { ...this.datasourceIndexed, [id]: !!indexed }
    },
    async refreshDatasourceStatuses() {
      const list = this.datasources || []
      for (const ds of list) {
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
          this.setDatasourceStatus(ds.id, res?.success ? 'up' : 'down')
        } catch {
          this.setDatasourceStatus(ds.id, 'down')
        }
      }
    },
    setQueryStats({ rowCount = 0, elapsedMs = 0 } = {}) {
      this.lastRowCount = rowCount
      this.lastElapsedMs = elapsedMs
    },
    bumpScriptRefresh() {
      this.scriptRefreshTrigger += 1
    },
    focusFirstScript() {
      this.scriptFocusToken += 1
    },
    setLeftPanelOpen(open) {
      this.leftPanelOpen = !!open
      localStorage.setItem('dbsidekick.leftOpen', this.leftPanelOpen ? '1' : '0')
    },
    setRightPanelOpen(open) {
      this.rightPanelOpen = !!open
      localStorage.setItem('dbsidekick.rightOpen', this.rightPanelOpen ? '1' : '0')
      if (this.rightPanelOpen && this.rightPanelWidth < RIGHT_COLLAPSE_SNAP) {
        this.setRightPanelWidth(RIGHT_WIDTH_DEFAULT)
      }
    },
    setLeftPanelWidth(width) {
      const w = clamp(Math.round(Number(width) || LEFT_WIDTH_DEFAULT), LEFT_WIDTH_MIN, LEFT_WIDTH_MAX)
      this.leftPanelWidth = w
      try {
        localStorage.setItem('dbsidekick.leftWidth', String(w))
      } catch {
        // ignore
      }
    },
    setRightPanelWidth(width) {
      const w = clamp(Math.round(Number(width) || RIGHT_WIDTH_DEFAULT), RIGHT_WIDTH_MIN, RIGHT_WIDTH_MAX)
      this.rightPanelWidth = w
      try {
        localStorage.setItem('dbsidekick.rightWidth', String(w))
      } catch {
        // ignore
      }
    },
    /** 拖拽过程中预览宽度（不写 localStorage）；mouseup 再持久化 */
    previewLeftPanelWidth(width) {
      this.leftPanelWidth = clamp(Math.round(Number(width) || LEFT_WIDTH_DEFAULT), LEFT_WIDTH_MIN, LEFT_WIDTH_MAX)
    },
    previewRightPanelWidth(width) {
      this.rightPanelWidth = clamp(Math.round(Number(width) || RIGHT_WIDTH_DEFAULT), RIGHT_WIDTH_MIN, RIGHT_WIDTH_MAX)
    },
    commitLeftPanelWidth() {
      this.setLeftPanelWidth(this.leftPanelWidth)
    },
    commitRightPanelWidth() {
      if (this.rightPanelWidth <= RIGHT_COLLAPSE_SNAP) {
        this.setRightPanelOpen(false)
        // 折叠后保留默认展开宽度，下次点开不至于只有 48
        this.rightPanelWidth = RIGHT_WIDTH_DEFAULT
        try {
          localStorage.setItem('dbsidekick.rightWidth', String(RIGHT_WIDTH_DEFAULT))
        } catch {
          // ignore
        }
        return
      }
      this.setRightPanelWidth(this.rightPanelWidth)
      if (!this.rightPanelOpen) {
        this.setRightPanelOpen(true)
      }
    },
    openSettingsDialog(section = 'llm') {
      const allowed = ['llm', 'embedding', 'milvus', 'sql', 'storage']
      const sec = allowed.includes(section) ? section : 'llm'
      this.settingsDialog = { open: true, section: sec }
    },
    setSettingsSection(section) {
      const allowed = ['llm', 'embedding', 'milvus', 'sql', 'storage']
      if (!allowed.includes(section)) return
      this.settingsDialog = { ...this.settingsDialog, section }
    },
    closeSettingsDialog() {
      this.settingsDialog = { ...this.settingsDialog, open: false }
    },
    focusSidebar(section) {
      const allowed = section === 'history' || section === 'sql' || section === 'settings' || section === 'datasource'
        ? section
        : 'datasource'
      this.sidebarFocus = allowed
      this.sidebarFocusToken += 1
      try {
        localStorage.setItem('dbsidekick.railSection', allowed)
      } catch {
        // 忽略写入失败
      }
      if (allowed === 'datasource' || allowed === 'history') {
        this.setLeftPanelOpen(true)
      } else if (allowed === 'sql') {
        this.setRightPanelOpen(true)
      }
    },
    scheduleMilvusRecheck() {
      if (milvusRecheckTimer) return
      milvusRecheckTimer = setTimeout(() => {
        milvusRecheckTimer = null
        this.refreshHealth()
      }, 1500)
    },
    async refreshHealth() {
      try {
        const [llm, milvus, emb] = await Promise.all([
          text2sqlHealth().catch(() => ({})),
          milvusHealth().catch(() => ({})),
          embeddingHealth().catch(() => ({}))
        ])

        if (llm.llmConfigured) {
          this.health.llm = 'up'
          this.healthDetail.llm = `LLM: UP · model=${llm.model || '-'}`
        } else {
          this.health.llm = 'unknown'
          this.healthDetail.llm = 'LLM: 未配置 API Key'
        }

        const provider = (emb.provider || 'onnx').toLowerCase()
        if (emb.loaded) {
          this.health.embedding = 'up'
          this.healthDetail.embedding = `Embedding: UP · provider=${provider}`
        } else if (emb.error) {
          this.health.embedding = 'down'
          this.healthDetail.embedding = `Embedding: DOWN · ${emb.error}`
        } else {
          this.health.embedding = 'unknown'
          this.healthDetail.embedding = 'Embedding: 未加载'
        }

        if (milvus.checked === false) {
          this.health.milvus = 'unknown'
          this.healthDetail.milvus = 'Milvus: 检测中'
          this.scheduleMilvusRecheck()
        } else if (milvus.reachable) {
          this.health.milvus = 'up'
          this.healthDetail.milvus = `Milvus: UP · collection=${milvus.collection || '-'} · rowCount=${milvus.rowCount ?? 0}`
        } else {
          this.health.milvus = 'down'
          this.healthDetail.milvus = `Milvus: DOWN${milvus.error ? ' · ' + milvus.error : ''}`
        }
      } catch {
        // ignore
      }
    }
  }
})
