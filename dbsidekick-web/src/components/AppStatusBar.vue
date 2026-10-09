<template>
  <div class="status-bar">
    <span class="item"><el-icon><CircleCheck /></el-icon> {{ appStore.statusText || '就绪' }}</span>
    <span class="sep">·</span>
    <span class="item"><el-icon><FolderOpened /></el-icon> {{ appStore.currentDatasourceName || '未选择' }}<template v-if="appStore.currentDatabase"> · {{ appStore.currentDatabase }}</template></span>
    <span class="sep">·</span>
    <span>{{ appStore.currentDialect || '-' }}</span>
    <span class="sep">·</span>
    <span>行数 {{ appStore.lastRowCount }} · 耗时 {{ appStore.lastElapsedMs }}ms</span>
  </div>
</template>

<script setup>
import { CircleCheck, FolderOpened } from '@element-plus/icons-vue'
import { useAppStore } from '../stores/app'
const appStore = useAppStore()
</script>

<style scoped>
.status-bar {
  height: 28px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  font-size: 12px;
  color: var(--sk-text-secondary);
  background: #fafbfc;
  border-top: 1px solid var(--sk-border);
}
.item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.item :deep(.el-icon) {
  font-size: 13px;
  color: var(--sk-text-tertiary);
}
.sep {
  color: #cbd5e1;
}
</style>
