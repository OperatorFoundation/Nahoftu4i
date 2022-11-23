package org.nahoft.nahoft

import android.app.Activity
import android.content.Context
import android.text.Layout
import android.text.SpannableString
import android.text.method.ScrollingMovementMethod
import android.text.style.AlignmentSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.viewpager.widget.PagerAdapter
import kotlinx.android.synthetic.main.slide_screen.view.*
import org.nahoft.nahoft.activities.SlideActivity

class SlideViewPagerAdapter(private val context: Context, private val slideList: ArrayList<Slide>) : PagerAdapter() {
    override fun getCount(): Int {
        return slideList.size
    }

    override fun isViewFromObject(view: View, `object`: Any): Boolean {
        return view == `object`
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val view = LayoutInflater.from(context).inflate(R.layout.slide_screen, container, false)

        val currentSlide = slideList[position]
        view.main_image_view.setImageResource(currentSlide.image)
        view.title_text_view.text = currentSlide.title
        view.description_text_view.text = currentSlide.description
        view.prev_button.isVisible = position != 0
        view.next_button.isVisible = position != slideList.size - 1
        view.get_started_button.text = currentSlide.skipButtonText
        view.read_more_button.isInvisible = currentSlide.fullDescription.isNullOrEmpty()

        setIndicatorColor(view, position)

        view.next_button.setOnClickListener {
            SlideActivity.viewPager?.currentItem = position + 1
        }

        view.prev_button.setOnClickListener {
            SlideActivity.viewPager?.currentItem = position - 1
        }

        view.get_started_button.setOnClickListener {
            (context as Activity).finish()
        }

        view.read_more_button.setOnClickListener {
            currentSlide.fullDescription?.let { desc ->
                showFullDescription(context, currentSlide.title, desc)
            }
        }

        container.addView(view)
        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        container.removeView(`object` as View)
    }

    private fun setIndicatorColor(view: View, activeInd: Int) {
        val params: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, 0, 8, 0)
        params.width = 40
        params.height = 40

        for (i in 0 until slideList.size) {
            val ind = ImageView(view.context)
            ind.setImageResource(if (i == activeInd) R.drawable.active_slide_indicator else R.drawable.unactive_slide_indicator)
            ind.layoutParams = params
            view.indicators_container.addView(ind)
        }
    }

    private fun showFullDescription(context: Context, title: String, body: String) {
        val builder: AlertDialog.Builder = AlertDialog.Builder(context, R.style.AppTheme_AlertDialog)
        val title = SpannableString(title)

        // alert dialog title align center
        title.setSpan(
            AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
            0,
            title.length,
            0
        )
        builder.setTitle(title)

        val inputTextView = TextView(context)
        inputTextView.textAlignment = View.TEXT_ALIGNMENT_CENTER
        inputTextView.setPadding(15)
        inputTextView.text = body
        inputTextView.textSize = 16F
        inputTextView.movementMethod = ScrollingMovementMethod()
        inputTextView.setTextColor(ContextCompat.getColor(context, R.color.black))
        builder.setView(inputTextView)

        builder.setNeutralButton(context.getString(R.string.close)) { dialog, _->
            dialog.cancel()
        }.create().show()
    }
}