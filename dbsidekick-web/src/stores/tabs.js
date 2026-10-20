import { defineStore } from 'pinia'

let seq = 1
function nextId() {
  return `tab-${Date.now()}-${seq++}`
}

/** @typedef {'aiChat'|'queryEditor'|'connectionManage'|'relationManage'} TabType */

export const useTabsStore = defineStore('tabs', {
  state: () => ({
    tabs: [],
    activeId: null
  }),
  getters: {
    activeTab(state) {
      return state.tabs.find((t) => t.id === state.activeId) || null
    }
  },
  actions: {
    /** 关闭遗留的配置类 Tab（已改为弹窗） */
    closeSettingsTabs() {
      const ids = this.tabs.filter((t) => t.type === 'settings').map((t) => t.id)
      ids.forEach((id) => this.closeTab(id))
    },
    /**
     * @param {TabType} type
     * @param {object} [props]
     * @param {{ title?: string, closable?: boolean }} [meta]
     */
    addTab(type, props = {}, meta = {}) {
      const id = nextId()
      const defaults = {
        aiChat: 'AI会话: 新会话',
        queryEditor: '查询编辑器',
        connectionManage: '连接管理',
        relationManage: '表关系管理'
      }
      const tab = {
        id,
        type,
        title: meta.title || defaults[type] || '未命名',
        closable: meta.closable !== false,
        props: { ...props }
      }
      this.tabs.push(tab)
      this.activeId = id
      return tab
    },
    openAiChat({ datasourceId, dbName, sessionId, title, draft, autoSend } = {}) {
      return this.addTab(
        'aiChat',
        {
          datasourceId: datasourceId || null,
          dbName: dbName || '',
          sessionId: sessionId || null,
          draft: draft || '',
          autoSend: !!autoSend
        },
        { title: title || 'AI会话: 新会话' }
      )
    },
    openQueryEditor({ initialSql, initialDatasourceId, scriptId, title } = {}) {
      return this.addTab(
        'queryEditor',
        {
          initialSql: initialSql || '',
          initialDatasourceId: initialDatasourceId || null,
          scriptId: scriptId || null
        },
        { title: title || '查询编辑器' }
      )
    },
    openRelationManage({ datasourceId, dbName } = {}) {
      const existing = this.tabs.find((t) => t.type === 'relationManage')
      if (existing) {
        this.activeId = existing.id
        this.updateTab(existing.id, {
          props: {
            ...existing.props,
            datasourceId: datasourceId || existing.props?.datasourceId || null,
            dbName: dbName || existing.props?.dbName || null,
            reloadToken: Date.now()
          }
        })
        return existing
      }
      return this.addTab(
        'relationManage',
        {
          datasourceId: datasourceId || null,
          dbName: dbName || null,
          reloadToken: Date.now()
        },
        { title: '表关系管理' }
      )
    },
    openConnectionManage({ openCreate } = {}) {
      const existing = this.tabs.find((t) => t.type === 'connectionManage')
      if (existing) {
        this.activeId = existing.id
        if (openCreate) {
          this.updateTab(existing.id, {
            props: { ...existing.props, openCreateToken: Date.now() }
          })
        }
        return existing
      }
      return this.addTab(
        'connectionManage',
        { openCreateToken: openCreate ? Date.now() : 0 },
        { title: '连接管理' }
      )
    },
    closeTab(id) {
      const idx = this.tabs.findIndex((t) => t.id === id)
      if (idx < 0) return
      this.tabs.splice(idx, 1)
      if (this.activeId === id) {
        const next = this.tabs[idx] || this.tabs[idx - 1] || null
        this.activeId = next ? next.id : null
      }
    },
    setActive(id) {
      this.activeId = id
    },
    updateTab(id, patch) {
      const tab = this.tabs.find((t) => t.id === id)
      if (!tab) return
      if (patch.props) {
        tab.props = { ...tab.props, ...patch.props }
        const { props, ...rest } = patch
        Object.assign(tab, rest)
      } else {
        Object.assign(tab, patch)
      }
    }
  }
})
