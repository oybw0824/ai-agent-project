import { marked } from 'marked'
import DOMPurify from 'dompurify'

// 配置 marked
marked.setOptions({ gfm: true, breaks: true })

/** 解析 YAML frontmatter（Skill 文档的 ---...--- 元数据块） */
export function parseFrontmatter(text: string): { frontmatter: Record<string, string> | null; body: string } {
  if (!text) return { frontmatter: null, body: text }
  const match = text.match(/^---\s*\n([\s\S]*?)\n---\s*\n?([\s\S]*)$/)
  if (!match) return { frontmatter: null, body: text }
  const yaml: Record<string, string> = {}
  for (const line of match[1].split('\n')) {
    const idx = line.indexOf(':')
    if (idx > 0) yaml[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
  }
  return { frontmatter: yaml, body: match[2] || '' }
}

/** 渲染 Markdown → HTML（经 DOMPurify 消毒防 XSS） */
export function renderMarkdown(text: string): string {
  if (!text) return ''
  try {
    const raw = marked.parse(text) as string
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return (DOMPurify as any).sanitize(raw)
  } catch (e) {
    console.warn('marked 渲染失败:', e)
    return escapeHtml(text)
  }
}

/** 渲染 Skill 文档（剥离 frontmatter → 渲染正文） */
export function renderSkillMarkdown(text: string): { frontmatter: Record<string, string> | null; html: string } {
  const { frontmatter, body } = parseFrontmatter(text)
  return { frontmatter, html: renderMarkdown(body) }
}

/** 基础 HTML 转义（marked 不可用时的兜底） */
function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#039;')
    .replace(/\n/g, '<br>')
}
