<template>
  <div class="tab-container">
    <el-tabs
      v-if="tabsStore.tabs.length"
      v-model="activeId"
      type="card"
      class="tabs"
      @tab-remove="onRemove"
    >
      <el-tab-pane
        v-for="tab in tabsStore.tabs"
        :key="tab.id"
        :name="tab.id"
        :closable="tab.closable !== false"
      >
        <template #label>
          <span
            class="tab-label"
            :style="{ '--tab-icon': tabVisual(tab).color, '--tab-icon-active': tabVisual(tab).active }"
          >
            <el-tooltip :content="tabVisual(tab).tip" placement="bottom" :show-after="300">
              <el-icon class="tab-icon" :size="14">
                <component :is="tabVisual(tab).icon" />
              </el-icon>
            </el-tooltip>
            <span class="tab-name" :title="tabName(tab)">{{ tabName(tab) }}</span>
          </span>
        </template>
        <div class="pane">
          <AiChatTab v-if="tab.type === 'aiChat'" :tab="tab" />
          <QueryEditorTab
            v-else-if="tab.type === 'queryEditor'"
            :tab-id="tab.id"
            :initial-sql="tab.props?.initialSql || ''"
            :initial-datasource-id="tab.props?.initialDatasourceId || ''"
          />
          <SettingsTab v-else-if="tab.type === 'settings'" :tab="tab" />
          <ConnectionManageTab v-else-if="tab.type === 'connectionManage'" :tab="tab" />
          <RelationManageTab v-else-if="tab.type === 'relationManage'" :tab="tab" />
          <el-empty v-else description="未知标签页类型" />
        </div>
      </el-tab-pane>
    </el-tabs>
    <div v-else class="empty">
      <div class="empty-state">
        <el-icon class="big-icon"><DataLine /></el-icon>
        <h3>{{ emptyTitle }}</h3>
        <p>{{ emptyHint }}</p>
        <div class="actions">
          <el-button type="primary" @click="openNewConnection">+ 新建连接</el-button>
          <el-button plain @click="openNewAiSession">+ 新建 AI 会话</el-button>
          <el-button plain @click="openHelp">查看文档</el-button>
        </div>
        <div class="divider"><span class="bulb" aria-hidden="true">💡</span>或者试试这样问</div>
        <div class="examples">
          <div v-for="q in examples" :key="q" class="example" :title="q" @click="useExample(q)">{{ q }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  ChatLineSquare,
  Coin,
  CollectionTag,
  Connection,
  DataLine,
  Document,
  EditPen,
  Setting
} from '@element-plus/icons-vue'
import { useTabsStore } from '../stores/tabs'
import { useAppStore } from '../stores/app'
import { useThemeStore } from '../stores/theme'
import AiChatTab from './tabs/AiChatTab.vue'
import QueryEditorTab from './tabs/QueryEditorTab.vue'
import SettingsTab from './tabs/SettingsTab.vue'
import ConnectionManageTab from './tabs/ConnectionManageTab.vue'
import RelationManageTab from './tabs/RelationManageTab.vue'
import { frequentQuestions } from '../api/session'

const FALLBACK_EXAMPLES = [
  '查最近的订单',
  '每个部门有多少人',
  '上个月销售额 Top10',
  '统计每个用户的订单数'
]

const tabsStore = useTabsStore()
const appStore = useAppStore()
const themeStore = useThemeStore()

/** 标签头只按 type 取图标和颜色，不改标题原文。 */
const TAB_VISUAL = {
  aiChat: { icon: ChatDotRound, color: '#3B82F6', active: '#1D4ED8', dark: '#60A5FA', darkActive: '#93C5FD', tip: 'AI 会话' },
  queryEditor: { icon: EditPen, color: '#10B981', active: '#047857', dark: '#34D399', darkActive: '#6EE7B7', tip: '查询编辑器' },
  relationManage: { icon: Connection, color: '#8B5CF6', active: '#6D28D9', dark: '#A78BFA', darkActive: '#C4B5FD', tip: '表关系' },
  connectionManage: { icon: Coin, color: '#64748B', active: '#334155', dark: '#94A3B8', darkActive: '#E2E8F0', tip: '连接管理' },
  settings: { icon: Setting, color: '#475569', active: '#1E293B', dark: '#94A3B8', darkActive: '#F1F5F9', tip: '模型配置' },
  script: { icon: Document, color: '#F59E0B', active: '#B45309', dark: '#FBBF24', darkActive: '#FDE68A', tip: 'SQL 清单' },
  glossary: { icon: CollectionTag, color: '#06B6D4', active: '#0E7490', dark: '#22D3EE', darkActive: '#67E8F9', tip: '业务术语' },
  prompt: { icon: ChatLineSquare, color: '#EC4899', active: '#BE185D', dark: '#F472B6', darkActive: '#F9A8D4', tip: 'Prompt 配置' }
}
const FALLBACK_VISUAL = { icon: Document, color: '#94A3B8', active: '#64748B', dark: '#94A3B8', darkActive: '#E2E8F0', tip: '标签页' }

function tabVisual(tab) {
  const visual = TAB_VISUAL[tab?.type] || FALLBACK_VISUAL
  if (themeStore.resolvedTheme !== 'dark') return visual
  return { ...visual, color: visual.dark, active: visual.darkActive }
}

function tabName(tab) {
  const raw = String(tab?.title || '').trim()
  if (tab?.type === 'aiChat') {
    return raw.replace(/^AI会话:\s*/, '').trim() || '新会话'
  }
  if (tab?.type === 'queryEditor') {
    const name = raw.replace(/^查询:\s*/, '').trim()
    if (!name || name === '查询编辑器') return '未命名'
    return name
  }
  return raw || '未命名'
}

const activeId = computed({
  get: () => tabsStore.activeId,
  set: (v) => tabsStore.setActive(v)
})

function onRemove(name) {
  tabsStore.closeTab(name)
}

const examples = ref([...FALLBACK_EXAMPLES])

async function loadExamples() {
  const id = appStore.currentDatasourceId
  if (!id) {
    examples.value = [...FALLBACK_EXAMPLES]
    return
  }
  try {
    const res = await frequentQuestions(id, 5)
    const list = Array.isArray(res?.questions) ? res.questions.map((q) => String(q || '').trim()).filter(Boolean) : []
    examples.value = list.length ? list : [...FALLBACK_EXAMPLES]
  } catch {
    examples.value = [...FALLBACK_EXAMPLES]
  }
}

const emptyTitle = computed(() => {
  if (appStore.currentDatabase) return `当前库：${appStore.currentDatabase}`
  if (appStore.currentDatasource) return `当前：${appStore.currentDatasourceName}`
  return '还没有选择数据源'
})

const emptyHint = computed(() => {
  if (appStore.currentDatabase) {
    return `数据源：${appStore.currentDatasourceName} · 用自然语言查数，或从左侧继续上次的会话`
  }
  if (appStore.currentDatasource) return '从左侧选一个库开始，或从左侧继续上次的会话'
  return '从左侧选一个数据源，开始用自然语言查数'
})

function openNewConnection() {
  tabsStore.openConnectionManage({ openCreate: true })
}

function openNewAiSession() {
  appStore.openSessionDialog({
    datasourceId: appStore.currentDatasourceId || '',
    dbName: appStore.currentDatabase || ''
  })
}

function openHelp() {
  ElMessageBox.alert(
    '从左侧选择数据源后，可以在中间用自然语言提问，也可以在查询编辑器里直接写 SQL。右侧清单用来收藏常用脚本。',
    '使用说明',
    { confirmButtonText: '知道了' }
  )
}

function useExample(q) {
  const id = appStore.currentDatasourceId
  const db = appStore.currentDatabase
  if (!id || !db) {
    appStore.openSessionDialog({ datasourceId: id || '', dbName: db || '' })
    return
  }
  appStore.setCurrentSessionId(null)
  tabsStore.openAiChat({
    datasourceId: id,
    dbName: db,
    title: 'AI会话: 新会话',
    draft: q,
    autoSend: true
  })
}

onMounted(() => {
  if (!appStore.datasources.length) {
    appStore.loadDatasources().catch(() => {})
  }
  loadExamples()
})

watch(() => appStore.currentDatasourceId, () => {
  loadExamples()
})
</script>

<style scoped>
.tab-container {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.tabs {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  padding: 0;
}
.tabs :deep(.el-tab-pane) {
  height: 100%;
}
.pane {
  height: 100%;
  min-height: 0;
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  max-width: 200px;
  vertical-align: middle;
}
.tab-icon {
  flex: 0 0 auto;
  color: var(--tab-icon);
  opacity: 0.72;
  filter: saturate(0.55);
}
.tabs :deep(.el-tabs__item.is-active) .tab-icon {
  color: var(--tab-icon-active);
  opacity: 1;
  filter: none;
}
.tab-name {
  min-width: 0;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  line-height: 1.2;
}
.empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--sk-bg);
  padding: 24px;
  box-sizing: border-box;
}
.empty-state {
  width: min(440px, 100%);
  text-align: center;
}
.big-icon {
  font-size: 36px;
  color: var(--sk-primary);
}
.empty-state h3 {
  margin: 12px 0 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--sk-text-primary);
}
.empty-state p {
  margin: 8px 0 16px;
  font-size: 13px;
  color: var(--sk-text-secondary);
}
.actions {
  display: flex;
  justify-content: center;
  gap: 8px;
}
.divider {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  margin: 16px 0 8px;
  font-size: 12px;
  color: var(--sk-text-tertiary);
}
.bulb {
  font-size: 14px;
  line-height: 1;
}
.examples {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  text-align: left;
}
.example {
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--sk-border);
  border-radius: 6px;
  background: var(--sk-bg-elevated);
  cursor: pointer;
  font-size: 12px;
  line-height: 32px;
  color: var(--sk-text-strong);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  box-sizing: border-box;
  transition: border-color 0.15s, background 0.15s;
}
.example:hover {
  border-color: var(--sk-primary);
  background: var(--sk-primary-light);
}
</style>
