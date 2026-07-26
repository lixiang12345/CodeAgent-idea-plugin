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
    └── img/            # 截图素材(复用 frontend/e2e 基线截图与 docs/images 概览图)
```

## 本地预览

```bash
cd website && python3 -m http.server 8890
```

打开 <http://127.0.0.1:8890>。直接双击 HTML 文件也可以浏览(无跨域资源)。

## 维护约定

- 中英文内容保持一一对应;两侧手册章节 `id` 必须一致,语言切换按钮依赖它保留锚点。
- 产品事实(版本号、命令、端口、功能声明)以仓库 `README.md`、`docs/PRODUCT.md` 与 `plugin.xml` 为准;不虚构数据与评价。
- 截图更新:从 `frontend/e2e/__screenshots__/` 复制并按需顶部裁剪(内容集中在顶部的页面裁掉空底),同步 `<img>` 的 `width/height`。
- 中文正文使用全角标点;`lang` 属性区分两版,`.shot.crop::before` 的提示文案随 `:lang(en)` 切换。
