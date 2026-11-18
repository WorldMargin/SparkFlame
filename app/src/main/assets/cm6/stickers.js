// 编辑器表情预览：在 [表情码] 左侧按行高画一张抖音表情图。
// 只负责显示，不改编辑器文档；坐标来自 CodeMirror 的 coordsAtPos。
(function () {
  var MAP = {};
  try {
    if (window.AndroidEditor && AndroidEditor.stickerMap) {
      MAP = JSON.parse(AndroidEditor.stickerMap());
    }
  } catch (e) {}

  var layer = null;
  var view = null;
  var timer = null;

  function ensure() {
    if (!window.spark || typeof window.spark.getView !== "function") return false;
    view = window.spark.getView();
    if (!view) return false;
    if (!layer) {
      layer = document.createElement("div");
      layer.id = "sticker-layer";
      layer.style.cssText =
        "position:absolute;inset:0;pointer-events:none;overflow:hidden;z-index:5;";
      var host = document.getElementById("editor") || document.body;
      host.appendChild(layer);
    }
    return true;
  }

  function render() {
    if (!ensure()) return;
    while (layer.firstChild) layer.removeChild(layer.firstChild);
    var doc = view.state.doc.toString();
    var rect = view.dom.getBoundingClientRect();
    var re = /\[[^\[\]\n]{1,12}\]/g;
    var m;
    while ((m = re.exec(doc))) {
      var code = m[0];
      var src = MAP[code];
      if (!src) continue;
      var coords = view.coordsAtPos(m.index);
      if (!coords) continue;
      var img = document.createElement("img");
      img.src = src;
      var h = Math.max(12, coords.bottom - coords.top - 1);
      img.style.cssText =
        "position:absolute;left:" +
        (coords.left - rect.left - 3) +
        "px;top:" +
        (coords.top - rect.top) +
        "px;height:" +
        h +
        "px;width:auto;transform:translate(-100%,0);pointer-events:none;";
      layer.appendChild(img);
    }
  }

  function schedule() {
    if (timer) return;
    timer = setTimeout(function () {
      timer = null;
      render();
    }, 60);
  }

  document.addEventListener("DOMContentLoaded", function () {
    var tries = 0;
    var iv = setInterval(function () {
      if (++tries > 150) {
        clearInterval(iv);
        return;
      }
      if (!ensure()) return;
      clearInterval(iv);
      var scroller = view.scrollDOM || view.dom;
      if (scroller) scroller.addEventListener("scroll", schedule, { passive: true });
      view.dom.addEventListener("keyup", schedule);
      view.dom.addEventListener("input", schedule);
      window.addEventListener("resize", schedule);
      try {
        new MutationObserver(schedule).observe(view.contentDOM, {
          childList: true,
          characterData: true,
          subtree: true,
        });
      } catch (e) {}
      schedule();
    }, 200);
  });
})();
