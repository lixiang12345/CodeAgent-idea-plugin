/* CodeAgent 产品站 · 共享交互(无依赖) */
(function () {
  "use strict";

  var reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* 顶部导航:滚动态 + 移动端开合 */
  var nav = document.querySelector(".nav");
  function onScroll() {
    if (nav) nav.classList.toggle("scrolled", window.scrollY > 8);
  }
  onScroll();
  window.addEventListener("scroll", onScroll, { passive: true });

  var toggle = document.querySelector(".nav-toggle");
  var links = document.querySelector(".nav-links");
  if (toggle && links) {
    toggle.addEventListener("click", function () {
      var open = links.classList.toggle("open");
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
    links.addEventListener("click", function (e) {
      if (e.target.closest("a")) {
        links.classList.remove("open");
        toggle.setAttribute("aria-expanded", "false");
      }
    });
  }

  /* 主题切换:手动优先,记住选择;首次跟随系统 */
  var themeBtn = document.querySelector(".theme-toggle");
  function syncThemeLabel() {
    if (!themeBtn) return;
    var dark = document.documentElement.getAttribute("data-theme") === "dark";
    var zh = (document.documentElement.lang || "").indexOf("zh") === 0;
    themeBtn.setAttribute("aria-label", dark
      ? (zh ? "切换到浅色主题" : "Switch to light theme")
      : (zh ? "切换到深色主题" : "Switch to dark theme"));
  }
  if (themeBtn) {
    themeBtn.addEventListener("click", function () {
      var root = document.documentElement;
      var toDark = root.getAttribute("data-theme") !== "dark";
      if (toDark) root.setAttribute("data-theme", "dark");
      else root.removeAttribute("data-theme");
      try { localStorage.setItem("ca-theme", toDark ? "dark" : "light"); } catch (e) {}
      syncThemeLabel();
    });
    syncThemeLabel();
  }

  /* 语言切换:携带当前锚点跳到对应语言页 */
  document.querySelectorAll("[data-lang-link]").forEach(function (a) {
    a.addEventListener("click", function () {
      if (location.hash) {
        a.setAttribute("href", a.getAttribute("href").split("#")[0] + location.hash);
      }
    });
  });

  /* 进场动画 */
  if (!reducedMotion && "IntersectionObserver" in window) {
    var io = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (en) {
          if (en.isIntersecting) {
            en.target.classList.add("in");
            io.unobserve(en.target);
          }
        });
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.05 }
    );
    document.querySelectorAll(".reveal").forEach(function (el) { io.observe(el); });
  } else {
    document.querySelectorAll(".reveal").forEach(function (el) { el.classList.add("in"); });
  }

  /* 代码块复制 */
  document.querySelectorAll(".code").forEach(function (block) {
    var btn = block.querySelector(".copy-btn");
    var pre = block.querySelector("pre");
    if (!btn || !pre) return;
    btn.addEventListener("click", function () {
      var text = pre.innerText.replace(/\n+$/, "");
      function done(ok) {
        btn.classList.toggle("done", ok);
        btn.querySelector("span").textContent = ok ? "已复制" : "复制失败";
        setTimeout(function () {
          btn.classList.remove("done");
          btn.querySelector("span").textContent = "复制";
        }, 1600);
      }
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
      } else {
        done(false);
      }
    });
  });

  /* 截图灯箱 */
  var lightbox = document.getElementById("lightbox");
  if (lightbox) {
    var lbImg = lightbox.querySelector("img");
    var lbTitle = lightbox.querySelector(".lb-title");
    document.querySelectorAll("[data-lightbox]").forEach(function (el) {
      el.addEventListener("click", function () {
        var img = el.querySelector("img");
        if (!img) return;
        lbImg.src = img.src;
        lbImg.alt = img.alt;
        if (lbTitle) lbTitle.textContent = el.getAttribute("data-lightbox") || img.alt;
        lightbox.showModal();
      });
    });
    lightbox.querySelector(".lb-close").addEventListener("click", function () { lightbox.close(); });
    lightbox.addEventListener("click", function (e) {
      var r = lightbox.getBoundingClientRect();
      var inside = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom;
      if (!inside) lightbox.close();
    });
  }

  /* 手册目录:桌面端展开 + 滚动高亮 */
  var tocDetails = document.querySelector(".docs-side details");
  if (tocDetails) {
    var mq = window.matchMedia("(min-width: 961px)");
    function syncToc(e) { if (e.matches) tocDetails.open = true; }
    syncToc(mq);
    mq.addEventListener ? mq.addEventListener("change", syncToc) : mq.addListener(syncToc);
  }

  var tocLinks = Array.prototype.slice.call(document.querySelectorAll(".toc-group a[href^='#']"));
  var sections = tocLinks
    .map(function (a) { return document.getElementById(a.getAttribute("href").slice(1)); })
    .filter(Boolean);
  if (sections.length) {
    var setActive = function (id) {
      tocLinks.forEach(function (a) {
        a.classList.toggle("active", a.getAttribute("href") === "#" + id);
      });
    };
    var spy = new IntersectionObserver(
      function (entries) {
        var visible = entries
          .filter(function (en) { return en.isIntersecting; })
          .sort(function (a, b) { return a.boundingClientRect.top - b.boundingClientRect.top; });
        if (visible.length) setActive(visible[0].target.id);
      },
      { rootMargin: "-20% 0px -66% 0px", threshold: 0 }
    );
    sections.forEach(function (s) { spy.observe(s); });
  }
})();
