package com.woyuxiaji.dianmo.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.woyuxiaji.dianmo.R

/**
 * 和纸风格的列表选择弹窗（替代 AlertDialog.setItems）。
 */
class WashiMenuDialog(
    context: Context,
    title: String,
    items: List<Pair<String, String?>>,  // (主文案, 次要说明)
    onSelect: (Int) -> Unit,
    subtitle: String? = null,
    overlayType: Int = 0,
) : Dialog(context, R.style.Theme_DianMo_Dialog) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_menu)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
            if (overlayType != 0) setType(overlayType)
            val dm = context.resources.displayMetrics
            val w = minOf(dm.widthPixels - (48 * dm.density).toInt(), (440 * dm.density).toInt())
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        findViewById<TextView>(R.id.menuTitle).text = title

        val sub = findViewById<TextView>(R.id.menuSubtitle)
        if (subtitle.isNullOrEmpty()) {
            sub.visibility = android.view.View.GONE
        } else {
            sub.visibility = android.view.View.VISIBLE
            sub.text = subtitle
        }

        val container = findViewById<LinearLayout>(R.id.menuContainer)
        val density = context.resources.displayMetrics.density

        items.forEachIndexed { index, (label, desc) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
                isClickable = true
                setBackgroundResource(R.drawable.bg_stamp_ripple)
            }
            val name = TextView(context).apply {
                text = label
                setTextColor(context.getColor(R.color.ink))
                textSize = 15f
                typeface = android.graphics.Typeface.SERIF
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(name)

            if (!desc.isNullOrEmpty()) {
                val d = TextView(context).apply {
                    text = desc
                    setTextColor(context.getColor(R.color.ink_secondary))
                    textSize = 12f
                    typeface = android.graphics.Typeface.SERIF
                }
                row.addView(d)
            }

            row.setOnClickListener {
                onSelect(index)
                dismiss()
            }
            container.addView(row)

            if (index != items.lastIndex) {
                val line = android.view.View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
                    )
                    setBackgroundColor(context.getColor(R.color.ink_line))
                }
                container.addView(line)
            }
        }

        findViewById<TextView>(R.id.menuCancel).setOnClickListener { dismiss() }
    }
}
