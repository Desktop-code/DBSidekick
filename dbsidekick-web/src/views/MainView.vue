<template>
  <div class="main-view">
    <AppMenuBar />
    <div class="body">
      <aside class="side left" :class="{ collapsed: !leftOpen }">
        <div v-show="leftOpen" class="side-body">
          <DataSourceTree />
        </div>
        <button
          v-if="leftOpen"
          type="button"
          class="fold"
          title="收起数据源"
          @click="leftOpen = false"
        >
          ‹
        </button>
        <button
          v-else
          type="button"
          class="rail"
          title="展开数据源"
          @click="leftOpen = true"
        >
          <span class="rail-mark">›</span>
          <span class="rail-text">数据源</span>
        </button>
      </aside>
      <main class="center">
        <TabContainer />
      </main>
      <aside class="side right" :class="{ collapsed: !rightOpen }">
        <div v-show="rightOpen" class="side-body">
          <SqlListPanel @collapse="rightOpen = false" />
        </div>
        <div v-show="!rightOpen" class="right-rail">
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
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { Document } from '@element-plus/icons-vue'
import AppMenuBar from '../components/AppMenuBar.vue'
import AppStatusBar from '../components/AppStatusBar.vue'
import DataSourceTree from '../components/DataSourceTree.vue'
import TabContainer from '../components/TabContainer.vue'
import SqlListPanel from '../components/SqlListPanel.vue'
import SetupWizard from '../components/SetupWizard.vue'
import NewSessionDialog from '../components/NewSessionDialog.vue'
import { useAppStore } from '../stores/app'

const LEFT_KEY = 'dbsidekick.leftOpen'
const RIGHT_KEY = 'dbsidekick.rightOpen'

const appStore = useAppStore()
const leftOpen = ref(localStorage.getItem(LEFT_KEY) !== '0')
const rightOpen = ref(localStorage.getItem(RIGHT_KEY) !== '0')

watch(leftOpen, (open) => localStorage.setItem(LEFT_KEY, open ? '1' : '0'))
watch(rightOpen, (open) => localStorage.setItem(RIGHT_KEY, open ? '1' : '0'))
watch(
  () => appStore.scriptFocusToken,
  (token) => {
    if (token) rightOpen.value = true
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
}
.side {
  position: relative;
  flex: 0 0 240px;
  width: 240px;
  min-height: 0;
  overflow: hidden;
  background: var(--sk-card);
  border-right: 1px solid var(--sk-border);
  transition: width 0.18s ease, flex-basis 0.18s ease;
}
.side.right {
  border-right: none;
  border-left: 1px solid var(--sk-border);
  transition: width 200ms ease, flex-basis 200ms ease;
}
.side.collapsed {
  flex-basis: 36px;
  width: 36px;
  background: var(--sk-bg);
}
.side.right.collapsed {
  flex-basis: 48px;
  width: 48px;
}
.right-rail {
  height: 100%;
  display: flex;
  justify-content: center;
  padding-top: 12px;
}
.rail-icon {
  width: 32px;
  height: 32px;
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
.rail-icon:hover {
  color: var(--sk-primary);
  background: var(--sk-primary-light);
}
.side-body {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.center {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.fold,
.rail {
  border: 1px solid var(--sk-border);
  background: var(--sk-card);
  color: var(--sk-text-secondary);
  cursor: pointer;
  padding: 0;
}
.fold {
  position: absolute;
  top: 50%;
  z-index: 2;
  width: 18px;
  height: 36px;
  border-radius: 4px;
  font-size: 16px;
  line-height: 1;
}
.left .fold {
  right: 0;
  transform: translateY(-50%);
  border-right: none;
  border-radius: 4px 0 0 4px;
}
.right .fold {
  left: 0;
  transform: translateY(-50%);
  border-left: none;
  border-radius: 0 4px 4px 0;
}
.fold:hover,
.rail:hover {
  color: var(--sk-primary);
  background: var(--sk-primary-light);
}
.rail {
  width: 100%;
  height: 100%;
  border: none;
  background: transparent;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding-top: 14px;
}
.rail-mark {
  font-size: 16px;
  line-height: 1;
}
.rail-text {
  writing-mode: vertical-rl;
  letter-spacing: 2px;
  font-size: 12px;
}
</style>
