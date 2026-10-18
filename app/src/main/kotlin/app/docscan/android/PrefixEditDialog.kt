package app.docscan.android

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal object PrefixEditDialog {

    fun show(
        context: Context,
        dialogHost: InsetDialogHost,
        initialPrefixes: List<String>,
        onSaved: (List<String>) -> Unit,
    ) {
        val workingList = initialPrefixes.toMutableList()
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            minimumHeight = dp(450)
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val titleView = TextView(context).apply {
            text = context.getString(R.string.prefix_edit_title)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF1E293B.toInt())
            setPadding(0, dp(4), 0, dp(16))
        }
        container.addView(titleView)

        // Top row: [input for new prefix] [+]
        val addRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val newPrefixInput = EditText(context).apply {
            setSingleLine(true)
            hint = context.getString(R.string.prefix_new_hint)
            setHintTextColor(0xFF94A3B8.toInt())
            textSize = 15f
            setTextColor(0xFF0F172A.toInt())
            background = GradientDrawable().apply {
                setColor(0xFFF1F5F9.toInt())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFFCBD5E1.toInt())
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val addBtn = TextView(context).apply {
            text = "+"
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF2563EB.toInt())
            background = GradientDrawable().apply {
                setColor(0xFFF1F5F9.toInt())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFFCBD5E1.toInt())
            }
        }

        addRow.addView(
            newPrefixInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            },
        )
        addRow.addView(addBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        container.addView(
            addRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            },
        )

        // Middle: Scrollable list of existing prefixes (+27% height: 200dp -> 254dp)
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        val scrollView = object : ScrollView(context) {
            private var downX = 0f
            private var downY = 0f

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        super.onInterceptTouchEvent(ev)
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = Math.abs(ev.rawX - downX)
                        val dy = Math.abs(ev.rawY - downY)
                        if (dy > touchSlop && dy > dx) {
                            return true
                        }
                    }
                }
                return super.onInterceptTouchEvent(ev)
            }

            override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                super.requestDisallowInterceptTouchEvent(false)
            }
        }.apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_ALWAYS
        }

        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            val focused = container.findFocus()
            if (focused is EditText && focused !== newPrefixInput) {
                focused.clearFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(container.windowToken, 0)
            }
        }

        val itemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(
            itemsContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // Height-constrained scroll area (+27% height: 254dp)
        container.addView(
            scrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(254)).apply {
                bottomMargin = dp(8)
            },
        )

        fun refreshList() {
            itemsContainer.removeAllViews()
            for (i in workingList.indices) {
                val itemRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val itemEdit = EditText(context).apply {
                    setSingleLine(true)
                    setText(workingList[i])
                    textSize = 15f
                    setTextColor(0xFF0F172A.toInt())
                    background = GradientDrawable().apply {
                        setColor(0xFFFFFFFF.toInt())
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), 0xFFE2E8F0.toInt())
                    }
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            if (i < workingList.size) {
                                workingList[i] = s?.toString().orEmpty()
                            }
                        }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                    setOnTouchListener { v, event ->
                        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                }

                val deleteBtn = TextView(context).apply {
                    text = "✕"
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(0xFFEF4444.toInt())
                    background = GradientDrawable().apply {
                        setColor(0xFFFEE2E2.toInt())
                        cornerRadius = dp(8).toFloat()
                    }
                    setOnClickListener {
                        if (i < workingList.size) {
                            workingList.removeAt(i)
                            refreshList()
                        }
                    }
                }

                itemRow.addView(
                    itemEdit,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(8)
                    },
                )
                itemRow.addView(deleteBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
                itemsContainer.addView(
                    itemRow,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8)
                    },
                )
            }
        }

        addBtn.setOnClickListener {
            val newText = newPrefixInput.text?.toString()?.trim().orEmpty()
            if (newText.isNotEmpty()) {
                if (!workingList.contains(newText)) {
                    workingList.add(newText)
                    refreshList()
                }
                newPrefixInput.setText("")
            }
        }

        refreshList()

        val dialog = dialogHost.builder(context)
            .setView(container)
            .setNegativeButton(R.string.prefix_cancel, null)
            .setPositiveButton(R.string.prefix_save) { _, _ ->
                val cleanList = workingList
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                FilePrefixPreferences(context).savePrefixes(cleanList)
                onSaved(cleanList)
            }
            .create()

        dialog.show()
    }
}
