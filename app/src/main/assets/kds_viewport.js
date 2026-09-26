/*
 * Injected at document start when VIEWPORT_WIDTH is set in kds.properties.
 * Forces the page's layout width to a fixed number of CSS pixels (e.g. 1920),
 * so the KDS looks the same on every TV no matter its resolution or density.
 * The WebView then scales that layout to fill the screen.
 */
(function () {
  'use strict';
  if (window.__kdsViewport || window.top !== window) return;
  window.__kdsViewport = true;

  var content = 'width=__KDS_VIEWPORT_WIDTH__, user-scalable=no';

  function apply() {
    var root = document.head || document.documentElement;
    if (!root) return false;
    var metas = document.querySelectorAll('meta[name="viewport"]');
    if (!metas.length) {
      var meta = document.createElement('meta');
      meta.name = 'viewport';
      meta.content = content;
      root.insertBefore(meta, root.firstChild);
      return true;
    }
    for (var i = 0; i < metas.length; i++) {
      if (metas[i].getAttribute('content') !== content) metas[i].setAttribute('content', content);
    }
    return true;
  }

  function touchesViewport(mutations) {
    for (var i = 0; i < mutations.length; i++) {
      var m = mutations[i];
      if (m.type === 'attributes') return true;
      for (var j = 0; j < m.addedNodes.length; j++) {
        var n = m.addedNodes[j];
        if (n.nodeName === 'META' || n.nodeName === 'HEAD' || n.nodeName === 'HTML') return true;
      }
    }
    return false;
  }

  apply();
  // Re-apply whenever the page adds or edits a viewport tag (also in single-page apps).
  new MutationObserver(function (mutations) {
    if (touchesViewport(mutations)) apply();
  }).observe(document, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['content']
  });
})();
