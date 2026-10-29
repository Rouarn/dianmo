package com.woyuxiaji.dianmo.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.woyuxiaji.dianmo.R

/**
 * 和纸风格弹窗：米白纸底、墨线、朱印按钮。
 * 替代系统 AlertDialog，避免默认样式破坏主题。
 */
class WashiDialog(
    context: Context,
    title: String,
    message: String? = null,
    positiveText: String = "确定",
    onPositive: ((String?) -> Unit)? = null,
    negativeText: String? = null,
    onNegative: (() -> Unit)? = null,
    neutralText: String? = null,
    onNeutral: (() -> Unit)? = null,
    inputHint: String? = null,
    inputValue: String? = null,
    inputType: Int = android.text.InputType.TYPE_CLASS_TEXT,
    overlayType: Int = 0,
) : Dialog(context, R.style.Theme_DianMo_Dialog) {

    private val input: EditText

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_washi)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
            if (overlayType != 0) {
                setType(overlayType)
            }
            // 宽度限制，避免横屏时拉太宽
            val dm = context.resources.displayMetrics
            val w = minOf(dm.widthPixels - (48 * dm.density).toInt(), (420 * dm.density).toInt())
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
            // 键盘弹出时整体上浮，避免输入框被遮挡
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        }

        findViewById<TextView>(R.id.dlgTitle).text = title

        val msgView = findViewById<TextView>(R.id.dlgMessage)
        if (message.isNullOrEmpty()) {
            msgView.visibility = View.GONE
        } else {
            msgView.visibility = View.VISIBLE
            msgView.text = message
        }

        input = findViewById(R.id.dlgInput)
        if (inputHint != null || inputValue != null) {
            input.visibility = View.VISIBLE
            input.hint = inputHint ?: ""
            input.inputType = inputType
            if (!inputValue.isNullOrEmpty()) {
                input.setText(inputValue)
                input.setSelection(input.text.length)
            }
        } else {
            input.visibility = View.GONE
        }

        val pos = findViewById<TextView>(R.id.dlgPositive)
        if (positiveText.isEmpty()) {
            pos.visibility = View.GONE
        } else {
            pos.text = positiveText
            pos.setOnClickListener {
                val text = if (input.visibility == View.VISIBLE) input.text.toString() else null
                onPositive?.invoke(text)
                dismiss()
            }
        }

        val neg = findViewById<TextView>(R.id.dlgNegative)
        if (negativeText == null) {
            neg.visibility = View.GONE
        } else {
            neg.visibility = View.VISIBLE
            neg.text = negativeText
            neg.setOnClickListener {
                onNegative?.invoke()
                dismiss()
            }
        }

        val neu = findViewById<TextView>(R.id.dlgNeutral)
        if (neutralText == null) {
            neu.visibility = View.GONE
        } else {
            neu.visibility = View.VISIBLE
            neu.text = neutralText
            neu.setOnClickListener {
                onNeutral?.invoke()
                dismiss()
            }
        }
    }
}
