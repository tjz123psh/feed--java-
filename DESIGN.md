---
colors:
  ink: '#0b0e13'
  surface: '#14181f'
  surface-2: '#1b2029'
  text: '#f2f4f8'
  white: '#ffffff'
  black: '#000000'
  brand: '#ff3b6b'
  brand-hi: '#ff5e85'
  brand-deep: '#e0245e'
  brand-deep-hover: '#c81f52'
  success: '#34d399'
  danger: '#e5484d'
  danger-text: '#ff6b6f'
  warning: '#f5a524'
typography:
  micro:
    fontFamily: 'Inter, "Source Han Sans CN", MiSans, "PingFang SC", system-ui, sans-serif'
    fontSize: '11px'
  caption:
    fontFamily: 'Inter, "Source Han Sans CN", MiSans, "PingFang SC", system-ui, sans-serif'
    fontSize: '12px'
  meta:
    fontFamily: 'Inter, "Source Han Sans CN", MiSans, "PingFang SC", system-ui, sans-serif'
    fontSize: '13px'
  small:
    fontFamily: 'Inter, "Source Han Sans CN", MiSans, "PingFang SC", system-ui, sans-serif'
    fontSize: '14px'
  body:
    fontFamily: 'Inter, "Source Han Sans CN", MiSans, "PingFang SC", system-ui, sans-serif'
    fontSize: '16px'
  lead:
    fontFamily: 'Inter, "Source Han Sans CN", MiSans, "PingFang SC", system-ui, sans-serif'
    fontSize: '18px'
  title:
    fontFamily: 'Inter, "Source Han Sans CN", MiSans, "PingFang SC", system-ui, sans-serif'
    fontSize: '20px'
  heading:
    fontFamily: 'Inter, "Source Han Sans CN", MiSans, "PingFang SC", system-ui, sans-serif'
    fontSize: '24px'
  page:
    fontFamily: 'Inter, "Source Han Sans CN", MiSans, "PingFang SC", system-ui, sans-serif'
    fontSize: '30px'
  display:
    fontFamily: 'Inter, "Source Han Sans CN", MiSans, "PingFang SC", system-ui, sans-serif'
    fontSize: '36px'
  hero:
    fontFamily: 'Inter, "Source Han Sans CN", MiSans, "PingFang SC", system-ui, sans-serif'
    fontSize: '48px'
  mono:
    fontFamily: '"JetBrainsMono Nerd Font", ui-monospace, SFMono-Regular, Menlo, monospace'
    fontSize: '13px'
rounded:
  sm: '6px'
  md: '12px'
  lg: '16px'
  full: '999px'
---

# DESIGN.md — feed-java 前端重构（「影院舞台」）

任务：《09》第 8 步前端联调 + 用户要求整体重构（原 UI「太丑、体验不够好」）。
范围：`frontend/`（仓库内副本）。后端契约冻结，不动 `src/`。2026-10-08 起。

## 1. 判断结论

- **产品类型**：视频消费型产品（短视频 Feed）。主页/详情的主导取向是**沉浸表达**；热榜、私信、上传、设置、账号是**工具面**（列表与表单）。同一个路由只有一个取向。
- **谁/环境/首要任务/成功**：为「浏览器访客与登录用户」在「桌面为主、窄屏可用的网页」完成「刷推荐流并随手点赞/评论/关注，按需上传、私信」；界面突出**视频内容本身**；成功 = 进入即自动播放、上下切换自然、点赞/评论/关注一步可达、页面无任何开发期痕迹（API 路径、字段名、快捷键浮片不得出现在 UI）。
- **已知**：路由 10 条；API 层完整（`src/api/*`，token 刷新已有）；数据为 7 条探针视频（封面多为占位图，内容质量不属本次范围）；后端错误文案是契约，前端照原文展示。
- **推断**：用户会在桌面 1280–1920 与窄屏（375）使用；「刷」是最高频动作。依据：现有断点 900px、任务描述。
- **未知**：无阻塞性未知。字体按本机实有资产（Inter / Source Han Sans CN / MiSans / JetBrainsMono Nerd Font，`fc-list` 已核实）落地，不引入 webfont。
- **内容模型与信息几何**：Feed = 纵向单屏视频流 → 舞台（stage）结构；热榜 = 可比较排名 → 列表行（非卡片流）；详情 = 单视频 + 评论 → 主从（桌面舞台+右栏，窄屏舞台+底部抽屉）；上传 = 向导式表单 + 分块上传进度；私信 = 主从双栏；账号/设置 = 单列表单。
- **分组**：默认邻近律（留白+对齐）；容器只留给「对象边界有意义」处（评论条目、会话列表行、表单分组）。**移除玻璃卡片堆砌**：热榜/列表/表单不再套卡，仅用留白、细线、排版层级。
- **卡片准入**：视频舞台不是卡片（无边框无阴影，视频浮在环境场上）；评论/会话行用细分隔线；空/错误态用居中提示而非空卡。
- **密度**：Comfortable（消费型默认）；私信/评论列表紧凑一档。
- **动效动机**：① 舞台环境辉光（呼吸 9s）——解释"这一屏属于这支视频"，背景是画布；② 点赞心形脉冲（180ms）——即时因果反馈；③ 抽屉/Toast 进出（200–260ms）——空间关系。其余一律不动；`prefers-reduced-motion` 全部降级为静态/即时。
- **表单校验时机**：onBlur 首次格式校验、onChange（已报错后）即时确认、onSubmit 全量并把焦点移到第一个错误；错误文案用后端原文，放字段下方；不禁用提交按钮。
- **表单原型**（登录/注册/改名/改密/上传必填）检查后按此执行。

## 2. UI Contract（最小模板）

- **产品**：浏览器访客/登录用户，刷推荐流、点赞评论关注、上传、私信；成功 = 一步操作 + 无开发痕迹。
- **结构**：桌面 ≥1024px：左轨 76px（图标+10px 标签）→ 主区；Feed 为整屏舞台（视频 contain + 环境辉光 + 底部渐层元数据 + 右侧动作栏），评论为右侧面板；热榜为 720px 居中排名列表；上传/账号为 560px 居中表单列。窄屏 <768px：底部 Tab（推荐/热榜/发布/私信/我的）→ 整屏舞台，动作栏叠放右下，评论为底部抽屉（复用）。768–1024：左轨保留、内容自适应。
- **视觉立场**：**深色放映厅**——近黑石墨底 + 内容驱动的环境辉光，通过**视频即舞台、排版承担层级（Inter 800 紧排 + 思源黑体）、单一玫瑰强调色（#ff3b6b，语义=热度/喜欢/主操作）、少量细线分隔**服务「沉浸刷流与一步操作」，避免**管理后台气质（卡片堆叠、表单裸标签、双强调色）与开发期痕迹**。
- **令牌**：见 frontmatter（色板/字阶/圆角）；间距 4px 基准；动效 90/180/260ms + 9s 呼吸；焦点环 = 品牌色 2px。
- **状态**：加载（骨架屏）、空（给行动指引）、错误（后端文案 + 重试）、未登录（引导登录）、无更多（"到底了"）、上传中（分块进度）、慢网（本机不考虑离线/缓存语义）。
- **响应式**：窄屏隐藏左轨换底部 Tab；Feed 保持全屏舞台（不缩小）；热榜排名行始终保留名次+标题+作者；主操作（发布、登录、点赞）在窄屏移至拇指区。
- **验收**：Standard 档——375/768/1440 三视口、控制台干净、键盘走通主流程、对比度实测、空/错误/加载态、reduced-motion、反模式检测器（`detect.mjs`，`frontend/src`）+ 全流程真实操作（注册→上传→刷流→点赞→评论→通知→私信，8081）。

## 3. 方向发散（记录取舍）

- **A 影院舞台（选中）**：视频无边框悬浮于环境场；导航为窄轨/底部 Tab；无卡片语言。选：与"内容即界面"的产品本质一致、桌面移动同构、直接消除现有"视频塞进卡片"的根因。
- **B 社交卡片流**（放弃）：时间线卡片（缩略图+文案）。短视频产品的主流消费模式是全屏单条，卡片流制造"再看一眼列表"的中间层，与"刷"冲突。
- **C 信息台**（放弃）：表格化首页 + 密集管理视图。产品是消费型不是后台；教学受众亦然。

## 4. 变更记录

- 2026-10-08：建立；开始实现（vite.config 加 `VITE_API_TARGET`、全局令牌、AppShell、HomeView 及顺修"下拉翻页不可达"缺陷——`v-show` 会让隐藏 slide 退出布局导致滚动高度不足，改为"全部 slide 参与布局、video 仅挂载 active±1"）。
- 2026-10-08 评审闭环修正：封面 404 时改用元素自身渐层兜底（`alt=""` 防破图标+alt 文本）；超长无空格文本 `overflow-wrap: anywhere`；窄屏隐藏快捷键入口并缩标签（消除与工具区压叠）；对比度修正——`--text-3` 0.4→0.5、主按钮底色改 `--brand-deep`（白字 ≥4.5:1，实测见验收记录）。
- 环境备注：rolldown-vite 连续快速改同一 SFC 的模板+样式时，样式增量模块可能滞留旧版；`touch` 文件即可强制重编译（验收时实测）。
