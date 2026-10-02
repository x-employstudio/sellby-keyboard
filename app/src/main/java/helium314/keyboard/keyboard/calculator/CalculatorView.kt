// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.calculator

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.TextViewCompat
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.brightenOrDarken
import helium314.keyboard.latin.utils.isDarkColor
import java.util.Locale
import kotlin.math.abs

/**
 * Standalone calculator panel, shown in place of the main keyboard (same View-swap mechanism as
 * EmojiPalettesView/ClipboardHistoryView). Its own keys never touch InputConnection directly -
 * they only update the local expression/result state, mirroring sellby_keyboard.dart's
 * _onCalcKeyPress/_calculateExpression. Only the dedicated "insert result" button commits text,
 * via the normal KeyboardActionListener, exactly like the reference Flutter implementation.
 */
class CalculatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyle: Int = R.attr.calculatorViewStyle
) : LinearLayout(context, attrs, defStyle) {

    private lateinit var expressionView: TextView
    private lateinit var resultView: TextView
    private lateinit var keyboardActionListener: KeyboardActionListener

    private var initialized = false
    private var expression = ""
    private var result = "0"

    private val handler = Handler(Looper.getMainLooper())
    private var backspaceRepeatRunnable: Runnable? = null

    init {
        orientation = VERTICAL
        fitsSystemWindows = true
        // Horizontal breathing room between the button grid and the screen edges: NOT done via
        // CalculatorView's own padding - 2 separate attempts (XML android:paddingStart/End, then
        // setPaddingRelative() imperatively right here) both had zero visible effect on device
        // despite being textbook-correct, almost certainly neutralized somewhere in
        // calculatorViewStyle's theme resolution. The margin that actually works is declared per-row
        // in calculator_view.xml instead (android:layout_marginStart/End on each of the 4 row
        // elements) - a LayoutParams property on the CHILD rows, a completely different mechanism
        // from CalculatorView's own padding, unaffected by whatever was resetting that.
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Since Round 13 the display row lives in the strip above the keyboard, not in this panel
        // - it's just 4 button rows now, same as Numpad, so it should match Numpad's height too.
        // Height is forced EXACTLY (needed so LinearLayout can resolve row1-4's layout_weight - a
        // wrap_content parent gives no natural height otherwise).
        // Width, unlike height, is NOT recomputed here anymore - it used to be re-derived
        // independently via ResourceUtils.getKeyboardWidth(), a SECOND source of truth parallel to
        // whatever the parent (keyboard_view_wrapper, this view's own XML already declares
        // layout_width="match_parent") already measured it to be. Numpad/the regular keyboard never
        // override onMeasure for width at all - they just inherit match_parent from that same
        // correctly-inset parent, and that's reportedly fine across devices. Having two independent
        // width calculations let them drift apart on some devices (different insets/display
        // cutouts/rounded corners) - confirmed by a user report: fine on their own phone, keys
        // overflowing past the screen edge on another. Just passing widthMeasureSpec through
        // unchanged makes width always match the parent exactly, same mechanism as Numpad.
        val res = context.resources
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, heightSpec)
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun initialize() {
        if (initialized) return
        initialized = true
        val keyTextColor = Settings.getValues().mColors.get(ColorType.KEY_TEXT)

        // The expression/result display and insert button live in the suggestion/toolbar strip
        // above the keyboard (calculator_strip in strip_container.xml), not inside this panel -
        // reusing that existing bar instead of adding a taller display row here, same idea as how
        // EmojiPalettesView reaches into KeyboardSwitcher's clipboardStrip for its own tab row.
        val strip = KeyboardSwitcher.getInstance().calculatorStrip
        expressionView = strip.findViewById(R.id.calc_expression)
        resultView = strip.findViewById(R.id.calc_result)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            expressionView, 11, 40, 1, TypedValue.COMPLEX_UNIT_SP)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            resultView, 9, 15, 1, TypedValue.COMPLEX_UNIT_SP)
        strip.findViewById<View>(R.id.calc_insert_result).apply {
            // Fixed teal pill, same as Enter's actual accent color, in both light and dark mode.
            background = pillDrawable(ContextCompat.getColor(context, R.color.calculator_accent))
            setOnClickListener {
                if (result.isNotEmpty() && result != "Error" && result != "...") {
                    keyboardActionListener.onTextInput(result)
                }
            }
        }

        // All 4 rows are flat <LinearLayout> containers (calculator_view.xml) - every key, plain
        // and specially-styled alike (AC/backspace/ABC+comma/=), is built and addView()'d here in
        // exact on-screen column order. "(", ")" and "+/-" were dropped entirely (per request) and
        // "," was merged into the ABC key (tap = switch to alphabet, hold = insert ",") instead of
        // keeping its own column - 5 columns per row now, not 6.
        val row1 = findViewById<LinearLayout>(R.id.calc_row1)
        if (row1.childCount == 0) {
            listOf("1", "2", "3").forEach { row1.addView(createKey(it, keyTextColor)) }
            row1.addView(buildAcKey())
            row1.addView(buildBackspaceKey(keyTextColor))
        }
        buildRow(findViewById(R.id.calc_row2), listOf("4", "5", "6", "×", "÷"), keyTextColor)
        buildRow(findViewById(R.id.calc_row3), listOf("7", "8", "9", "+", "−"), keyTextColor)
        val row4 = findViewById<LinearLayout>(R.id.calc_row4)
        if (row4.childCount == 0) {
            row4.addView(buildAbcCommaKey())
            listOf("0", "000", "%").forEach { row4.addView(createKey(it, keyTextColor)) }
            row4.addView(buildEqualsKey())
        }

        // Match Numpad's font size instead of this view's own fixed sp literals - Numpad (a real
        // Key/KeyboardView layout) sizes each key's text via Key.selectTextSize() (Key.java:570-577):
        // single-character labels (digits, operators, "%", backspace, "=") use mLetterSize
        // (config_key_letter_ratio_lxx, 43% of the key's own height), but labels with MORE than 1
        // character ("000", "AC") use the SMALLER mLabelSize
        // (config_key_label_ratio_lxx, 28%) instead - a single uniform ratio for every button (the
        // first attempt here) made multi-character labels like "ABC" render oversized. All 4 rows
        // share layout_weight="1" on the same vertical LinearLayout root, so they're always exactly
        // the same height - measuring one row's own KEY child (not the row container itself, which
        // is taller by its own top/bottom padding) after layout is enough to size every key
        // consistently.
        row1.doOnLayout { applyNumpadMatchingTextSize() }
    }

    private fun applyNumpadMatchingTextSize() {
        val referenceKey = findViewById<LinearLayout>(R.id.calc_row1).getChildAt(0) ?: return
        val keyHeight = referenceKey.height
        if (keyHeight <= 0) return
        val letterSizePx = resources.getFraction(R.fraction.config_key_letter_ratio_lxx, keyHeight, keyHeight)
        val labelSizePx = resources.getFraction(R.fraction.config_key_label_ratio_lxx, keyHeight, keyHeight)
        // All 4 rows are flat now, so every key (plain or specially-styled) is a direct child of
        // one of these 4 row containers - no more separate sibling list needed.
        val allKeys = listOf(R.id.calc_row1, R.id.calc_row2, R.id.calc_row3, R.id.calc_row4).flatMap { rowId ->
            val row = findViewById<LinearLayout>(rowId)
            (0 until row.childCount).mapNotNull { row.getChildAt(it) as? TextView }
        }
        allKeys.forEach { key ->
            // "AC" is 2 characters but visually short like a single operator glyph (unlike the
            // genuinely longer "000" label) - sized at the bigger letter ratio so it doesn't look
            // shrunken next to "C"-sized neighbors, then trimmed 10% per explicit request
            // (letter-ratio on its own read a touch too large for a 2-character label).
            val ratioPx = when {
                key.text == "AC" -> letterSizePx * 0.9f
                (key.text?.length ?: 0) <= 1 -> letterSizePx
                else -> labelSizePx
            }
            key.setTextSize(TypedValue.COMPLEX_UNIT_PX, ratioPx)
        }
    }

    /** Rectangle + very large corner radius = pill, auto-clipped to the view's own bounds
     *  regardless of size (the same trick as the XML `android:radius="500dp"` convention used
     *  throughout this app), but with an explicit color instead of an unthemed drawable placeholder. */
    private fun pillDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 500f * resources.displayMetrics.density
        setColor(color)
    }

    /** Rounded-square, solid-fill key background (Gboard-style block, no stroke) - mirrors
     *  Colors.kt's DefaultColors.borderedKeyDrawable() exactly (same 8dp corner radius, no
     *  stroke), duplicated here rather than shared since this whole panel never goes through the
     *  Key/KeyboardView/Colors pipeline at all - every button here is a plain View with its own
     *  manually-assigned background (see buildAbcCommaKey/buildEqualsKey below). Always rendered
     *  this way regardless of the global "Border pada Tombol" toggle (per explicit request: the
     *  Calculator panel should always look bordered/blocked, unlike the rest of the keyboard
     *  where that's opt-in) - unlike the rest of this panel's drawables, this one does NOT branch
     *  on Settings.getValues().mColors.hasKeyBorders. */
    private fun keyDrawable(fill: Int): Drawable {
        val density = resources.displayMetrics.density
        val cornerRadiusPx = 8f * density
        fun shape(fillColor: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(fillColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(brightenOrDarken(fill, true)))
            addState(intArrayOf(), shape(fill))
        }
    }

    private fun buildRow(row: LinearLayout, keys: List<String>, keyTextColor: Int) {
        if (row.childCount > 0) return // view instance reused across show/hide, don't rebuild
        keys.forEach { label ->
            row.addView(createKey(label, keyTextColor))
        }
    }

    /** weight=1, MATCH_PARENT height, 3dp margin on every side - the one shared sizing/spacing
     *  rule every key in the grid uses (plain digits/operators AND AC/backspace/ABC/=), so all 6
     *  columns in every row end up exactly the same width (see the margin-subtraction bug this
     *  flat-row structure fixes, explained above initialize()'s row-building code). */
    private fun standardKeyLayoutParams(): LinearLayout.LayoutParams {
        val marginPx = (3f * resources.displayMetrics.density).toInt()
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(marginPx, marginPx, marginPx, marginPx)
        }
    }

    private fun createKey(label: String, keyTextColor: Int): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(keyTextColor)
            // Text size applied uniformly after layout by applyNumpadMatchingTextSize().
            // FUNCTIONAL_KEY_BACKGROUND, not KEY_BACKGROUND - see buildBackspaceKey() below.
            background = keyDrawable(Settings.getValues().mColors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
            layoutParams = standardKeyLayoutParams()
            setOnClickListener { onCalcKeyPress(label) }
        }
    }

    private fun buildAcKey(): TextView = TextView(context).apply {
        text = "AC"
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        // Red goes on the TEXT, not the box - the box stays the same normal key fill as every
        // other key in the row (per explicit request: "tombol AC yg merah itu hurufnya bukan
        // kotaknya"). Clearly red but not an alarm-style neon red - bumped up from an earlier,
        // too-muted pass that blended into the background on dark theme.
        val acColor = if (isDarkColor(Settings.getValues().mColors.get(ColorType.KEY_BACKGROUND)))
            0xFFEF5350.toInt() else 0xFFD32F2F.toInt()
        setTextColor(acColor)
        background = keyDrawable(Settings.getValues().mColors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
        layoutParams = standardKeyLayoutParams()
        // Internal dispatch key stays "C" (onCalcKeyPress's existing clear-all branch) - only
        // the displayed label changed to "AC", no business-logic change needed.
        setOnClickListener { onCalcKeyPress("C") }
    }

    private fun buildBackspaceKey(keyTextColor: Int): TextView = TextView(context).apply {
        text = "⌫"
        gravity = Gravity.CENTER
        setTextColor(keyTextColor)
        // FUNCTIONAL_KEY_BACKGROUND (not KEY_BACKGROUND) - same Gboard-block fix as the main
        // keyboard's Colors.kt: KEY_BACKGROUND is deliberately identical to the keyboard
        // background in both themes (the flat/no-border look), so bordered mode needs the
        // already-distinct functionalKey tile color instead. No-op when borders are off -
        // keyDrawable() ignores `fill` entirely in that branch.
        background = keyDrawable(Settings.getValues().mColors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
        layoutParams = standardKeyLayoutParams()
        setupBackspaceRepeat(this)
    }

    /** Combined "switch to alphabet" / "insert comma" key - the dedicated "," column was dropped
     *  to make room for AC/backspace moving into row1, so its only remaining entry point is a
     *  long-press on this icon (abc_koma.svg, converted to ic_calc_abc_comma_sellby - drawn as a
     *  single glyph showing both "ABC" and "," together, per the asset's own design). Short tap
     *  keeps the old plain-text "ABC" key's behavior (switch keyboard mode) unchanged. */
    private fun buildAbcCommaKey(): View = ImageView(context).apply {
        setImageResource(R.drawable.ic_calc_abc_comma_sellby)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val paddingPx = (1f * resources.displayMetrics.density).toInt()
        setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        // Same badge-grey fill as the other badge pills (ABC/?123) elsewhere in the keyboard -
        // btn_keyboard_key_badge_lxx_base's own <solid> color is just an unthemed placeholder,
        // only tinted correctly when drawn through the normal Key/KeyboardView pipeline, which
        // this plain View background bypasses. Always the bordered rounded-square shape (see
        // keyDrawable() above) regardless of the global key-borders toggle.
        background = keyDrawable(Settings.getValues().mColors.get(ColorType.BADGE_KEY_BACKGROUND))
        setColorFilter(Settings.getValues().mColors.get(ColorType.FUNCTIONAL_KEY_TEXT))
        layoutParams = standardKeyLayoutParams()
        setOnClickListener {
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }
        setOnLongClickListener {
            onCalcKeyPress(",")
            true
        }
    }

    private fun buildEqualsKey(): TextView = TextView(context).apply {
        text = "="
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        // White text explicitly - a plain TextView's default text color doesn't give white on
        // its own, so without this it rendered as a barely-visible grey glyph on the teal fill.
        setTextColor(android.graphics.Color.WHITE)
        // Fixed teal accent fill, same as Enter's actual accent color, in both light and dark
        // mode (btn_keyboard_key_action_normal_lxx_base's own <solid> is an unthemed white
        // placeholder outside the Key/KeyboardView pipeline, same issue as buildAbcCommaKey()
        // above). Always the bordered rounded-square shape regardless of key-borders toggle.
        val accentColor = ContextCompat.getColor(context, R.color.calculator_accent)
        background = keyDrawable(accentColor)
        layoutParams = standardKeyLayoutParams()
        setOnClickListener { onCalcKeyPress("=") }
    }

    private fun setupBackspaceRepeat(view: TextView) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    onCalcKeyPress("DEL")
                    val runnable = object : Runnable {
                        override fun run() {
                            onCalcKeyPress("DEL")
                            handler.postDelayed(this, 70)
                        }
                    }
                    backspaceRepeatRunnable = runnable
                    handler.postDelayed(runnable, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    backspaceRepeatRunnable?.let { handler.removeCallbacks(it) }
                    backspaceRepeatRunnable = null
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    fun startCalculator(keyVisualAttr: KeyVisualAttributes?, editorInfo: EditorInfo, listener: KeyboardActionListener) {
        keyboardActionListener = listener
        initialize()
        updateDisplay()
    }

    fun stopCalculator() {
        backspaceRepeatRunnable?.let { handler.removeCallbacks(it) }
        backspaceRepeatRunnable = null
        // expression/result are intentionally kept, matching the reference design (state persists
        // across show/hide, same as ClipboardHistoryManager's history)
    }

    private fun updateDisplay() {
        if (!initialized) return
        // Font size is handled by autosize (set up once in initialize()), which shrinks only once
        // the text actually stops fitting the available width - not a fixed length threshold.
        expressionView.text = expression.ifEmpty { "0" }
        resultView.text = "=$result"
    }

    // ---------------------------------------------------------------------------------------
    // Key press state machine, ported from sellby_keyboard.dart's _onCalcKeyPress (line ~676)
    // ---------------------------------------------------------------------------------------

    private fun onCalcKeyPress(key: String) {
        when {
            key == "C" -> {
                expression = ""
                result = "0"
            }
            key == "DEL" -> {
                if (expression.isNotEmpty()) {
                    expression = expression.dropLast(1)
                    if (expression.endsWith(".")) expression = expression.dropLast(1)
                    expression = formatExpressionString(expression)
                    calculateExpression()
                } else {
                    result = "0"
                }
            }
            key == "=" -> {
                calculateExpression()
                if (result != "Error" && result != "...") {
                    expression = result
                }
            }
            key == "000" -> {
                val cleanDigits = getCurrentNumberSegment(expression).filter { it.isDigit() }
                if (cleanDigits.length + 3 > 15) return
                expression = when {
                    expression.isEmpty() || expression == "0" -> "0"
                    expression.last() in "+-×÷(" -> expression + "0"
                    else -> expression + "000"
                }
                expression = formatExpressionString(expression)
                calculateExpression()
            }
            key == "," -> {
                val currentNum = getCurrentNumberSegment(expression)
                if (!currentNum.contains(",")) {
                    expression += if (currentNum.isEmpty() || (expression.isNotEmpty() && expression.last() in "+-×÷()")) "0," else ","
                }
                calculateExpression()
            }
            key.length == 1 && key[0] in "+-×÷" -> {
                if (expression.isEmpty()) {
                    if (key == "-") expression = "-"
                } else if (expression.last() in "+-×÷") {
                    expression = expression.dropLast(1) + key
                } else if (expression.endsWith("(")) {
                    if (key == "-") expression += "-"
                } else {
                    if (expression.endsWith(",")) expression = expression.dropLast(1)
                    expression += key
                }
                calculateExpression()
            }
            else -> { // digits 0-9 and %
                if (key.isNotEmpty() && key[0].isDigit()) {
                    val cleanDigits = getCurrentNumberSegment(expression).filter { it.isDigit() }
                    if (cleanDigits.length >= 15) return
                }
                expression = if ((expression == "0" || expression.isEmpty()) && key != "%") key else expression + key
                expression = formatExpressionString(expression)
                calculateExpression()
            }
        }
        updateDisplay()
    }

    private fun getCurrentNumberSegment(expr: String): String {
        if (expr.isEmpty()) return ""
        var lastOp = -1
        for (i in expr.length - 1 downTo 0) {
            if (expr[i] in "+-×÷()") { lastOp = i; break }
        }
        return if (lastOp == -1) expr else expr.substring(lastOp + 1)
    }

    /** Adds "." thousand separators / "," decimal separator to the live expression display, ported from _formatExpressionString */
    private fun formatExpressionString(expr: String): String {
        if (expr.isEmpty()) return ""
        val buffer = StringBuilder()
        var currentNum = StringBuilder()
        fun flushNumber() {
            if (currentNum.isNotEmpty()) {
                val clean = currentNum.toString().replace(".", "")
                if (clean.contains(",")) {
                    val parts = clean.split(",", limit = 2)
                    buffer.append(addThousandSeparators(parts[0])).append(",").append(parts.getOrElse(1) { "" })
                } else {
                    buffer.append(addThousandSeparators(clean))
                }
                currentNum = StringBuilder()
            }
        }
        for (c in expr) {
            when {
                c.isDigit() || c == ',' -> currentNum.append(c)
                c == '.' -> continue
                else -> { flushNumber(); buffer.append(c) }
            }
        }
        flushNumber()
        return buffer.toString()
    }

    private fun addThousandSeparators(intStr: String): String {
        val sb = StringBuilder()
        var count = 0
        for (i in intStr.length - 1 downTo 0) {
            sb.append(intStr[i])
            count++
            if (count == 3 && i != 0) { sb.append('.'); count = 0 }
        }
        return sb.reverse().toString()
    }

    // ---------------------------------------------------------------------------------------
    // Expression evaluator, ported from sellby_keyboard.dart's _calculateExpression/_evalMath/
    // _evalSubMath/_parseTokenValue/_formatResult (line ~840-1057)
    // ---------------------------------------------------------------------------------------

    private fun calculateExpression() {
        if (expression.isEmpty()) {
            result = "0"
            return
        }
        result = try {
            var cleanExpr = expression
                .replace("×", "*")
                .replace("÷", "/")
                .replace(".", "")
                .replace(",", ".")
            val openCount = cleanExpr.count { it == '(' }
            val closeCount = cleanExpr.count { it == ')' }
            if (openCount > closeCount) cleanExpr += ")".repeat(openCount - closeCount)
            formatResult(evalMath(cleanExpr))
        } catch (e: Exception) {
            "..."
        }
    }

    private fun evalMath(expr0: String): Double {
        var expr = expr0.replace(" ", "")
        if (expr.isEmpty()) return 0.0
        expr = Regex("(\\d)\\(").replace(expr) { "${it.groupValues[1]}*(" }
        expr = Regex("\\)(\\d)").replace(expr) { ")*${it.groupValues[1]}" }
        expr = expr.replace(")(", ")*(")

        var guard = 0
        while (expr.contains("(") && guard < 30) {
            guard++
            val match = Regex("\\(([^()]+)\\)").find(expr) ?: break
            val value = evalSubMath(match.groupValues[1])
            expr = expr.replaceRange(match.range, value.toString())
        }
        return evalSubMath(expr)
    }

    private fun evalSubMath(expr: String): Double {
        if (expr.isEmpty()) return 0.0
        val tokens = mutableListOf<String>()
        var idx = 0
        while (idx < expr.length) {
            val c = expr[idx]
            if (c == '-' && (tokens.isEmpty() || tokens.last() in listOf("+", "-", "*", "/"))) {
                val sb = StringBuilder("-")
                idx++
                while (idx < expr.length && (expr[idx].isDigit() || expr[idx] == '.')) { sb.append(expr[idx]); idx++ }
                if (idx < expr.length && expr[idx] == '%') { sb.append('%'); idx++ }
                tokens.add(sb.toString())
                continue
            }
            if (c.isDigit() || c == '.') {
                val sb = StringBuilder()
                while (idx < expr.length && (expr[idx].isDigit() || expr[idx] == '.')) { sb.append(expr[idx]); idx++ }
                if (idx < expr.length && expr[idx] == '%') { sb.append('%'); idx++ }
                tokens.add(sb.toString())
                continue
            }
            if (c == '%') {
                if (tokens.isNotEmpty() && !tokens.last().endsWith("%")) tokens[tokens.size - 1] = tokens.last() + "%"
                idx++
                continue
            }
            if (c == '+' || c == '-' || c == '*' || c == '/') {
                tokens.add(c.toString())
                idx++
                continue
            }
            idx++
        }
        if (tokens.isEmpty()) return 0.0
        if (tokens.last() in listOf("+", "-", "*", "/")) tokens.removeAt(tokens.size - 1)
        if (tokens.isEmpty()) return 0.0

        val pass1 = mutableListOf<String>()
        var p = 0
        while (p < tokens.size) {
            if (tokens[p] == "*" || tokens[p] == "/") {
                val op = tokens[p]
                val prevVal = parseTokenValue(pass1.removeAt(pass1.size - 1))
                val nextStr = if (p + 1 < tokens.size) tokens[p + 1] else "1"
                val nextVal = parseTokenValue(nextStr)
                val res = if (op == "*") prevVal * nextVal
                    else {
                        if (nextVal == 0.0) return Double.NaN
                        prevVal / nextVal
                    }
                pass1.add(res.toString())
                p += 2
            } else {
                pass1.add(tokens[p])
                p++
            }
        }
        if (pass1.isEmpty()) return 0.0

        var current = parseTokenValue(pass1[0])
        var q = 1
        while (q < pass1.size) {
            val op = pass1[q]
            val nextToken = if (q + 1 < pass1.size) pass1[q + 1] else "0"
            if (nextToken.endsWith("%")) {
                val pctFactor = (nextToken.removeSuffix("%").toDoubleOrNull() ?: 0.0) / 100.0
                val amount = current * pctFactor
                if (op == "+") current += amount
                if (op == "-") current -= amount
            } else {
                val v = nextToken.toDoubleOrNull() ?: 0.0
                if (op == "+") current += v
                if (op == "-") current -= v
            }
            q += 2
        }
        return current
    }

    private fun parseTokenValue(token: String): Double {
        if (token.endsWith("%")) return (token.removeSuffix("%").toDoubleOrNull() ?: 0.0) / 100.0
        return token.toDoubleOrNull() ?: 0.0
    }

    private fun formatResult(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "Error"
        val isNegative = value < 0
        val absValue = abs(value)

        if (absValue >= 1e15) {
            val expStr = String.format(Locale.US, "%.6e", absValue)
            val parts = expStr.split("e")
            val mantissa = parts[0].trimEnd('0').trimEnd('.')
            val exponent = parts[1].toInt()
            return (if (isNegative) "-" else "") + mantissa + "e" + exponent
        }

        if (absValue % 1.0 == 0.0) {
            val formatted = addThousandSeparators(absValue.toLong().toString())
            return if (isNegative) "-$formatted" else formatted
        }

        var rawFixed = String.format(Locale.US, "%.8f", absValue)
        if (rawFixed.contains(".")) rawFixed = rawFixed.trimEnd('0').trimEnd('.')

        return if (rawFixed.contains(".")) {
            val parts = rawFixed.split(".")
            val formatted = "${addThousandSeparators(parts[0])},${parts[1]}"
            if (isNegative) "-$formatted" else formatted
        } else {
            val formatted = addThousandSeparators(rawFixed)
            if (isNegative) "-$formatted" else formatted
        }
    }
}
