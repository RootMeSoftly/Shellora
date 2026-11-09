package com.shellora.app

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var rootFrame: FrameLayout
    private lateinit var main: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var menuBtn: TextView
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var scroll: ScrollView
    private lateinit var out: TextView
    private lateinit var keysArea: LinearLayout
    private lateinit var inputRow: LinearLayout
    private lateinit var prompt: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var panel: ScrollView
    private lateinit var panelBox: LinearLayout
    private lateinit var scrim: View
    private lateinit var ctrlKey: TextView
    private val keyViews = mutableListOf<TextView>()

    private val ui = Handler(Looper.getMainLooper())
    private var proc: Process? = null
    private val sb = StringBuilder()
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val hist = mutableListOf<String>()
    private var histIdx = 0
    private var ctrlOn = false
    private var panelOpen = false
    private var downX = 0f
    private var downY = 0f
    private var tracking = false

    private lateinit var prefs: SharedPreferences
    private var bg = Color.parseColor("#0D1117")
    private var fg = Color.parseColor("#7EE787")
    private var fontSize = 13

    private val bgColors = listOf("#0D1117", "#000000", "#1E1E2E", "#002B36",
        "#282A36", "#1B2B34", "#FFFFFF", "#F5F0E6")
    private val fgColors = listOf("#7EE787", "#33FF66", "#FFFFFF", "#00E5FF",
        "#FFB86C", "#F1FA8C", "#FF79C6", "#222222")

    private val rootfs by lazy { File(filesDir, "rootfs") }
    private val proot by lazy { File(applicationInfo.nativeLibraryDir, "libproot.so") }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun blend(a: Int, b: Int, r: Float): Int {
        val i = 1 - r
        return Color.rgb(
            (Color.red(a) * i + Color.red(b) * r).toInt(),
            (Color.green(a) * i + Color.green(b) * r).toInt(),
            (Color.blue(a) * i + Color.blue(b) * r).toInt()
        )
    }

    private fun isLight(c: Int) =
        (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 150

    private fun surface() =
        if (isLight(bg)) blend(bg, Color.BLACK, 0.08f) else blend(bg, Color.WHITE, 0.10f)

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("shellora", MODE_PRIVATE)
        bg = prefs.getInt("bg", bg)
        fg = prefs.getInt("fg", fg)
        fontSize = prefs.getInt("font", fontSize)
        buildUi()
        applyTheme()
        Thread { boot() }.start()
    }

    override fun onBackPressed() {
        if (panelOpen) closePanel() else super.onBackPressed()
    }

    override fun onDestroy() {
        val p = proc; proc = null
        p?.destroy()
        super.onDestroy()
    }

    // ---------- swipe from left to open settings ----------
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; tracking = true }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val w = resources.displayMetrics.widthPixels
                if (!panelOpen && downX < w * 0.25f && dx > dp(50) &&
                    Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    openPanel(); tracking = false
                } else if (panelOpen && dx < -dp(60) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    closePanel(); tracking = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------- UI ----------
    private fun buildUi() {
        rootFrame = FrameLayout(this)
        main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // top bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(16), dp(4))
        }
        menuBtn = TextView(this).apply {
            text = "☰"; textSize = 22f; gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { togglePanel() }
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply {
            text = "Shellora"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        }
        sub = TextView(this).apply { text = "Alpine Linux  •  rootless"; textSize = 11f }
        titleBox.addView(title)
        titleBox.addView(sub)
        topBar.addView(menuBtn)
        topBar.addView(titleBox)
        main.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        // output
        scroll = ScrollView(this)
        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        scroll.addView(out)
        main.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // extra keys
        keysArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        keysArea.addView(keyRow(listOf("CTRL", "TAB", "↑", "↓", "←", "→")))
        keysArea.addView(keyRow(listOf("/", "-", "|", "~", "_", "$", ">", "&")))
        main.addView(keysArea, LinearLayout.LayoutParams(-1, -2))

        // input row
        inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(6), dp(4))
        }
        prompt = TextView(this).apply {
            text = "$"; textSize = 16f; typeface = Typeface.MONOSPACE
        }
        input = EditText(this).apply {
            background = null
            typeface = Typeface.MONOSPACE
            textSize = 15f
            hint = "type a command"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine()
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            var pos = -1
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                if (ctrlOn && b == 0 && c == 1) pos = st
            }
            override fun afterTextChanged(e: Editable?) {
                if (pos >= 0 && e != null && pos < e.length) {
                    val p = pos; pos = -1
                    val ch = e[p].lowercaseChar()
                    ctrlOn = false; refreshCtrl()
                    if (ch in 'a'..'z') {
                        e.delete(p, p + 1)
                        when (ch) {
                            'c' -> restartShell()
                            'l' -> clearScreen()
                            'u' -> e.clear()
                        }
                    }
                } else pos = -1
            }
        })
        sendBtn = TextView(this).apply {
            text = "➤"; textSize = 18f; gravity = Gravity.CENTER
            setOnClickListener { send() }
        }
        inputRow.addView(prompt)
        inputRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        main.addView(inputRow, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(8), dp(6), dp(8), dp(8))
        })

        // settings panel
        scrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            setOnClickListener { closePanel() }
        }
        panelBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        panel = ScrollView(this).apply {
            addView(panelBox)
            translationX = -dp(290).toFloat()
            elevation = dp(16).toFloat()
        }

        rootFrame.addView(main, FrameLayout.LayoutParams(-1, -1))
        rootFrame.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        rootFrame.addView(panel, FrameLayout.LayoutParams(dp(290), -1))
        setContentView(rootFrame)
    }

    private fun keyRow(labels: List<String>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEach { l ->
            val k = TextView(this).apply {
                text = l; textSize = 14f; gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setOnClickListener { keyPress(l) }
            }
            if (l == "CTRL") ctrlKey = k
            keyViews.add(k)
            row.addView(k, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        return row
    }

    private fun refreshCtrl() {
        val surf = surface()
        ctrlKey.background = pill(if (ctrlOn) fg else surf, 8)
        ctrlKey.setTextColor(if (ctrlOn) bg else fg)
    }

    private fun applyTheme() {
        val surf = surface()
        val dim = blend(fg, bg, 0.45f)
        rootFrame.setBackgroundColor(bg)
        main.setBackgroundColor(bg)
        topBar.setBackgroundColor(surf)
        menuBtn.setTextColor(fg)
        title.setTextColor(fg)
        sub.setTextColor(dim)
        out.setTextColor(fg)
        out.textSize = fontSize.toFloat()
        keyViews.forEach { it.setTextColor(fg); it.background = pill(surf, 8) }
        refreshCtrl()
        inputRow.background = pill(surf, 24)
        prompt.setTextColor(dim)
        input.setTextColor(fg)
        input.setHintTextColor(dim)
        sendBtn.setTextColor(bg)
        sendBtn.background = pill(fg, 21)
        panel.setBackgroundColor(blend(surf, if (isLight(bg)) Color.WHITE else Color.BLACK, 0.25f))
        window.statusBarColor = surf
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 23) {
            var f = window.decorView.systemUiVisibility
            f = if (isLight(surf)) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            window.decorView.systemUiVisibility = f
        }
        buildPanel()
    }

    // ---------- settings panel ----------
    private fun togglePanel() { if (panelOpen) closePanel() else openPanel() }

    private fun openPanel() {
        if (panelOpen) return
        panelOpen = true
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(180).start()
        panel.animate().translationX(0f).setDuration(220).start()
    }

    private fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        scrim.animate().alpha(0f).setDuration(180).withEndAction { scrim.visibility = View.GONE }.start()
        panel.animate().translationX(-dp(290).toFloat()).setDuration(200).start()
    }

    private fun buildPanel() {
        panelBox.removeAllViews()
        val pbg = blend(surface(), if (isLight(bg)) Color.WHITE else Color.BLACK, 0.25f)
        val txt = if (isLight(pbg)) Color.BLACK else Color.WHITE
        val soft = blend(txt, pbg, 0.45f)
        val accent = Color.parseColor("#2F81F7")

        fun label(t: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) =
            TextView(this).apply {
                text = t; textSize = size; setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(top), 0, dp(6))
            }

        fun grid(colors: List<String>, current: Int, pick: (Int) -> Unit): LinearLayout {
            val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            colors.chunked(4).forEach { chunk ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { hex ->
                    val c = Color.parseColor(hex)
                    val v = View(this).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(c)
                            setStroke(dp(if (c == current) 3 else 1), if (c == current) accent else soft)
                        }
                        setOnClickListener { pick(c) }
                    }
                    row.addView(v, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    })
                }
                g.addView(row)
            }
            return g
        }

        fun button(t: String, action: () -> Unit) = TextView(this).apply {
            text = t; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(txt)
            background = pill(blend(pbg, txt, 0.12f), 10)
            setOnClickListener { action() }
        }

        panelBox.addView(label("Settings", 22f, txt, true))
        panelBox.addView(label("Customize your terminal", 12f, soft))

        panelBox.addView(label("Background", 13f, soft, true, 18))
        panelBox.addView(grid(bgColors, bg) { c ->
            bg = c; prefs.edit().putInt("bg", c).apply(); applyTheme()
        })

        panelBox.addView(label("Text color", 13f, soft, true, 14))
        panelBox.addView(grid(fgColors, fg) { c ->
            fg = c; prefs.edit().putInt("fg", c).apply(); applyTheme()
        })

        panelBox.addView(label("Font size: $fontSize", 13f, soft, true, 14))
        val seek = SeekBar(this).apply {
            max = 12; progress = fontSize - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    fontSize = 10 + p
                    out.textSize = fontSize.toFloat()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    prefs.edit().putInt("font", fontSize).apply(); buildPanel()
                }
            })
        }
        panelBox.addView(seek)

        val bp = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) }
        panelBox.addView(label("Actions", 13f, soft, true, 18))
        panelBox.addView(button("Clear screen") { clearScreen(); closePanel() }, bp)
        panelBox.addView(button("Restart shell") { restartShell(); closePanel() }, bp)
        panelBox.addView(button("Reset theme") {
            bg = Color.parseColor("#0D1117"); fg = Color.parseColor("#7EE787"); fontSize = 13
            prefs.edit().putInt("bg", bg).putInt("fg", fg).putInt("font", fontSize).apply()
            applyTheme()
        }, bp)

        panelBox.addView(label("Shellora 1.1  •  Alpine Linux", 11f, soft, false, 24))
    }

    // ---------- keys ----------
    private fun keyPress(k: String) {
        when (k) {
            "CTRL" -> { ctrlOn = !ctrlOn; refreshCtrl() }
            "TAB" -> insert("\t")
            "↑" -> histMove(-1)
            "↓" -> histMove(1)
            "←" -> input.setSelection((input.selectionStart.coerceAtLeast(0) - 1).coerceAtLeast(0))
            "→" -> input.setSelection((input.selectionStart.coerceAtLeast(0) + 1).coerceAtMost(input.text.length))
            else -> insert(k)
        }
    }

    private fun insert(t: String) {
        val p = input.selectionStart.coerceAtLeast(0)
        input.text.insert(p, t)
    }

    private fun histMove(d: Int) {
        if (hist.isEmpty()) return
        histIdx = (histIdx + d).coerceIn(0, hist.size)
        if (histIdx == hist.size) input.setText("")
        else { input.setText(hist[histIdx]); input.setSelection(input.text.length) }
    }

    private fun clearScreen() { sb.setLength(0); out.text = "" }

    // ---------- terminal output ----------
    private fun print(s: String) {
        ui.post {
            sb.append(ansi.replace(s.replace("\r", ""), ""))
            if (sb.length > 60000) sb.delete(0, sb.length - 40000)
            out.text = sb
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun send() {
        val cmd = input.text.toString()
        input.setText("")
        if (cmd.isNotBlank()) { hist.add(cmd); histIdx = hist.size }
        print("# $cmd\n")
        val p = proc
        if (p == null) {
            print("Shell is not running. Open menu → Restart shell.\n"); return
        }
        try {
            p.outputStream.apply { write((cmd + "\n").toByteArray()); flush() }
        } catch (e: Exception) {
            print("Shell closed. Open menu → Restart shell.\n")
        }
    }

    // ---------- Linux setup ----------
    private fun boot() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        val arch = when {
            abi.startsWith("arm64") -> "aarch64"
            abi.startsWith("armeabi") -> "armv7"
            else -> { print("Unsupported CPU: $abi\n"); return }
        }
        if (!File(rootfs, "bin/busybox").exists()) {
            print("Shellora: first-time setup (~3 MB download)...\n")
            val url = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/$arch/alpine-minirootfs-3.20.3-$arch.tar.gz"
            val tar = File(cacheDir, "rootfs.tar.gz")
            try {
                download(url, tar)
                rootfs.mkdirs()
                print("Extracting...\n")
                ProcessBuilder("tar", "-xzf", tar.absolutePath, "-C", rootfs.absolutePath)
                    .redirectErrorStream(true).start().waitFor()
                tar.delete()
            } catch (e: Exception) {
                print("Setup failed: ${e.message}\nCheck internet and reopen the app.\n"); return
            }
            if (!File(rootfs, "bin/busybox").exists()) { print("Extract failed.\n"); return }
        }
        File(rootfs, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(rootfs, "tmp").mkdirs()
        if (!proot.exists()) { print("ERROR: proot missing: ${proot.path}\n"); return }
        print("Ready. Try: ls, uname -a, apk add python3 git nano\n\n")
        startShell(false)
    }

    private fun download(u: String, dest: File) {
        val c = URL(u).openConnection() as HttpURLConnection
      
