package com.opengate.auto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** מסך הטלפון: ניהול רשימת השערים. לחיצה = פתיחה, לחיצה ארוכה = עריכה/מחיקה. */
class MainActivity : AppCompatActivity() {

    private val gates = mutableListOf<Gate>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())

        val empty = TextView(this).apply {
            text = "אין שערים עדיין.\nלחץ על \"הוסף שער\"."
            gravity = Gravity.CENTER
            setPadding(pad, pad * 3, pad, pad)
        }
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                GateStore.open(this@MainActivity, gates[pos]) { toast(it) }
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(gates[pos].name)
                    .setItems(arrayOf("עריכה", "מחיקה")) { _, which ->
                        if (which == 0) editGate(pos) else {
                            gates.removeAt(pos)
                            persist()
                        }
                    }
                    .show()
                true
            }
        }
        val add = Button(this).apply {
            text = "הוסף שער"
            setOnClickListener { editGate(-1) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(empty, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(add, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        list.emptyView = empty
        setContentView(root)

        gates.addAll(GateStore.load(this))
        refresh()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 1)
        }
    }

    /** pos = -1 להוספת שער חדש. */
    private fun editGate(pos: Int) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val existing = gates.getOrNull(pos)
        val name = EditText(this).apply {
            hint = "שם השער"
            setSingleLine()
            setText(existing?.name ?: "")
        }
        val target = EditText(this).apply {
            hint = "מספר טלפון או כתובת http://"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.target ?: "")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(name)
            addView(target)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "שער חדש" else "עריכת שער")
            .setView(box)
            .setPositiveButton("שמור") { _, _ ->
                val n = name.text.toString().trim()
                val t = target.text.toString().trim()
                if (n.isEmpty() || t.isEmpty()) {
                    toast("חובה למלא שם ויעד")
                } else {
                    if (existing == null) gates.add(Gate(n, t)) else gates[pos] = Gate(n, t)
                    persist()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun persist() {
        GateStore.save(this, gates)
        refresh()
    }

    private fun refresh() {
        adapter.clear()
        adapter.addAll(gates.map { "${it.name}\n${it.target}" })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
