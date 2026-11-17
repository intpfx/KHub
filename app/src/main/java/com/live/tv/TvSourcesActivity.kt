package com.live.tv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.live.tv.media.VodRepository
import org.json.JSONObject
import java.util.concurrent.Executors

/** Settings sub-page for the VOD catalogue sources that the former web panel used to manage. */
class TvSourcesActivity : Activity() {
    private lateinit var menuKey: MenuKeyController
    private lateinit var globalSearch: GlobalSearchOverlay
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tv-sources").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val repository by lazy { VodRepository.get(this) }
    private val rows = mutableListOf<View>()
    private lateinit var list: LinearLayout
    private lateinit var message: TextView
    private var dialog: AlertDialog? = null
    private var active = false
    private var busy = false
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(40), dp(64), dp(32))
            setBackgroundColor(BASE)
            tag = "sources-root"
        }
        content.addView(text(getString(R.string.app_name), 14f, SECONDARY))
        content.addView(text("影视来源", 34f, INK).apply {
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(12))
        })
        content.addView(text("输入采集地址仍在电视上进行；来源只用于流媒体搜索与播放。", 15f, SECONDARY).apply {
            setPadding(0, 0, 0, dp(16))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, LinearLayout.LayoutParams(-1, -2))
        message = text("", 15f, SECONDARY).apply {
            tag = "sources-message"; setPadding(0, dp(24), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(message)
        content.addView(text("↑ ↓ 选择    确认键打开    返回键回到设置", 13f, SECONDARY).apply { setPadding(0, dp(22), 0, 0) })
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(BASE) }
        root.addView(ScrollView(this).apply {
            setBackgroundColor(BASE); isFillViewport = true; isFocusable = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, ViewGroup.LayoutParams(-1, -1))
        globalSearch = GlobalSearchOverlay(this)
        root.addView(globalSearch, ViewGroup.LayoutParams(-1, -1))
        menuKey = MenuKeyController(onSearch = { globalSearch.show() })
        setContentView(root)
        refreshRows()
    }

    override fun onResume() { super.onResume(); active = true; refreshRows() }
    override fun onPause() { active = false; dialog?.dismiss(); dialog = null; super.onPause() }
    override fun onDestroy() { generation++; if (::menuKey.isInitialized) menuKey.cancel(); worker.shutdownNow(); super.onDestroy() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::globalSearch.isInitialized && globalSearch.isOpen()) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) globalSearch.hide()
                return true
            }
            return globalSearch.dispatchKeyEvent(event)
        }
        if (::menuKey.isInitialized && menuKey.handle(event)) return true
        val key = event.keyCode
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                val popup = dialog
                if (popup?.isShowing == true) popup.dismiss()
                else DesktopMode.returnToHome(this)
            }
            return true
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && rows.isNotEmpty()) {
                val current = rows.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)
                rows[(current + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(0, rows.lastIndex)].requestFocus()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun refreshRows() {
        if (!active) return
        val token = ++generation
        worker.execute {
            val result = try { repository.state() } catch (_: Exception) { null }
            main.post { if (active && token == generation) render(result) }
        }
    }

    private fun render(state: JSONObject?) {
        list.removeAllViews(); rows.clear()
        val sources = state?.optJSONArray("sources")
        if (sources == null || sources.length() == 0) {
            list.addView(text("尚未添加影视来源。", 17f, SECONDARY).apply { setPadding(0, dp(8), 0, dp(12)) })
        } else {
            for (index in 0 until sources.length()) {
                val source = sources.optJSONObject(index) ?: continue
                val id = source.optString("id")
                val name = source.optString("name").ifBlank { "未命名来源" }
                val searchable = source.optBoolean("searchable")
                val row = row(name, if (searchable) "可搜索" else "此来源不可原生浏览") { confirmRemove(id, name) }
                row.tag = "sources-row-$index"
                addRow(row)
            }
        }
        addRow(row("添加无损云", "使用预置的无损云采集地址") { addSource(mapOf("preset" to "wsyzy")) })
        addRow(row("添加自定义来源", "填写名称与 MacCMS JSON 采集地址") { showAddSource() })
        linkRows()
        if (dialog?.isShowing != true) rows.firstOrNull()?.requestFocus()
    }

    private fun linkRows() {
        rows.forEachIndexed { index, view ->
            view.nextFocusUpId = rows[(index - 1 + rows.size) % rows.size].id
            view.nextFocusDownId = rows[(index + 1) % rows.size].id
            view.nextFocusLeftId = view.id; view.nextFocusRightId = view.id
        }
    }

    private fun addRow(view: View) { rows.add(view); list.addView(view, LinearLayout.LayoutParams(-1, -2)) }

    private fun row(title: String, subtitle: String, action: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            id = View.generateViewId(); orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16)); minimumHeight = dp(76)
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            background = focusBackground()
            setOnClickListener { action() }
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val titleView = text(title, 21f, SECONDARY)
        val subtitleView = text(subtitle, 14f, SECONDARY).apply { setPadding(0, dp(5), 0, 0) }
        labels.addView(titleView); labels.addView(subtitleView)
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            titleView.setTextColor(if (focused) BASE else SECONDARY)
            titleView.typeface = android.graphics.Typeface.create("sans-serif", if (focused) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            subtitleView.setTextColor(if (focused) 0xFF3B404A.toInt() else SECONDARY)
        }
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.text = "$title。$subtitle"; info.className = "android.widget.Button"
            }
        }
        return row
    }

    private fun focusBackground() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
            setColor(INK); cornerRadius = dp(16).toFloat()
        })
        addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply { setColor(Color.TRANSPARENT) })
    }

    private fun showAddSource() {
        val name = input("来源名称", 120).apply { tag = "sources-name" }
        val url = input("MacCMS JSON 采集地址", 8192).apply {
            tag = "sources-url"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(name); addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        val status = text("", 14f, SECONDARY).apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(content); addView(status) }
        val popup = TvDialogs.builder(this).setTitle("添加影视来源").setView(holder)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加", null)
            .create()
        popup.setOnShowListener {
            popup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (name.text.isBlank() || url.text.isBlank()) { status.text = "请填写来源名称与采集地址"; return@setOnClickListener }
                popup.dismiss()
                addSource(mapOf("name" to name.text.toString().trim(), "url" to url.text.toString().trim()))
            }
        }
        popup.show()
        dialog = popup
        popup.setOnDismissListener { if (dialog === popup) dialog = null }
        popup.getButton(AlertDialog.BUTTON_POSITIVE)?.let { positive ->
            name.nextFocusDownId = url.id; url.nextFocusUpId = name.id; url.nextFocusDownId = positive.id
            positive.nextFocusUpId = url.id
        }
        name.requestFocus()
    }

    private fun confirmRemove(id: String, name: String) {
        if (id.isBlank()) return
        dialog = TvDialogs.builder(this).setTitle("移除影视来源")
            .setMessage("移除“$name”？此操作无法撤销。已有缓存不会在此删除。")
            .setNegativeButton("保留", null)
            .setPositiveButton("移除") { _, _ ->
                val token = ++generation
                busy = true; message.text = "正在移除…"
                worker.execute {
                    val result = try { repository.command("vod_remove_source", mapOf("id" to id)) }
                    catch (_: Exception) { JSONObject().put("ok", false).put("error", "移除失败，请重试") }
                    val state = try { repository.state() } catch (_: Exception) { null }
                    main.post {
                        if (!active) return@post
                        busy = false; message.text = if (result.optBoolean("ok")) "已移除来源" else result.optString("error", "移除失败")
                        if (token == generation) render(state)
                    }
                }
            }.show().also { popup ->
                popup.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }
    }

    private fun addSource(form: Map<String, String>) {
        if (busy) { message.text = "请等待当前操作完成"; return }
        busy = true; message.text = "正在添加…"
        val token = ++generation
        worker.execute {
            val result = try { repository.command("vod_add_source", form) }
            catch (_: Exception) { JSONObject().put("ok", false).put("error", "无法保存来源，请重试") }
            val state = try { repository.state() } catch (_: Exception) { null }
            main.post {
                if (!active) return@post
                busy = false
                message.text = if (result.optBoolean("ok")) "已添加来源" else result.optString("error", "无法添加此来源")
                if (token == generation) render(state)
            }
        }
    }

    private fun input(hint: String, limit: Int) = TvDialogs.searchInput(this, hint).apply {
        id = View.generateViewId()
        filters = arrayOf(InputFilter.LengthFilter(limit))
        textSize = 17f
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    companion object {
        private val BASE = Color.rgb(8, 10, 14)
        private val INK = Color.rgb(245, 245, 247)
        private val SECONDARY = Color.rgb(184, 187, 194)
    }
}
