<template>
  <div class="main-view">
    <AppMenuBar />
    <div class="body" :class="{ resizing }">
      <aside
        class="side left"
        :class="{ collapsed: !leftOpen }"
        :style="leftStyle"
      >
        <div v-show="leftOpen" class="side-body sk-scroll">
          <DataSourceTree @collapse="leftOpen = false" />
        </div>
        <div v-show="!leftOpen" class="side-rail">
          <el-tooltip v-for="item in railItems" :key="item.id" :content="item.label" placement="right">
            <button
              type="button"
              class="rail-icon"
              :class="{ active: appStore.sidebarFocus === item.id }"
              :aria-label="item.label"
              @click="onRail(item.id)"
            >
              <el-icon :size="18"><component :is="item.icon" /></el-icon>
            </button>
          </el-tooltip>
        </div>
      </aside>

      <PanelResizer
        v-if="leftOpen"
        direction="left"
        @start="onLeftResizeStart"
        @move="onLeftResizeMove"
        @end="onLeftResizeEnd"
      />

      <main class="center sk-scroll">
        <TabContainer />
      </main>

      <PanelResizer
        direction="right"
        @start="onRightResizeStart"
        @move="onRightResizeMove"
        @end="onRightResizeEnd"
      />

      <aside
        class="side right"
        :class="{ collapsed: !rightOpen }"
        :style="rightStyle"
      >
        <div v-show="rightOpen" class="side-body sk-scroll">
          <SqlListPanel @collapse="rightOpen = false" />
        </div>
        <div v-show="!rightOpen" class="side-rail">
          <el-tooltip content="SQL 清单" placement="left">
            <button type="button" class="rail-icon" aria-label="SQL 清单" @click="rightOpen = true">
              <el-icon :size="18"><Document /></el-icon>
            </button>
          </el-tooltip>
        </div>
      </aside>
    </div>
    <AppStatusBar />
    <SetupWizard />
    <NewSessionDialog />
    <SettingsDialog />
    <SchemaInitModal />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ChatDotRound, Coin, Document, Setting } from '@element-plus/icons-vue'
import AppMenuBar from '../components/AppMenuBar.vue'
import AppStatusBar from '../components/AppStatusBar.vue'
import DataSourceTree from '../components/DataSourceTree.vue'
import TabContainer from '../components/TabContainer.vue'
import SqlListPanel from '../components/SqlListPanel.vue'
import SetupWizard from '../components/SetupWizard.vue'
import NewSessionDialog from '../components/NewSessionDialog.vue'
import SettingsDialog from '../components/SettingsDialog.vue'
import SchemaInitModal from '../components/SchemaInitModal.vue'
import PanelResizer from '../components/PanelResizer.vue'
import { useAppStore } from '../stores/app'
import { useTabsStore } from '../stores/tabs'

const COLLAPSED = 48

const appStore = useAppStore()
const tabsStore = useTabsStore()
const railItems = [
  { id: 'datasource', label: '数据源', icon: Coin },
  { id: 'history', label: '历史会话', icon: ChatDotRound },
  { id: 'sql', label: 'SQL清单', icon: Document },
  { id: 'settings', label: '设置', icon: Setting }
]

const resizing = ref(false)
let dragStartLeft = 240
let dragStartRight = 240

function onRail(id) {
  if (id === 'settings') {
    tabsStore.closeSettingsTabs()
    appStore.openSettingsDialog('llm')
    return
  }
  appStore.focusSidebar(id)
}

const leftOpen = computed({
  get: () => appStore.leftPanelOpen,
  set: (open) => appStore.setLeftPanelOpen(open)
})
const rightOpen = computed({
  get: () => appStore.rightPanelOpen,
  set: (open) => appStore.setRightPanelOpen(open)
})

const leftStyle = computed(() => {
  const w = leftOpen.value ? appStore.leftPanelWidth : COLLAPSED
  return { width: `${w}px`, flexBasis: `${w}px` }
})

const rightStyle = computed(() => {
  const w = rightOpen.value ? Math.max(appStore.rightPanelWidth, COLLAPSED) : COLLAPSED
  return { width: `${w}px`, flexBasis: `${w}px` }
})

function onLeftResizeStart() {
  resizing.value = true
  dragStartLeft = appStore.leftPanelWidth
}

function onLeftResizeMove(delta) {
  appStore.previewLeftPanelWidth(dragStartLeft + delta)
}

function onLeftResizeEnd() {
  resizing.value = false
  appStore.commitLeftPanelWidth()
}

function onRightResizeStart() {
  resizing.value = true
  dragStartRight = rightOpen.value ? appStore.rightPanelWidth : COLLAPSED
}

function onRightResizeMove(delta) {
  const next = dragStartRight + delta
  if (next <= appStore.rightCollapseSnap) {
    appStore.previewRightPanelWidth(COLLAPSED)
    if (rightOpen.value) {
      appStore.rightPanelOpen = false
    }
    return
  }
  if (!rightOpen.value) {
    appStore.rightPanelOpen = true
  }
  appStore.previewRightPanelWidth(next)
}

function onRightResizeEnd() {
  resizing.value = false
  appStore.commitRightPanelWidth()
}

watch(
  () => appStore.scriptFocusToken,
  (token) => {
    if (token) appStore.setRightPanelOpen(true)
  }
)
</script>

<style scoped>
.main-view {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--sk-bg);
}
.body {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow-x: auto;
}
.side {
  position: relative;
  flex: 0 0 auto;
  min-height: 0;
  overflow: hidden;
  background: var(--sk-card);
  border-right: 1px solid var(--sk-border);
  transition: width 0.18s ease, flex-basis 0.18s ease, background-color 200ms ease, border-color 200ms ease;
}
.body.resizing .side {
  transition: none;
}
.side.right {
  border-right: none;
  border-left: 1px solid var(--sk-border);
}
.side.left {
  background: var(--sk-bg-sidebar);
}
.side.collapsed {
  background: var(--sk-bg-sidebar);
}
.side-rail {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding-top: 12px;
  box-sizing: border-box;
}
.side.left .rail-icon {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--sk-text-secondary);
}
.side.left .rail-icon:hover,
.side.left .rail-icon.active {
  background: var(--sk-active-bg);
  color: var(--sk-primary);
}
.side.right .rail-icon {
  width: 32px;
  height: 32px;
  border: 1px solid var(--sk-border);
  border-radius: 4px;
  background: var(--sk-card);
  color: var(--sk-text-secondary);
}
.side.right .rail-icon:hover {
  color: var(--sk-primary);
  background: var(--sk-primary-light);
}
.rail-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  cursor: pointer;
}
.side-body {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.center {
  flex: 1 1 auto;
  min-width: 500px;
  min-height: 0;
  overflow: hidden;
}
</style>
