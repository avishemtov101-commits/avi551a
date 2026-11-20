package com.opengate.auto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** מסך הטלפון: רשימת שערים + הגדרות/התחברות. */
class MainActivity : AppCompatActivity() {

    private val gates = mutableListOf<Gate>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())

        val empty = TextView(this).apply {
            text = "אין שערים עדיין.\nלחץ על \"הוסף שער\"."
            gravity = Gravity.CENTER
            setPadding(pad, pad * 3, pad, pad)
        }
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> GateStore.open(this@MainActivity, gates[pos]) { toast(it) } }
            setOnItemLongClickListener { _, _, pos, _ ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(gates[pos].name)
                    .setItems(arrayOf("עריכה", "מחיקה")) { _, which ->
                        if (which == 0) editGate(pos) else { gates.removeAt(pos); persist() }
                    }.show()
                true
            }
        }
        val add = Button(this).apply { text = "הוסף שער"; setOnClickListener { editGate(-1) } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(empty, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(add, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        list.emptyView = empty
        setContentView(root)

        gates.addAll(GateStore.load(this)); refresh()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 1)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "הגדרות / התחברות")
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { settingsDialog(); return true }
        return super.onOptionsItemSelected(item)
    }

    /** pos = -1 להוספת שער חדש. */
    private fun editGate(pos: Int) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val existing = gates.getOrNull(pos)
        val name = EditText(this).apply { hint = "שם השער"; setSingleLine(); setText(existing?.name ?: "") }
        val apiMode = CheckBox(this).apply {
            text = "פתיחה דרך OpenGate API"
            isChecked = existing?.isApi ?: true
        }
        val target = EditText(this).apply {
            setSingleLine()
            setText(existing?.let { if (it.isApi) it.gateId else it.target } ?: "")
        }
        fun syncHint() { target.hint = if (apiMode.isChecked) "gateId (מזהה השער)" else "מספר טלפון או כתובת http://" }
        syncHint(); apiMode.setOnCheckedChangeListener { _, _ -> syncHint() }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
            addView(name); addView(apiMode); addView(target)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "שער חדש" else "עריכת שער")
            .setView(box)
            .setPositiveButton("שמור") { _, _ ->
                val n = name.text.toString().trim(); val t0 = target.text.toString().trim()
                if (n.isEmpty() || t0.isEmpty()) { toast("חובה למלא שם ויעד"); return@setPositiveButton }
                val t = if (apiMode.isChecked) "api:$t0" else t0
                if (existing == null) gates.add(Gate(n, t)) else gates[pos] = Gate(n, t)
                persist()
            }
            .setNegativeButton("ביטול", null).show()
    }

    private fun settingsDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val base = EditText(this).apply { setSingleLine(); setText(GateStore.baseUrl(this@MainActivity)); hint = "כתובת שרת" }
        val token = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(GateStore.token(this@MainActivity)); hint = "Token (הדבקה ידנית)"
        }
        val phone = EditText(this).apply { inputType = InputType.TYPE_CLASS_PHONE; hint = "מספר טלפון להתחברות" }
        val code = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "קוד מה-SMS" }
        val sendBtn = Button(this).apply {
            text = "שלח קוד SMS"
            setOnClickListener {
                GateStore.setBaseUrl(this@MainActivity, base.text.toString())
                val p = phone.text.toString().trim()
                if (p.isEmpty()) toast("הזן מספר טלפון") else GateStore.sendSmsCode(this@MainActivity, p) { _, m -> toast(m) }
            }
        }
        val verifyBtn = Button(this).apply {
            text = "אמת קוד והתחבר"
            setOnClickListener {
                val p = phone.text.toString().trim(); val c = code.text.toString().trim()
                if (p.isEmpty() || c.isEmpty()) { toast("הזן טלפון וקוד"); return@setOnClickListener }
                GateStore.verifySmsCode(this@MainActivity, p, c) { ok, m ->
                    toast(m); if (ok) token.setText(GateStore.token(this@MainActivity))
                }
            }
        }
        val loadBtn = Button(this).apply {
            text = "טען שערים מהשרת"
            setOnClickListener {
                GateStore.setBaseUrl(this@MainActivity, base.text.toString())
                GateStore.setToken(this@MainActivity, token.text.toString())
                GateStore.fetchGates(this@MainActivity) { ok, fetched, m ->
                    toast(m)
                    if (ok && fetched != null) {
                        val existing = gates.map { it.target }.toMutableSet()
                        fetched.forEach { if (it.target !in existing) { gates.add(it); existing.add(it.target) } }
                        persist()
                    }
                }
            }
        }
        fun lbl(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
            addView(lbl("שרת:")); addView(base)
            addView(lbl("התחברות ב-SMS:")); addView(phone); addView(sendBtn); addView(code); addView(verifyBtn)
            addView(lbl("או Token ידני:")); addView(token); addView(loadBtn)
        }
        val scroll = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this)
            .setTitle("הגדרות / התחברות")
            .setView(scroll)
            .setPositiveButton("שמור") { _, _ ->
                GateStore.setBaseUrl(this, base.text.toString())
                GateStore.setToken(this, token.text.toString())
                toast("נשמר")
            }
            .setNegativeButton("סגור", null).show()
    }

    private fun persist() { GateStore.save(this, gates); refresh() }
    private fun refresh() {
        adapter.clear()
        adapter.addAll(gates.map { g ->
            val kind = when { g.isApi -> "OpenGate API"; g.isHttp -> "כתובת רשת"; else -> g.target }
            "${g.name}\n$kind"
        })
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
