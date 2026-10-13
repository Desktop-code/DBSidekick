import { defineStore } from 'pinia'

const STORAGE_KEY = 'theme'
const MEDIA = '(prefers-color-scheme: dark)'

function readSaved() {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    if (value === 'light' || value === 'dark' || value === 'system') return value
  } catch {
    // 读不到时用浅色
  }
  return 'light'
}

function systemIsDark() {
  return window.matchMedia(MEDIA).matches
}

export const useThemeStore = defineStore('theme', {
  state: () => ({
    theme: readSaved()
  }),
  getters: {
    resolvedTheme(state) {
      if (state.theme === 'system') return systemIsDark() ? 'dark' : 'light'
      return state.theme
    }
  },
  actions: {
    setTheme(value) {
      const next = value === 'dark' || value === 'system' ? value : 'light'
      this.theme = next
      try {
        localStorage.setItem(STORAGE_KEY, next)
      } catch {
        // 存不住也不影响当前这次切换
      }
      this.apply()
    },
    apply() {
      const dark = this.resolvedTheme === 'dark'
      document.documentElement.classList.toggle('dark', dark)
      document.documentElement.style.colorScheme = dark ? 'dark' : 'light'
    },
    init() {
      this.apply()
      const media = window.matchMedia(MEDIA)
      const onChange = () => {
        if (this.theme === 'system') this.apply()
      }
      if (media.addEventListener) media.addEventListener('change', onChange)
      else media.addListener(onChange)
      requestAnimationFrame(() => {
        document.documentElement.classList.add('theme-ready')
      })
    }
  }
})
