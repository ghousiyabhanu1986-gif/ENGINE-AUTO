package com.auto.engine.extensions

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.gson.Gson
import java.io.File

/**
 * JavaScript bridge that exposes Chrome Extension APIs to WebView content scripts.
 * Injected as window.AndroidBridge; the companion JS bootstrapper maps it to
 * window.chrome.* APIs so extensions work without modification.
 */
class ChromeApisBridge(
    private val context: Context,
    private val isIncognito: Boolean
) {
    private var webView: WebView? = null

    fun setWebView(wv: WebView) {
        this.webView = wv
    }
    private val gson = Gson()
    private val storage = context.getSharedPreferences("ext_storage", Context.MODE_PRIVATE)
    private val syncStorage = context.getSharedPreferences("ext_storage_sync", Context.MODE_PRIVATE)
    private val extensionsDir = File(context.filesDir, "extensions")

    // ─── chrome.storage.local ────────────────────────────────────────────────

    @JavascriptInterface
    fun storageLocalGet(keysJson: String): String {
        return try {
            val keys = gson.fromJson(keysJson, Array<String>::class.java)
            val result = mutableMapOf<String, Any?>()
            val all = storage.all
            keys.forEach { key -> result[key] = all[key] }
            gson.toJson(result)
        } catch (e: Exception) {
            gson.toJson(storage.all)
        }
    }

    @JavascriptInterface
    fun storageLocalSet(dataJson: String) {
        try {
            val map = gson.fromJson(dataJson, Map::class.java)
            val edit = storage.edit()
            map.forEach { (k, v) -> edit.putString(k.toString(), v?.toString() ?: "") }
            edit.apply()
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun storageLocalRemove(keysJson: String) {
        try {
            val keys = gson.fromJson(keysJson, Array<String>::class.java)
            val edit = storage.edit()
            keys.forEach { edit.remove(it) }
            edit.apply()
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun storageLocalClear() = storage.edit().clear().apply()

    // ─── chrome.storage.sync ─────────────────────────────────────────────────

    @JavascriptInterface
    fun storageSyncGet(keysJson: String): String {
        return try {
            val keys = gson.fromJson(keysJson, Array<String>::class.java)
            val result = mutableMapOf<String, Any?>()
            val all = syncStorage.all
            keys.forEach { key -> result[key] = all[key] }
            gson.toJson(result)
        } catch (e: Exception) {
            gson.toJson(syncStorage.all)
        }
    }

    @JavascriptInterface
    fun storageSyncSet(dataJson: String) {
        try {
            val map = gson.fromJson(dataJson, Map::class.java)
            val edit = syncStorage.edit()
            map.forEach { (k, v) -> edit.putString(k.toString(), v?.toString() ?: "") }
            edit.apply()
        } catch (_: Exception) {}
    }

    // ─── chrome.tabs ─────────────────────────────────────────────────────────

    @JavascriptInterface
    fun tabsGetCurrent(): String {
        val tab = mapOf(
            "id" to 1,
            "url" to "about:blank",
            "title" to "Tab",
            "active" to true,
            "incognito" to isIncognito
        )
        return gson.toJson(tab)
    }

    @JavascriptInterface
    fun tabsCreate(url: String): String {
        // Signal to the JS layer that a new tab was requested
        val tab = mapOf("id" to 2, "url" to url, "active" to true)
        return gson.toJson(tab)
    }

    // ─── chrome.runtime ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun runtimeGetId(): String = "betting_engine_runtime"

    @JavascriptInterface
    fun runtimeGetManifest(): String = "{\"name\":\"BettingEngine\",\"version\":\"1.0\",\"manifest_version\":3}"

    @JavascriptInterface
    fun runtimeSendMessage(message: String): String {
        // In a real browser, this would go to the background script.
        // For now, we'll just echo it back or handle specific automation commands.
        return "{\"success\":true}"
    }

    @JavascriptInterface
    fun automationClick(x: Int, y: Int) {
        webView?.post {
            // Coordinate scaling for screen density
            val density = context.resources.displayMetrics.density
            val scaledX = x * density
            val scaledY = y * density
            
            val downTime = android.os.SystemClock.uptimeMillis()
            val eventTime = android.os.SystemClock.uptimeMillis()
            
            val downEvent = android.view.MotionEvent.obtain(
                downTime, eventTime, android.view.MotionEvent.ACTION_DOWN,
                scaledX, scaledY, 0
            )
            webView?.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            webView?.postDelayed({
                val upEvent = android.view.MotionEvent.obtain(
                    downTime, android.os.SystemClock.uptimeMillis(),
                    android.view.MotionEvent.ACTION_UP, scaledX, scaledY, 0
                )
                webView?.dispatchTouchEvent(upEvent)
                upEvent.recycle()
            }, 50)
        }
    }

    @JavascriptInterface
    fun logExtension(message: String) {
        android.util.Log.d("ExtensionLog", message)
    }

    @JavascriptInterface
    fun runtimeGetURL(resourcePath: String): String {
        // Build a custom scheme URL that will be intercepted by the WebView client
        // Format: chrome-extension://<ext_id>/<resource_path>
        return "chrome-extension://auto-engine-extension/$resourcePath"
    }

    // ─── chrome.webNavigation ────────────────────────────────────────────────

    @JavascriptInterface
    fun webNavigationGetCurrentTab(): String = tabsGetCurrent()

    // ─── Utility ─────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun isIncognitoMode(): Boolean = isIncognito

    @JavascriptInterface
    fun getVersion(): String = "1.0.0"

    companion object {
        /**
         * Find an extension resource file by searching all installed extension directories.
         * Used by WebViewClient to resolve chrome-extension:// URLs.
         */
        fun findExtensionResource(context: Context, resourcePath: String): File? {
            val extensionsDir = File(context.filesDir, "extensions")
            if (!extensionsDir.exists()) return null
            extensionsDir.listFiles { f -> f.isDirectory }?.forEach { extDir ->
                // Check direct path
                val direct = File(extDir, resourcePath)
                if (direct.exists()) return direct
                // Check one level of subdirectories (for zips with wrapper dirs)
                extDir.listFiles { f -> f.isDirectory }?.forEach { subDir ->
                    val nested = File(subDir, resourcePath)
                    if (nested.exists()) return nested
                }
            }
            return null
        }

        /**
         * JavaScript bootstrapper injected into every page.
         * Maps window.AndroidBridge.* to window.chrome.* APIs.
         */
        val BOOTSTRAP_JS = """
(function() {
  if (typeof window.AndroidBridge === 'undefined') return;
  var bridge = window.AndroidBridge;
  
  window.chrome = window.chrome || {};
  
  // chrome.storage
  window.chrome.storage = {
    local: {
      get: function(keys, cb) {
        var k = Array.isArray(keys) ? JSON.stringify(keys) : JSON.stringify(Object.keys(keys || {}));
        var result = JSON.parse(bridge.storageLocalGet(k));
        if(cb) cb(result); return result;
      },
      set: function(items, cb) { bridge.storageLocalSet(JSON.stringify(items)); if(cb) cb(); },
      remove: function(keys, cb) {
        bridge.storageLocalRemove(JSON.stringify(Array.isArray(keys) ? keys : [keys]));
        if(cb) cb();
      },
      clear: function(cb) { bridge.storageLocalClear(); if(cb) cb(); }
    },
    sync: {
      get: function(keys, cb) {
        var k = Array.isArray(keys) ? JSON.stringify(keys) : JSON.stringify(Object.keys(keys || {}));
        var result = JSON.parse(bridge.storageSyncGet(k));
        if(cb) cb(result); return result;
      },
      set: function(items, cb) { bridge.storageSyncSet(JSON.stringify(items)); if(cb) cb(); }
    }
  };
  
  // chrome.runtime
  window.chrome.runtime = {
    id: bridge.runtimeGetId(),
    getManifest: function() { return JSON.parse(bridge.runtimeGetManifest()); },
    getURL: function(path) { return bridge.runtimeGetURL(path); },
    sendMessage: function(msg, cb) { 
      if (typeof msg === 'object' && msg.type === 'automation_click') {
        bridge.automationClick(msg.x, msg.y);
      }
      var r = JSON.parse(bridge.runtimeSendMessage(JSON.stringify(msg))); 
      if(cb) cb(r); 
    },
    onMessage: { 
      addListener: function(fn) { 
        window._chromeOnMessageListeners = window._chromeOnMessageListeners || []; 
        window._chromeOnMessageListeners.push(fn); 
      } 
    },
    connect: function() { return { onMessage: { addListener: function(){} }, postMessage: function(){} }; }
  };

  // Automation Helper
  window.chrome.automation = {
    click: function(x, y) { 
      // Account for scroll position
      var sx = x + window.scrollX;
      var sy = y + window.scrollY;
      bridge.automationClick(Math.round(sx), Math.round(sy)); 
    },
    dispatchClick: async function(element) {
      if (!element) return;
      const rect = element.getBoundingClientRect();
      // Use center of element for most reliable click
      const x = rect.left + rect.width / 2;
      const y = rect.top + rect.height / 2;
      this.click(x, y);
    },
    touch: async function(element) {
      if (!element) return;
      this.dispatchClick(element);
    }
  };

  // Expanded Message Handling (Kiwi-style)
  window.chrome.runtime.onMessage = {
    listeners: [],
    addListener: function(fn) { this.listeners.push(fn); },
    removeListener: function(fn) { this.listeners = this.listeners.filter(l => l !== fn); },
    trigger: function(msg, sender, sendResponse) {
      this.listeners.forEach(l => l(msg, sender, sendResponse));
    }
  };
  
  // chrome.tabs
  window.chrome.tabs = {
    getCurrent: function(cb) { var t = JSON.parse(bridge.tabsGetCurrent()); if(cb) cb(t); return t; },
    create: function(props, cb) { var t = JSON.parse(bridge.tabsCreate(props.url || '')); if(cb) cb(t); return t; },
    query: function(q, cb) { var t = [JSON.parse(bridge.tabsGetCurrent())]; if(cb) cb(t); return t; },
    update: function(id, props) {},
    sendMessage: function(id, msg, cb) { var r = JSON.parse(bridge.runtimeSendMessage(JSON.stringify(msg))); if(cb) cb(r); }
  };
  
  // chrome.windows
  window.chrome.windows = {
    update: function(id, props) {}
  };
  
  // chrome.action
  window.chrome.action = {
    setTitle: function() {},
    setBadgeText: function() {},
    setBadgeBackgroundColor: function() {},
    onClicked: { addListener: function(fn) { window._chromeActionListener = fn; } }
  };
  
  // chrome.scripting
  window.chrome.scripting = {
    executeScript: function(details) { if(details.func) details.func(); }
  };
  
  // chrome.cookies
  window.chrome.cookies = { getAll: function(d,cb) { if(cb) cb([]); } };
  
  // chrome.notifications
  window.chrome.notifications = {
    create: function(id, opts, cb) { bridge.notificationsCreate(id||'', JSON.stringify(opts)); if(cb) cb(id||''); }
  };
  
  // chrome.contextMenus (stub)
  window.chrome.contextMenus = { create: function(){}, remove: function(){}, onClicked: { addListener: function(){} } };
  
  // chrome.webNavigation
  window.chrome.webNavigation = { onCompleted: { addListener: function(){} } };
  
  // chrome.permissions
  window.chrome.permissions = { contains: function(p,cb){ if(cb) cb(true); }, request: function(p,cb){ if(cb) cb(true); } };
  
  // chrome.i18n
  window.chrome.i18n = { getMessage: function(key) { return key; }, getUILanguage: function() { return navigator.language; } };
  
  // chrome.alarms
  window.chrome.alarms = { create: function(){}, clear: function(){}, onAlarm: { addListener: function(){} } };
  
  // chrome.downloads
  window.chrome.downloads = {
    download: function(opts, cb) {
      var a = document.createElement('a');
      a.href = opts.url || '';
      a.download = opts.filename || '';
      a.click();
      if(cb) cb(1);
    }
  };
  
  // chrome.webRequest (stub)
  window.chrome.webRequest = {
    onBeforeRequest: { addListener: function(){} },
    onHeadersReceived: { addListener: function(){} }
  };
  
  // Stealth Overrides
  const stealth = () => {
    // Override navigator.webdriver
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
    
    // Override Languages
    Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
    
    // Override Plugins
    Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
    
    // Override Chrome object
    window.chrome = window.chrome || {};
    if (!window.chrome.app) {
        window.chrome.app = { InstallState: "IDLE", RunningState: "CANNOT_RUN", getDetails: () => {}, getIsInstalled: () => false };
    }
    
    // Fix permissions
    const originalQuery = window.navigator.permissions.query;
    window.navigator.permissions.query = (parameters) => (
      parameters.name === 'notifications' ?
        Promise.resolve({ state: Notification.permission }) :
        originalQuery(parameters)
    );
  };
  stealth();

  console.log('[BettingEngine] Chrome Extension APIs and Stealth initialized');
})();
        """.trimIndent()
    }
}
