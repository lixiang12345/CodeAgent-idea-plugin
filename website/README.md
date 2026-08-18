# CodeAgent 产品网站

CodeAgent for JetBrains 的营销、产品介绍与使用手册静态网站。纯 HTML/CSS/JS,无构建步骤、无外部依赖(字体、脚本、图片全部本地)。

## 结构

```
website/
├── index.html          # 营销首页(中文)
├── product.html        # 产品介绍(中文)
├── manual.html         # 使用手册(中文)
├── en/                 # 英文版(与中文页一一对应,导航右上角可切换)
│   ├── index.html
│   ├── product.html
│   └── manual.html
└── assets/
    ├── css/site.css    # 设计系统(暖纸底 + 墨绿油墨 + 品牌绿,Editorial Light)
    ├── js/site.js      # 导航、进场动画、复制按钮、灯箱、目录滚动高亮、语言切换锚点保留
    └── img/            # 界面示意图(手写动画 SVG,按 1:1 显示尺寸作图)
```

## 界面示意图

`assets/img/` 下 7 张界面图是自包含的动画 SVG,不是位图截图:

- **画的是真实插件界面**。文案、图标、颜色、间距取自插件 webview 的实际实现
  (`releases/*.jar` 内 `webviews/` 的 Svelte 前端 + source map),不自行发明控件或措辞。
- **动画用 CSS keyframes**,`<img>` 引用的 SVG 里 CSS 动画照常播放(脚本不会执行)。
  每张图都带 `prefers-reduced-motion: reduce` 兜底。
- **按显示尺寸作图**:hero 宽 1128,特性图宽 440(`.feature-media .shot` 的
  `max-width`),这样发丝线正好落在整数像素上。
- 外部资源一律不可用(字体、图片都加载不了),所以文字只用通用字体栈;
  横排元素靠左的靠左锚定、靠右的靠右锚定并留出余量,字体度量变化才不会撞字。
  同一行内变宽的文本放进同一个 `<text>` 用 `tspan` 顺排,不要各自钉死 `x`。
- 代码块缩进用显式 `x` 坐标,不用前导空格(`xml:space` 在 `<img>` 场景下不可靠)。
- CSS 动画的 `transform` 会**覆盖**元素上的 `transform` 属性:需要两者并存时,
  外层 `<g>` 放定位 `transform`,内层 `<g>` 放动画 class。

## 本地预览

```bash
cd website && python3 -m http.server 8890
```

打开 <http://127.0.0.1:8890>。直接双击 HTML 文件也可以浏览(无跨域资源)。

## 维护约定

- 中英文内容保持一一对应;两侧手册章节 `id` 必须一致,语言切换按钮依赖它保留锚点。
- 产品事实(版本号、命令、端口、功能声明)以仓库 `README.md`、`docs/PRODUCT.md` 与 `plugin.xml` 为准;不虚构数据与评价。
- 截图更新:改 `assets/img/*.svg` 本身,并同步 `<img>` 的 `width/height`(等于 SVG 的 viewBox)。
- 中文正文使用全角标点;`lang` 属性区分两版。
