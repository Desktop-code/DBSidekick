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
        :label="tab.title"
        :closable="tab.closable !== false"
      >
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
          <el-button @click="openHelp">查看文档</el-button>
        </div>
        <div class="divider">或者试试这样问</div>
        <div class="examples">
          <div v-for="q in examples" :key="q" class="example" @click="useExample(q)">"{{ q }}"</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DataLine } from '@element-plus/icons-vue'
import { useTabsStore } from '../stores/tabs'
import { useAppStore } from '../stores/app'
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

const emptyTitle = computed(() =>
  appStore.currentDatasource ? `当前：${appStore.currentDatasourceName}` : '还没有选择数据源'
)

const emptyHint = computed(() =>
  appStore.currentDatasource
    ? '用自然语言查数，或从左侧继续上次的会话'
    : '从左侧选一个数据源，开始用自然语言查数'
)

function openNewConnection() {
  tabsStore.openConnectionManage({ openCreate: true })
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
  if (!id) {
    ElMessage.warning('请先在左侧选择数据源')
    return
  }
  appStore.setCurrentSessionId(null)
  tabsStore.openAiChat({
    datasourceId: id,
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
  margin: 16px 0 12px;
  font-size: 12px;
  color: var(--sk-text-tertiary);
}
.examples {
  display: flex;
  flex-direction: column;
  gap: 4px;
  text-align: left;
}
.example {
  padding: 8px 12px;
  border: 1px solid var(--sk-border);
  border-radius: 6px;
  background: var(--sk-card);
  cursor: pointer;
  font-size: 13px;
  color: var(--sk-text-primary);
  transition: border-color 0.15s, background 0.15s;
}
.example:hover {
  border-color: var(--sk-primary);
  background: var(--sk-primary-light);
}
</style>
