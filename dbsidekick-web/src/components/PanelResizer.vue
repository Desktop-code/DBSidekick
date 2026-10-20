<template>
  <div
    class="panel-resizer"
    :class="{ active: dragging, disabled }"
    title="拖拽调整宽度"
    @mousedown.prevent="onMouseDown"
  />
</template>

<script setup>
import { onBeforeUnmount, ref } from 'vue'

const props = defineProps({
  /** 拖动时鼠标右移为正方向对应的宽度增量符号：left=+1，right=-1 */
  direction: { type: String, default: 'left' },
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['start', 'move', 'end'])

const dragging = ref(false)
let startX = 0
let moved = false

function onMouseDown(e) {
  if (props.disabled || e.button !== 0) return
  dragging.value = true
  moved = false
  startX = e.clientX
  emit('start')
  document.addEventListener('mousemove', onMouseMove)
  document.addEventListener('mouseup', onMouseUp)
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
}

function onMouseMove(e) {
  if (!dragging.value) return
  moved = true
  const dx = e.clientX - startX
  const signed = props.direction === 'right' ? -dx : dx
  emit('move', signed)
}

function onMouseUp() {
  if (!dragging.value) return
  dragging.value = false
  document.removeEventListener('mousemove', onMouseMove)
  document.removeEventListener('mouseup', onMouseUp)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  emit('end', moved)
}

onBeforeUnmount(() => {
  document.removeEventListener('mousemove', onMouseMove)
  document.removeEventListener('mouseup', onMouseUp)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
})
</script>

<style scoped>
.panel-resizer {
  flex: 0 0 4px;
  width: 4px;
  align-self: stretch;
  cursor: col-resize;
  background: transparent;
  position: relative;
  z-index: 5;
  transition: background 0.15s ease;
}
.panel-resizer::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: -2px;
  right: -2px;
}
.panel-resizer:hover,
.panel-resizer.active {
  background: var(--sk-primary-light, #eff6ff);
}
.panel-resizer.disabled {
  cursor: default;
  pointer-events: none;
  opacity: 0;
}
html.dark .panel-resizer:hover,
html.dark .panel-resizer.active {
  background: rgba(96, 165, 250, 0.25);
}
</style>
