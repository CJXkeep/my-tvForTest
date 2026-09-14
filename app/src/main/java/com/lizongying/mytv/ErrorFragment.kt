package com.lizongying.mytv

import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.ErrorSupportFragment

class ErrorFragment : ErrorSupportFragment() {

    internal fun setErrorContent(message: String) {
        render(message)
        buttonText = resources.getString(R.string.dismiss_error)
        buttonClickListener = View.OnClickListener { dismiss() }
    }

    /**
     * 源疑似失效时的提示。
     *
     * 只有一个按钮（Leanback 默认会聚焦它），家里老人按一次 OK 就能换源；
     * 不用系统弹窗是因为电视上按钮焦点不可控，而这个页面的焦点一定落在按钮上。
     */
    internal fun setSwitchSourceContent(message: String, buttonLabel: String, onConfirm: () -> Unit) {
        render(message)
        buttonText = buttonLabel
        buttonClickListener = View.OnClickListener {
            dismiss()
            onConfirm()
        }
    }

    private fun render(message: String) {
        imageDrawable =
            ContextCompat.getDrawable(context!!, androidx.leanback.R.drawable.lb_ic_sad_cloud)
        this.message = message
        setDefaultBackground(TRANSLUCENT)
        backgroundDrawable = ContextCompat.getDrawable(
            context!!,
            R.color.black
        )
    }

    private fun dismiss() {
        fragmentManager?.beginTransaction()?.remove(this@ErrorFragment)?.commit()
    }

    companion object {
        private const val TRANSLUCENT = false
    }
}