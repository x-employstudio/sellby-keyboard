/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.emoji;

import java.util.HashMap;
import java.util.Map;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.Key;
import helium314.keyboard.keyboard.KeyboardActionListener;
import helium314.keyboard.keyboard.KeyboardLayoutSet;
import helium314.keyboard.keyboard.KeyboardSwitcher;
import helium314.keyboard.keyboard.internal.KeyVisualAttributes;
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParserKt;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.AudioAndHapticFeedbackManager;
import helium314.keyboard.latin.dictionary.Dictionary;
import helium314.keyboard.latin.dictionary.DictionaryFactory;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.SingleDictionaryFacilitator;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.utils.DictionaryInfoUtils;
import helium314.keyboard.latin.utils.ResourceUtils;

import static helium314.keyboard.latin.common.Constants.NOT_A_COORDINATE;

/**
 * View class to implement Emoji palettes.
 * The Emoji keyboard consists of group of views layout/emoji_palettes_view.
 * <ol>
 * <li> Emoji category tabs.
 * <li> Delete button.
 * <li> Emoji keyboard pages that can be scrolled by swiping horizontally or by selecting a tab.
 * <li> Back to main keyboard button and enter button.
 * </ol>
 * Because of the above reasons, this class doesn't extend {@link KeyboardView}.
 */
public final class EmojiPalettesView extends LinearLayout
        implements View.OnClickListener, EmojiViewCallback {
    private static final class PagerViewHolder extends RecyclerView.ViewHolder {
        private long mCategoryId;

        private PagerViewHolder(View itemView) {
            super(itemView);
        }
    }

    private final class PagerAdapter extends RecyclerView.Adapter<PagerViewHolder> {
        private boolean mInitialized;
        private final Map<Integer, RecyclerView> mViews = new HashMap<>(mEmojiCategory.getShownCategories().size());

        private PagerAdapter(ViewPager2 pager) {
            setHasStableIds(true);
            pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    var categoryId = (int) getItemId(position);
                    setCurrentCategory(EmojiCategory.Category.getEntries().get(categoryId), false);
                    var recyclerView = mViews.get(position);
                    if (recyclerView != null) {
                        updateState(recyclerView, categoryId);
                    }
                }
            });
        }

        @Override
        public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
            recyclerView.setItemViewCacheSize(mEmojiCategory.getShownCategories().size());
        }

        @NonNull
        @Override
        public PagerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            var view = LayoutInflater.from(parent.getContext()).inflate(R.layout.emoji_category_view, parent, false);
            var viewHolder = new PagerViewHolder(view);
            var emojiRecyclerView = getRecyclerView(view);

            emojiRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    // Ignore this message. Only want the actual page selected.
                }

                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    updateState(recyclerView, viewHolder.mCategoryId);
                }
            });

            emojiRecyclerView.setPersistentDrawingCache(PERSISTENT_NO_CACHE);
            return viewHolder;
        }

        @Override
        public void onBindViewHolder(PagerViewHolder holder, int position) {
            holder.mCategoryId = getItemId(position);
            var recyclerView = getRecyclerView(holder.itemView);
            mViews.put(position, recyclerView);
            recyclerView.setAdapter(new EmojiPalettesAdapter(
                mEmojiCategory,
                EmojiCategory.Category.getEntries().get((int) holder.mCategoryId),
                EmojiPalettesView.this
            ));

            if (! mInitialized) {
                recyclerView.scrollToPosition(mEmojiCategory.getCurrentCategoryPageId());
                mInitialized = true;
            }
        }

        @Override
        public int getItemCount() {
            return mEmojiCategory.getShownCategories().size();
        }

        @Override
        public void onViewDetachedFromWindow(PagerViewHolder holder) {
            if (holder.mCategoryId == EmojiCategory.Category.RECENTS.ordinal()) {
                // Needs to save pending updates for recent keys when we get out of the recents
                // category because we don't want to move the recent emojis around while the user
                // is in the recents category.
                getRecentsKeyboard().flushPendingRecentKeys();
                getRecyclerView(holder.itemView).getAdapter().notifyDataSetChanged();
            }
        }

        @Override
        public long getItemId(int position) {
            return mEmojiCategory.getShownCategories().get(position).getCategory().ordinal();
        }

        private static RecyclerView getRecyclerView(View view) {
            return view.findViewById(R.id.emoji_keyboard_list);
        }

        private void updateState(@NonNull RecyclerView recyclerView, long categoryId) {
            if (categoryId != mEmojiCategory.getCurrentCategory().ordinal()) {
                return;
            }

            final int offset = recyclerView.computeVerticalScrollOffset();
            final int extent = recyclerView.computeVerticalScrollExtent();
            final int range = recyclerView.computeVerticalScrollRange();
            final float percentage = offset / (float) (range - extent);

            final int currentCategorySize = mEmojiCategory.getCurrentCategoryPageCount();
            final int a = (int) (percentage * currentCategorySize);
            final float b = percentage * currentCategorySize - a;
            mEmojiCategoryPageIndicatorView.setCategoryPageId(currentCategorySize, a, b);

            LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
            final int firstCompleteVisibleBoard = layoutManager.findFirstCompletelyVisibleItemPosition();
            final int firstVisibleBoard = layoutManager.findFirstVisibleItemPosition();
            mEmojiCategory.setCurrentCategoryPageId(
                    firstCompleteVisibleBoard > 0 ? firstCompleteVisibleBoard : firstVisibleBoard);
        }
    }

    private static SingleDictionaryFacilitator sDictionaryFacilitator;

    private boolean initialized = false;
    private final Colors mColors;
    private final EmojiLayoutParams mEmojiLayoutParams;
    private LinearLayout mTabStrip;
    private EmojiCategoryPageIndicatorView mEmojiCategoryPageIndicatorView;
    private KeyboardActionListener mKeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER;
    private final EmojiCategory mEmojiCategory;
    private ViewPager2 mPager;

    public EmojiPalettesView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.emojiPalettesViewStyle);
    }

    public EmojiPalettesView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
        mColors = Settings.getValues().mColors;
        final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(context, null, null);
        final Resources res = context.getResources();
        mEmojiLayoutParams = new EmojiLayoutParams(res);
        builder.setSubtype(RichInputMethodSubtype.Companion.getEmojiSubtype());
        builder.setKeyboardGeometry(ResourceUtils.getKeyboardWidth(context, Settings.getValues()),
                mEmojiLayoutParams.getEmojiKeyboardHeight());
        final KeyboardLayoutSet layoutSet = builder.build();
        final TypedArray emojiPalettesViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.EmojiPalettesView, defStyle, R.style.EmojiPalettesView);
        mEmojiCategory = new EmojiCategory(context, layoutSet, emojiPalettesViewAttr);
        emojiPalettesViewAttr.recycle();
        setFitsSystemWindows(true);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final Resources res = getContext().getResources();
        // The main keyboard expands to the entire this {@link KeyboardView}.
        final int width = ResourceUtils.getKeyboardWidth(getContext(), Settings.getValues())
                + getPaddingLeft() + getPaddingRight();
        final int height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues())
                + getPaddingTop() + getPaddingBottom();
        mEmojiCategoryPageIndicatorView.mWidth = width;
        setMeasuredDimension(width, height);
    }

    // Sellby: category tab is a fixed 36dp circle (teal-filled when active), scrollable strip
    // instead of the stock equal-division top strip - see sellby_keyboard.dart's _EmojiSection.
    private static final int TAB_SIZE_DP = 36;
    private static final int TAB_MARGIN_DP = 2;
    private static final int TAB_ICON_SIZE_DP = 19;

    private void addTab(LinearLayout host, EmojiCategory.Category category) {
        final ImageView iconView = new ImageView(getContext());
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconView.setImageResource(mEmojiCategory.getCategoryTabIcon(category));
        iconView.setContentDescription(mEmojiCategory.getAccessibilityDescription(category));
        iconView.setTag(category);
        final float density = getResources().getDisplayMetrics().density;
        final int size = (int) (TAB_SIZE_DP * density);
        final int margin = (int) (TAB_MARGIN_DP * density);
        final int iconPadding = (int) ((TAB_SIZE_DP - TAB_ICON_SIZE_DP) / 2f * density);
        iconView.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(margin, 0, margin, 0);
        iconView.setLayoutParams(lp);
        setTabSelected(iconView, category == mEmojiCategory.getCurrentCategory());
        iconView.setOnClickListener(this);
        host.addView(iconView);
        if (category == EmojiCategory.Category.RECENTS) {
            iconView.setOnLongClickListener(v -> {
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_LONG_PRESS);
                clearRecentKeys();

                return true;
            });
        }
    }

    private void setTabSelected(ImageView iconView, boolean selected) {
        if (selected) {
            final GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(ContextCompat.getColor(getContext(), R.color.calculator_accent));
            iconView.setBackground(bg);
            iconView.setColorFilter(Color.WHITE);
        } else {
            iconView.setBackground(null);
            mColors.setColor(iconView, ColorType.EMOJI_CATEGORY);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    public void initialize() {
        if (initialized) return;
        mEmojiCategory.initialize();
        mTabStrip = findViewById(R.id.emoji_category_tab_strip);
        for (EmojiCategory.CategoryProperties properties : mEmojiCategory.getShownCategories()) {
            addTab(mTabStrip, properties.getCategory());
        }

        mPager = findViewById(R.id.emoji_pager);
        mPager.setAdapter(new PagerAdapter(mPager));
        mEmojiLayoutParams.setEmojiListProperties(mPager);
        mEmojiCategoryPageIndicatorView = findViewById(R.id.emoji_category_page_id_view);
        mEmojiLayoutParams.setCategoryPageIdViewProperties(mEmojiCategoryPageIndicatorView);
        setCurrentCategory(mEmojiCategory.getCurrentCategory(), true);
        mEmojiCategoryPageIndicatorView.setColors(mColors.get(ColorType.EMOJI_CATEGORY_SELECTED), mColors.get(ColorType.STRIP_BACKGROUND));
        setupBottomBar();
        initialized = true;
    }

    /** Sellby: "?123" pill (back to letters) + scrollable category tabs + red backspace pill,
     *  replacing the stock alpha/search/space/delete bottom row entirely - matches
     *  sellby_keyboard.dart's _EmojiSection bottom row exactly (no spacebar in Emoji mode). */
    private void setupBottomBar() {
        final TextView backToAlpha = findViewById(R.id.emoji_back_to_alpha);
        backToAlpha.setBackground(pillDrawable(mColors.get(ColorType.BADGE_KEY_BACKGROUND)));
        backToAlpha.setTextColor(mColors.get(ColorType.FUNCTIONAL_KEY_TEXT));
        backToAlpha.setOnClickListener(v ->
                mKeyboardActionListener.onCodeInput(KeyCode.ALPHA, NOT_A_COORDINATE, NOT_A_COORDINATE, false));

        final ImageView backspace = findViewById(R.id.emoji_backspace);
        backspace.setImageResource(R.drawable.sym_keyboard_delete_lxx);
        backspace.setColorFilter(Color.WHITE);
        backspace.setBackground(pillDrawable(0xFFE53935));
        setupBackspaceRepeat(backspace);
    }

    private GradientDrawable pillDrawable(int color) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(500f * getResources().getDisplayMetrics().density);
        drawable.setColor(color);
        return drawable;
    }

    private final Handler mBackspaceHandler = new Handler(Looper.getMainLooper());
    private Runnable mBackspaceRepeatRunnable;

    @SuppressLint("ClickableViewAccessibility")
    private void setupBackspaceRepeat(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> {
                    v.setPressed(true);
                    deleteOneCharacter();
                    final Runnable runnable = new Runnable() {
                        @Override
                        public void run() {
                            deleteOneCharacter();
                            mBackspaceHandler.postDelayed(this, 70);
                        }
                    };
                    mBackspaceRepeatRunnable = runnable;
                    mBackspaceHandler.postDelayed(runnable, 400);
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mBackspaceRepeatRunnable != null) {
                        mBackspaceHandler.removeCallbacks(mBackspaceRepeatRunnable);
                        mBackspaceRepeatRunnable = null;
                    }
                    v.setPressed(false);
                    return true;
                }
                default -> {
                    return false;
                }
            }
        });
    }

    private void deleteOneCharacter() {
        mKeyboardActionListener.onCodeInput(KeyCode.DELETE, NOT_A_COORDINATE, NOT_A_COORDINATE, false);
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link android.view.View.OnClickListener}
     * interface to handle non-canceled touch-up events from View-based elements such as the space
     * bar.
     */
    @Override
    public void onClick(View v) {
        final Object tag = v.getTag();
        if (tag instanceof EmojiCategory.Category category) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS);
            if (category != mEmojiCategory.getCurrentCategory()) {
                setCurrentCategory(category, false);
                updateEmojiCategoryPageIdView();
            }
        }
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji buttons.
     */
    @Override
    public void onPressKey(final Key key) {
        final int code = key.getCode();
        mKeyboardActionListener.onPressKey(code, 0, 1, HapticEvent.KEY_PRESS);
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji buttons.
     * This may be called without any prior call to {@link EmojiViewCallback#onPressKey(Key)}.
     */
    @Override
    public void onReleaseKey(final Key key) {
        addRecentKey(key);
        final int code = key.getCode();
        if (code == KeyCode.MULTIPLE_CODE_POINTS) {
            // todo: when we enter some emoticons, e.g. :-D, inline emoji search is triggered and emoji view is closed
            //  (one more instance of "emoji search should recognize emoticons", but in this case could be fixed in other ways)
            mKeyboardActionListener.onTextInput(key.getOutputText());
        } else if (code != KeyCode.UNSPECIFIED) {
            mKeyboardActionListener.onCodeInput(code, NOT_A_COORDINATE, NOT_A_COORDINATE, false);
        }
        mKeyboardActionListener.onReleaseKey(code, false);
        if (Settings.getValues().mAlphaAfterEmojiInEmojiView)
            mKeyboardActionListener.onCodeInput(KeyCode.ALPHA, NOT_A_COORDINATE, NOT_A_COORDINATE, false);
    }

    @Override
    public void onRemoveRecentsKey(Key key)
    {
        getRecentsKeyboard().removeRecentsKey(key);
    }

    private void clearRecentKeys() {
        if (RecentEmojis.get().isEmpty()) return;
        RecentEmojis.clear();
        getRecentsKeyboard().removeAllKeys();
        mPager.getAdapter().notifyItemChanged(mEmojiCategory.getRecentTabId());
        KeyboardSwitcher.getInstance().showToast(getResources().getString(R.string.recent_emojis_cleared), true);
    }

    @Override
    public String getDescription(String emoji) {
        if (sDictionaryFacilitator == null) {
            return null;
        }

        var wordProperty = sDictionaryFacilitator.getWordProperty(EmojiParserKt.getEmojiNeutralVersion(emoji));
        if (wordProperty == null || ! wordProperty.mHasShortcuts) {
            return null;
        }

        return wordProperty.mShortcutTargets.get(0).mWord;
    }

    public void setHardwareAcceleratedDrawingEnabled(final boolean enabled) {
        if (!enabled) return;
        // TODO: Should use LAYER_TYPE_SOFTWARE when hardware acceleration is off?
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void startEmojiPalettes(final KeyVisualAttributes keyVisualAttr,
               final EditorInfo editorInfo, final KeyboardActionListener keyboardActionListener) {
        initialize();

        new EmojiLayoutParams(getResources()).setEmojiListProperties(mPager); // necessary when floating
        setupSidePadding();
        initDictionaryFacilitator();
    }

    void addRecentKey(final Key key) {
        if (Settings.getValues().mIncognitoModeEnabled) {
            // We do not want to log recent keys while being in incognito
            return;
        }
        if (getVisibility() == VISIBLE && mEmojiCategory.isInRecentTab()) {
            getRecentsKeyboard().addPendingKey(key);
            return;
        }
        getRecentsKeyboard().addKeyFirst(key);
        if (initialized)
            mPager.getAdapter().notifyItemChanged(mEmojiCategory.getRecentTabId());
    }

    private void setupSidePadding() {
        final SettingsValues sv = Settings.getValues();
        final int keyboardWidth = ResourceUtils.getKeyboardWidth(getContext(), sv);
        final TypedArray keyboardAttr = getContext().obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard);
        final float leftPadding = keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.mSidePaddingScale;
        final float rightPadding =  keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.mSidePaddingScale;
        keyboardAttr.recycle();
        mPager.setPadding(
                (int) leftPadding,
                mPager.getPaddingTop(),
                (int) rightPadding,
                mPager.getPaddingBottom()
        );
        mEmojiCategoryPageIndicatorView.setPadding(
                (int) leftPadding,
                mEmojiCategoryPageIndicatorView.getPaddingTop(),
                (int) rightPadding,
                mEmojiCategoryPageIndicatorView.getPaddingBottom()
        );
        // setting width does not do anything, so we have some workaround in EmojiCategoryPageIndicatorView
    }

    public void stopEmojiPalettes() {
        if (!initialized) return;
        getRecentsKeyboard().flushPendingRecentKeys();
    }

    private DynamicGridKeyboard getRecentsKeyboard() {
        return mEmojiCategory.getKeyboard(EmojiCategory.Category.RECENTS, 0);
    }

    public void setKeyboardActionListener(final KeyboardActionListener listener) {
        mKeyboardActionListener = listener;
    }

    private void updateEmojiCategoryPageIdView() {
        if (mEmojiCategoryPageIndicatorView == null) {
            return;
        }
        mEmojiCategoryPageIndicatorView.setCategoryPageId(
                mEmojiCategory.getCurrentCategoryPageCount(),
                mEmojiCategory.getCurrentCategoryPageId(), 0.0f);
    }

    private void setCurrentCategory(EmojiCategory.Category category, boolean initial) {
        EmojiCategory.Category oldCategory = mEmojiCategory.getCurrentCategory();
        if (initial || oldCategory != category) {
            mEmojiCategory.setCurrentCategory(category);

            if (mPager.getScrollState() != ViewPager2.SCROLL_STATE_DRAGGING) {
                // Not swiping
                mPager.setCurrentItem(mEmojiCategory.getTabIdFromCategoryId(
                                mEmojiCategory.getCurrentCategory()), ! initial && ! isAnimationsDisabled());
            }

            if (mTabStrip != null) {
                View old = mTabStrip.findViewWithTag(oldCategory);
                View current = mTabStrip.findViewWithTag(category);

                if (old instanceof ImageView) setTabSelected((ImageView) old, false);
                if (current instanceof ImageView) setTabSelected((ImageView) current, true);
            }
        }
    }

    private boolean isAnimationsDisabled() {
        return android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                                                         android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0.0f;
    }

    public void clearKeyboardCache() {
        if (!initialized) {
            return;
        }

        mEmojiCategory.clearKeyboardCache();
        mPager.getAdapter().notifyDataSetChanged();
        closeDictionaryFacilitator();
    }

    private void initDictionaryFacilitator() {
        if (Settings.getValues().mShowEmojiDescriptions) {
            var locale = RichInputMethodManager.getInstance().getCurrentSubtype().getLocale();
            if (sDictionaryFacilitator == null || ! sDictionaryFacilitator.isForLocale(locale)) {
                closeDictionaryFacilitator();
                var dictFile = DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI, getContext());
                var dictionary = dictFile != null? DictionaryFactory.getDictionary(dictFile, locale) : null;
                sDictionaryFacilitator = dictionary != null? new SingleDictionaryFacilitator(dictionary) : null;
            }
        } else {
            closeDictionaryFacilitator();
        }
    }

    public static void closeDictionaryFacilitator() {
        if (sDictionaryFacilitator != null) {
            sDictionaryFacilitator.closeDictionaries();
            sDictionaryFacilitator = null;
        }
    }
}
