package com.shellora.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val ui = Handler(Looper.getMainLooper())
    private var proc: Process? = null
    private val sb = StringBuilder()
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    private val rootfs by lazy { File(filesDir, "rootfs") }
    private val proot by lazy { File(applicationInfo.nativeLibraryDir, "libproot.so") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Thread { boot() }.start()
    }

    private fun buildUi() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        scroll = ScrollView(this)
        out = TextView(this).apply {
            setTextColor(Color.parseColor("#33FF66"))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(true)
        }
        scroll.addView(out)
        rootLayout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { a() }
        }
        bar.addView(btn("Clear") { sb.setLength(0); out.text = "" }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn("Stop (Ctrl+C)") { restartShell() }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn("Tab") { input.append("\t") }, LinearLayout.LayoutParams(0, -2, 1f))
        rootLayout.addView(bar)

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val prompt = TextView(this).apply {
            text = " # "; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE
        }
        input = EditText(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            hint = "command..."
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine()
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND ||
                    (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) { send(); true } else false
            }
        }
        row.addView(prompt)
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("Run") { send() })
        rootLayout.addView(row)
        setContentView(rootLayout)
    }

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
        print("# $cmd\n")
        try {
            proc?.outputStream?.apply { write((cmd + "\n").toByteArray()); flush() }
        } catch (e: Exception) { print("Shell closed. Press Stop to restart.\n") }
    }

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
        print("Ready. Try: ls, uname -a, apk add python3 git nano\n\n")
        startShell()
    }

    private fun download(u: String, dest: File) {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        val total = c.contentLength
        var done = 0; var lastPct = -10
        c.inputStream.use { i -> dest.outputStream().use { o ->
            val buf = ByteArray(16384)
            while (true) {
                val n = i.read(buf); if (n < 0) break
                o.write(buf, 0, n); done += n
                if (total > 0) {
                    val p = done * 100 / total
                    if (p >= lastPct + 25) { lastPct = p; print("  $p%\n") }
                }
            }
        } }
    }

    private fun startShell() {
        val tmp = File(cacheDir, "proot").apply { mkdirs() }
        val cmd = mutableListOf(
            proot.absolutePath, "--link2symlink", "-0", "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys"
        )
        if (File("/storage/emulated/0").exists()) { cmd += listOf("-b", "/storage/emulated/0:/sdcard") }
        cmd += listOf("-w", "/root", "/bin/sh", "-l")
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().apply {
            clear()
            put("HOME", "/root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("TERM", "dumb")
            put("PROOT_TMP_DIR", tmp.absolutePath)
            put("TMPDIR", "/tmp")
            put("LANG", "C.UTF-8")
        }
        try {
            val p = pb.start()
            proc = p
            Thread {
                val buf = ByteArray(4096)
                val i = p.inputStream
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    print(String(buf, 0, n))
                }
                print("\n[shell exited]\n")
            }.start()
        } catch (e: Exception) {
            print("Cannot start shell: ${e.message}\n")
        }
    }

    private fun restartShell() {
        proc?.destroy(); proc = null
        print("\n[restarting shell]\n")
        Thread { startShell() }.start()
    }

    override fun onDestroy() { proc?.destroy(); super.onDestroy() }
}
