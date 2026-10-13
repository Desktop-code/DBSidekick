<template>
  <div class="menu-bar">
    <el-dropdown trigger="click" @command="onFile">
      <span class="menu-item">文件</span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="newSession">新建会话</el-dropdown-item>
          <el-dropdown-item command="newQuery">新建查询</el-dropdown-item>
          <el-dropdown-item divided command="openData">打开数据目录</el-dropdown-item>
          <el-dropdown-item divided disabled>退出</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <el-dropdown trigger="click" @command="onView">
      <span class="menu-item">视图</span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="toggleTree">
            <span class="menu-check">{{ appStore.leftPanelOpen ? '✓' : '' }}</span>显示数据源树
          </el-dropdown-item>
          <el-dropdown-item command="toggleSql">
            <span class="menu-check">{{ appStore.rightPanelOpen ? '✓' : '' }}</span>显示 SQL 清单
          </el-dropdown-item>
          <el-dropdown-item divided disabled>主题切换</el-dropdown-item>
          <el-dropdown-item command="theme-light">
            <span class="menu-check">{{ themeStore.theme === 'light' ? '✓' : '' }}</span>浅色
          </el-dropdown-item>
          <el-dropdown-item command="theme-dark">
            <span class="menu-check">{{ themeStore.theme === 'dark' ? '✓' : '' }}</span>深色
          </el-dropdown-item>
          <el-dropdown-item command="theme-system">
            <span class="menu-check">{{ themeStore.theme === 'system' ? '✓' : '' }}</span>跟随系统
          </el-dropdown-item>
          <el-dropdown-item divided command="fullscreen">全屏</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <el-dropdown trigger="click" @command="onData">
      <span class="menu-item">数据</span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="connections">数据源管理</el-dropdown-item>
          <el-dropdown-item command="relations">管理表关系</el-dropdown-item>
          <el-dropdown-item divided command="aliases">生成表别名</el-dropdown-item>
          <el-dropdown-item command="infer">推断表关系</el-dropdown-item>
          <el-dropdown-item divided command="backup">备份数据</el-dropdown-item>
          <el-dropdown-item command="restore">恢复数据</el-dropdown-item>
          <el-dropdown-item divided command="resetData">
            <span class="danger-item">重置应用数据</span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <el-dropdown trigger="click" @command="onConfig">
      <span class="menu-item">配置</span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="model">模型配置</el-dropdown-item>
          <el-dropdown-item command="sql">SQL 配置</el-dropdown-item>
          <el-dropdown-item disabled>Prompt 配置</el-dropdown-item>
          <el-dropdown-item command="storage">全局设置</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <el-dropdown trigger="click" @command="onHelp">
      <span class="menu-item">帮助</span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="docs">使用文档</el-dropdown-item>
          <el-dropdown-item disabled>快捷键</el-dropdown-item>
          <el-dropdown-item divided disabled>检查更新</el-dropdown-item>
          <el-dropdown-item command="openLog">打开日志目录</el-dropdown-item>
          <el-dropdown-item command="about">关于</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <span class="spacer" />
    <div class="service-status">
      <span class="dot" :class="overallStatus" />
      <span class="label">{{ overallLabel }}</span>
      <el-popover placement="bottom-end" trigger="hover" :width="280">
        <template #reference>
          <el-icon class="info"><InfoFilled /></el-icon>
        </template>
        <div class="status-detail">
          <div><span class="dot" :class="lampClass(health.llm)" /> {{ detail.llm }}</div>
          <div><span class="dot" :class="lampClass(health.embedding)" /> {{ detail.embedding }}</div>
          <div><span class="dot" :class="lampClass(health.milvus)" /> {{ detail.milvus }}</div>
        </div>
      </el-popover>
    </div>

    <el-dialog v-model="aboutVisible" title="关于 DBSidekick" width="440px">
      <div class="about">
        <p><b>版本</b> {{ appVersion }}</p>
        <p><b>作者</b> DBSidekick Team</p>
        <p><b>技术栈</b> Spring Boot 3 · Vue 3 · JavaFX · Milvus · ONNX Embedding · DeepSeek</p>
      </div>
      <template #footer>
        <el-button type="primary" @click="aboutVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { InfoFilled } from '@element-plus/icons-vue'
import request from '../api/request'
import { backupData, chooseSystemFile, openDataDir, openLogDir, resetData, restoreData } from '../api/system'
import { generateAliases, inferRelations } from '../api/schema'
import { useAppStore } from '../stores/app'
import { useTabsStore } from '../stores/tabs'
import { useThemeStore } from '../stores/theme'

const appStore = useAppStore()
const tabsStore = useTabsStore()
const themeStore = useThemeStore()
const aboutVisible = ref(false)
/** 与后端 /api/health（来自 pom version）保持一致 */
const appVersion = ref('0.1.0')
const health = computed(() => appStore.health)
const detail = computed(() => appStore.healthDetail)
let healthTimer = null

const overallStatus = computed(() => {
  const states = [health.value.llm, health.value.embedding, health.value.milvus]
  const up = states.filter((s) => s === 'up').length
  const down = states.filter((s) => s === 'down').length
  if (up === states.length) return 'green'
  if (down === states.length) return 'red'
  return 'orange'
})

const overallLabel = computed(() => {
  if (overallStatus.value === 'green') return '服务正常'
  if (overallStatus.value === 'red') return '服务异常'
  return '部分异常'
})

function lampClass(state) {
  if (state === 'up') return 'green'
  if (state === 'down') return 'red'
  return 'gray'
}

function onFile(cmd) {
  if (cmd === 'newSession') {
    appStore.openSessionDialog({
      datasourceId: appStore.currentDatasourceId || '',
      dbName: appStore.currentDatabase || ''
    })
  } else if (cmd === 'newQuery') {
    tabsStore.openQueryEditor()
  } else if (cmd === 'openData') {
    onOpenDataDir()
  }
}

function onView(cmd) {
  if (cmd === 'toggleTree') {
    appStore.setLeftPanelOpen(!appStore.leftPanelOpen)
  } else if (cmd === 'toggleSql') {
    appStore.setRightPanelOpen(!appStore.rightPanelOpen)
  } else if (cmd === 'fullscreen') {
    onFullscreen()
  } else if (cmd === 'theme-light' || cmd === 'theme-dark' || cmd === 'theme-system') {
    themeStore.setTheme(cmd.slice('theme-'.length))
  }
}

function onData(cmd) {
  if (cmd === 'connections') {
    tabsStore.openConnectionManage()
  } else if (cmd === 'relations') {
    const id = appStore.currentDatasourceId
    if (!id) {
      ElMessage.warning('请先选择数据源')
      return
    }
    tabsStore.openRelationManage({ datasourceId: id, dbName: appStore.currentDatabase || undefined })
  } else if (cmd === 'aliases') {
    onAliases()
  } else if (cmd === 'infer') {
    onInfer()
  } else if (cmd === 'backup') {
    onBackup()
  } else if (cmd === 'restore') {
    onRestore()
  } else if (cmd === 'resetData') {
    onResetData()
  }
}

async function onFullscreen() {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else {
      await document.documentElement.requestFullscreen()
    }
  } catch {
    // 浏览器拒绝全屏时不另外弹窗
  }
}

async function onAliases() {
  const id = appStore.currentDatasourceId
  if (!id) {
    ElMessage.warning('请先选择数据源')
    return
  }
  const loading = ElMessage({ message: '生成表别名中，预计 1-2 分钟...', duration: 0, type: 'info' })
  try {
    const res = await generateAliases(id, false)
    const n = res?.generated ?? 0
    const skipped = res?.skipped ?? 0
    const failed = res?.failed ?? 0
    ElMessage.success(`生成 ${n} 张表别名（跳过 ${skipped}，失败 ${failed}）`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '生成别名失败')
  } finally {
    loading.close?.()
  }
}

async function onInfer() {
  const id = appStore.currentDatasourceId
  if (!id) {
    ElMessage.warning('请先选择数据源')
    return
  }
  const db = appStore.currentDatabase
  if (!db) {
    ElMessage.warning('请先在左侧点选一个数据库')
    return
  }
  try {
    const res = await inferRelations(id, db)
    const n = res?.inferredRelations ?? 0
    const t = res?.tablesUpdated ?? 0
    const ms = res?.elapsedMs ?? 0
    ElMessage.success(`推断 ${n} 条关系，涉及 ${t} 张表（${ms} ms）`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '推断表关系失败')
  }
}

function backupFileName() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `dbsidekick-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.db`
}

async function onBackup() {
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

async function onRestore() {
  try {
    await ElMessageBox.confirm('恢复将覆盖当前所有数据，且需要重启应用，确定继续？', '恢复数据', {
      type: 'warning',
      confirmButtonText: '继续',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    const picked = await chooseSystemFile({ title: '选择备份文件', mode: 'open' })
    if (picked?.canceled || !picked?.path) return
    await restoreData(picked.path)
    await ElMessageBox.alert('恢复成功，请关闭应用后重新打开', '恢复数据', { type: 'success' })
  } catch {
    // 拦截器已提示
  }
}

async function onResetData() {
  try {
    await ElMessageBox.confirm('将删除所有数据（数据源配置、脚本、会话等），不可恢复！确定继续？', '重置应用数据', {
      type: 'warning',
      confirmButtonText: '重置',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await resetData()
    await ElMessageBox.alert('已重置，请关闭应用后重新打开', '重置应用数据', { type: 'warning' })
  } catch {
    // 拦截器已提示
  }
}

async function onOpenDataDir() {
  try {
    const res = await openDataDir()
    ElMessage.success(res?.path ? `已打开：${res.path}` : '已打开数据目录')
  } catch {
    // 拦截器已提示
  }
}

async function onOpenLogDir() {
  try {
    const res = await openLogDir()
    ElMessage.success(res?.path ? `已打开：${res.path}` : '已打开日志目录')
  } catch {
    // 拦截器已提示
  }
}

function onConfig(cmd) {
  if (cmd === 'model') {
    tabsStore.openSettings({ section: 'llm' })
  } else if (cmd === 'sql') {
    tabsStore.openSettings({ section: 'sql' })
  } else if (cmd === 'storage') {
    tabsStore.openSettings({ section: 'storage' })
  }
}

onMounted(() => {
  appStore.refreshHealth()
  healthTimer = setInterval(() => appStore.refreshHealth(), 30000)
})

onUnmounted(() => {
  if (healthTimer) clearInterval(healthTimer)
})

async function openAbout() {
  aboutVisible.value = true
  try {
    const res = await request.get('/health')
    if (res?.version) {
      appVersion.value = String(res.version).trim()
    }
  } catch {
    // 回退默认 0.1.0
  }
}

function onHelp(cmd) {
  if (cmd === 'docs') {
    ElMessageBox.alert(
      '从左侧选择数据源后，可以在中间用自然语言提问，也可以在查询编辑器里直接写 SQL。右侧清单用来收藏常用脚本。',
      '使用说明',
      { confirmButtonText: '知道了' }
    )
  } else if (cmd === 'about') {
    openAbout()
  } else if (cmd === 'openLog') {
    onOpenLogDir()
  }
}
</script>

<style scoped>
.menu-bar {
  height: 44px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 12px;
  background: var(--sk-card);
  color: var(--sk-text-primary);
  border-bottom: 1px solid var(--sk-border);
  font-size: 13px;
  user-select: none;
}
.menu-item {
  cursor: pointer;
  padding: 4px 10px;
  border-radius: 6px;
  color: var(--sk-text-primary);
  outline: none;
}
.danger-item {
  color: var(--sk-danger);
}
.menu-check {
  display: inline-block;
  width: 1.2em;
}
.menu-item:hover {
  background: var(--sk-bg);
}
.spacer {
  flex: 1;
}
.service-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--sk-text-secondary);
}
.service-status .label {
  line-height: 1;
}
.service-status .info {
  color: var(--sk-text-tertiary);
  cursor: default;
  font-size: 14px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--sk-text-tertiary);
  display: inline-block;
  flex-shrink: 0;
}
.dot.green {
  background: var(--sk-success);
}
.dot.orange {
  background: var(--sk-warning);
}
.dot.red {
  background: var(--sk-danger);
}
.dot.gray {
  background: var(--sk-text-tertiary);
}
.status-detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
  color: var(--sk-text-primary);
  line-height: 1.4;
}
.status-detail > div {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.about p {
  margin: 8px 0;
  font-size: 13px;
  color: var(--sk-text-primary);
}
</style>
