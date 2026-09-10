package com.lizongying.mytv

import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.lizongying.mytv.models.TVViewModel

class CardPresenter(
    private val context: Context,
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = object :
            ImageCardView(ContextThemeWrapper(parent.context, R.style.CustomImageCardTheme)) {}

        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val tvViewModel = item as TVViewModel
        val cardView = viewHolder.view as ImageCardView

        cardView.titleText = tvViewModel.getTV().title
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        cardView.tag = tvViewModel.videoUrl.value

        cardView.mainImageView?.let {
            Glide.with(viewHolder.view.context)
                .load(logoUrl(tvViewModel))
                .centerInside()
                .into(it)
        }

        cardView.setBackgroundColor(Color.WHITE)
        cardView.setMainImageScaleType(ImageView.ScaleType.CENTER_INSIDE)

//        cardView.setOnFocusChangeListener { v, hasFocus ->
//            run {
//                if (hasFocus) {
//                    if (v != null) {
//                        (v as ImageCardView).setInfoAreaBackgroundColor(context.resources.getColor(R.color.focus))
//                    }
//                } else {
//                    if (v != null) {
//                        (v as ImageCardView).setInfoAreaBackgroundColor(context.resources.getColor(R.color.ic_launcher_background))
//                    }
//                }
//            }
//        }

        val epg = tvViewModel.epg.value?.filter { it.beginTime < Utils.getDateTimestamp() }
        if (!epg.isNullOrEmpty()) {
            cardView.contentText = epg.last().title
        } else {
            cardView.contentText = ""
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }

    /** LOGO 地址：源未提供时用 fanmingming 图标库兜底（Glide 自带磁盘缓存） */
    private fun logoUrl(tvViewModel: TVViewModel): Any {
        val logo = tvViewModel.getTV().logo
        val s = logo?.toString() ?: ""
        if (s.isNotBlank() && s != "null") return logo
        val name = TVList.canonicalName(tvViewModel.getTV().title)
        return "https://live.fanmingming.cn/tv/$name.png"
    }

    companion object {
        private const val TAG = "CardPresenter"
        private const val CARD_WIDTH = 300
        private const val CARD_HEIGHT = 101
    }
}