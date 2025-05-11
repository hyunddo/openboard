/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dslul.openboard.inputmethod.latin.suggestions;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.dslul.openboard.inputmethod.accessibility.AccessibilityUtils;
import org.dslul.openboard.inputmethod.keyboard.Keyboard;
import org.dslul.openboard.inputmethod.keyboard.MainKeyboardView;
import org.dslul.openboard.inputmethod.keyboard.MoreKeysPanel;
import org.dslul.openboard.inputmethod.latin.AudioAndHapticFeedbackManager;
import org.dslul.openboard.inputmethod.latin.R;
import org.dslul.openboard.inputmethod.latin.SuggestedWords;
import org.dslul.openboard.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import org.dslul.openboard.inputmethod.latin.common.Constants;
import org.dslul.openboard.inputmethod.latin.define.DebugFlags;
import org.dslul.openboard.inputmethod.latin.network.RagApiService;
import org.dslul.openboard.inputmethod.latin.network.RagSearchResponse;
import org.dslul.openboard.inputmethod.latin.settings.Settings;
import org.dslul.openboard.inputmethod.latin.settings.SettingsValues;
import org.dslul.openboard.inputmethod.latin.suggestions.MoreSuggestionsView.MoreSuggestionsListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.core.view.ViewCompat;


import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class SuggestionStripView extends RelativeLayout implements OnClickListener,
        OnLongClickListener {
    private static final String TAG = "IME_SEARCH/SSV";

    public interface Listener {
        void pickSuggestionManually(SuggestedWordInfo word);

        void onCodeInput(int primaryCode, int x, int y, boolean isKeyRepeat);

        void onTextInput(final String rawText);

        CharSequence getSelection();
    }

    static final boolean DBG = DebugFlags.DEBUG_ENABLED;
    private static final float DEBUG_INFO_TEXT_SIZE_IN_DIP = 6.0f;

    private final ImageButton mVoiceKey;

    // API 호출용
    private RagApiService ragApi;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // 뷰 바인딩
    private final ImageButton mSearchKey;        // 돋보기 아이콘
    private final LinearLayout mSuggestionsStrip; // 기존 추천 텍스트 스트립
    private final LinearLayout mInputContainer;   // 검색 입력창 + 전송 버튼 컨테이너
    private final EditText mSearchInput;      // 검색어 입력 EditText
    private final ImageButton mSendKey;          // 전송 버튼
    private ViewGroup mButtonsContainer; // 검색/음성 버튼 등 원래 버튼들

    MainKeyboardView mMainKeyboardView;

    // 모드 플래그
    private boolean mIsSearchMode = false;

    private final View mMoreSuggestionsContainer;
    private final MoreSuggestionsView mMoreSuggestionsView;
    private final MoreSuggestions.Builder mMoreSuggestionsBuilder;

    private final ArrayList<TextView> mWordViews = new ArrayList<>();
    private final ArrayList<TextView> mDebugInfoViews = new ArrayList<>();
    private final ArrayList<View> mDividerViews = new ArrayList<>();

    Listener mListener;
    private SuggestedWords mSuggestedWords = SuggestedWords.getEmptyInstance();
    private int mStartIndexOfMoreSuggestions;

    private final SuggestionStripLayoutHelper mLayoutHelper;
    private final StripVisibilityGroup mStripVisibilityGroup;
    // 클래스 상단에 커스텀 코드 정의 (클립보드는 Constants.CODE_CLIPBOARD)
    private static final int CODE_MY_POPUP = 12345;

    private static class StripVisibilityGroup {
        private final View mSuggestionStripView;
        private final View mSuggestionsStrip;

        public StripVisibilityGroup(final View suggestionStripView,
                                    final ViewGroup suggestionsStrip) {
            mSuggestionStripView = suggestionStripView;
            mSuggestionsStrip = suggestionsStrip;
            showSuggestionsStrip();
        }

        public void setLayoutDirection(final boolean isRtlLanguage) {
            final int layoutDirection = isRtlLanguage ? ViewCompat.LAYOUT_DIRECTION_RTL
                    : ViewCompat.LAYOUT_DIRECTION_LTR;
            ViewCompat.setLayoutDirection(mSuggestionStripView, layoutDirection);
            ViewCompat.setLayoutDirection(mSuggestionsStrip, layoutDirection);
        }

        public void showSuggestionsStrip() {
            mSuggestionsStrip.setVisibility(VISIBLE);
        }

    }

    // ① 현재 검색 모드인지 외부에서 확인
    public boolean isSearchMode() {
        return mIsSearchMode;
    }

    // ② 검색창 EditText를 넘겨주거나, 편의 메서드를 만든다
    public EditText getSearchInput() {
        return mSearchInput;
    }

    // 선택) 편의 메서드
    public void appendToSearch(char c) {
        Log.d(TAG, "appendToSearch('" + c + "')");
        mSearchInput.append(String.valueOf(c));
    }

    public void deleteLast() {
        Editable e = mSearchInput.getText();
        Log.d(TAG, "deleteLast()  beforeLen=" + e.length());
        if (e.length() > 0) e.delete(e.length() - 1, e.length());
    }

    public void submitSearch() {
        final String q = mSearchInput.getText().toString().trim();
        Log.d(TAG, "submitSearch()  query=\"" + q + "\"");
        if (!TextUtils.isEmpty(q)) {
            doSearch(q);          // 이미 있는 메서드
            exitSearchMode();     // 이미 있는 메서드
        }
    }

    /**
     * Construct a {@link SuggestionStripView} for showing suggestions to be picked by the user.
     *
     * @param context
     * @param attrs
     */
    public SuggestionStripView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.suggestionStripViewStyle);
//        mSendKey.setOnClickListener(v -> doSearch());
    }

    /**
     * 검색 모드 진입
     */
    private void enterSearchMode() {
        Log.w("IME_SEARCH/SSV", "★ enterSearchMode CALLED ★");
        Log.d(TAG, "enterSearchMode()");
        // 1) 플래그를 **가장 먼저** 켜 둔다
        mIsSearchMode = true;

        // 2) 혹시 모를 외부 호출로 strip 이 가려지지 않도록 강제 노출
        setVisibility(VISIBLE);

        // 3) 일반 화면 전환
        mSuggestionsStrip.setVisibility(GONE);
        mSearchKey.setVisibility(GONE);
        mVoiceKey.setVisibility(GONE);
        mInputContainer.setVisibility(VISIBLE);

        /* ▼ 추가: 검색창을 맨 위로 올림 */
        mInputContainer.bringToFront();
        // bringToFront() 후 레이아웃/그리기 다시 시키기
        requestLayout();
        invalidate();

        mSearchInput.setText("");
        mSearchInput.requestFocus();
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(mSearchInput, InputMethodManager.SHOW_IMPLICIT);

        dumpVis(">> enterSearchMode END");
    }

    /**
     * 검색 모드 종료
     */
    private void exitSearchMode() {
        Log.d(TAG, "exitSearchMode()  text=\"" + mSearchInput.getText() + "\"");
        // 1) 먼저 플래그 해제
        mIsSearchMode = false;

        // 2) 화면 복구
        mInputContainer.setVisibility(GONE);
        mSearchKey.setVisibility(VISIBLE);
        mVoiceKey.setVisibility(VISIBLE);
        mSuggestionsStrip.setVisibility(VISIBLE);

        dumpVis(">> exitSearchMode END");
    }

    private void doSearch(final String query) {
        Log.d(TAG, "doSearch()  query=\"" + query + "\"");
        ragApi.search("36648ad3-ed4b-4eb0-bcf1-1dc66fa5d258", query).enqueue(new Callback<RagSearchResponse>() {
            @Override
            public void onResponse(Call<RagSearchResponse> call,
                                   Response<RagSearchResponse> resp) {
                // 요청된 URL 찍기
                Log.d("RagSearch", "Request URL: " + call.request().url());
                if (!resp.isSuccessful() || resp.body() == null) return;
                uiHandler.post(() -> showRagResults(resp.body()));
            }

            @Override
            public void onFailure(Call<RagSearchResponse> call, Throwable t) {
                uiHandler.post(() ->
                        Toast.makeText(getContext(), "검색 실패", Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    /**
     * 받은 결과를 추천 스트립에 뿌리기
     */
    private void showRagResults(RagSearchResponse res) {
        mSuggestionsStrip.removeAllViews();

        // answer
        if (!TextUtils.isEmpty(res.answer)) {
            TextView tv = new TextView(getContext(), null, R.attr.suggestionWordStyle);
            tv.setText(res.answer);
            tv.setPadding(16, 0, 16, 0);
            mSuggestionsStrip.addView(tv);
        }
        // info_results
        if (res.infoResults != null) {
            for (RagSearchResponse.ResultItem item : res.infoResults) {
                TextView tv = new TextView(getContext(), null, R.attr.suggestionWordStyle);
                tv.setText(item.text);
                tv.setPadding(16, 0, 16, 0);
                tv.setOnClickListener(v -> {
                    getListener().onTextInput(item.text);
                    clear();
                });
                mSuggestionsStrip.addView(tv);
            }
        }
        // photo_results
        if (res.photoResults != null) {
            for (RagSearchResponse.ResultItem item : res.photoResults) {
                TextView tv = new TextView(getContext(), null, R.attr.suggestionWordStyle);
                tv.setText(item.text);
                tv.setPadding(16, 0, 16, 0);
                tv.setOnClickListener(v -> {
                    getListener().onTextInput(item.text);
                    clear();
                });
                mSuggestionsStrip.addView(tv);
            }
        }

        mSuggestionsStrip.setVisibility(VISIBLE);
    }

    /**
     * LatinIME 쪽 리스너 getter (기존 코드에서 mListener)
     */
    private MoreSuggestionsView.MoreSuggestionsListener getListener() {
        // 여기에 실제 mListener 반환 로직을 넣으세요
        return (MoreSuggestionsView.MoreSuggestionsListener) mListener;
    }


    public SuggestionStripView(final Context context, final AttributeSet attrs,
                               final int defStyle) {
        super(context, attrs, defStyle);
        inflate(context, R.layout.suggestions_strip, this);

        Log.i(TAG, "CTOR  this=" + this.hashCode() + " searchKey=" + R.id.suggestions_strip_search_key);

        // 여기로 전부 집중시킵니다:
        // 1) 뷰 바인딩
        mSearchKey = findViewById(R.id.suggestions_strip_search_key);
        mSuggestionsStrip = findViewById(R.id.suggestions_strip);
        mInputContainer = findViewById(R.id.suggestions_strip_input_container);
        mSearchInput = findViewById(R.id.suggestions_strip_search_input);
        mSendKey = findViewById(R.id.suggestions_strip_send_key);
        mButtonsContainer = findViewById(R.id.suggestions_strip_wrapper);

        // 2) Retrofit/OkHttp 초기화
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://k12e201.p.ssafy.io:8090/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        ragApi = retrofit.create(RagApiService.class);

        // 3) 리스너 설정 --------------------------------------------------
        mSearchKey.setOnClickListener(v -> {
            if (mIsSearchMode) {      // 검색 모드였다면 끄기
                Log.i(TAG, "searchKey CLICK  (view.id=" + v.getId() + ")");
                exitSearchMode();
            } else {                  // 아니면 켜기
                enterSearchMode();
            }
        });

        mSendKey.setOnClickListener(v -> {
            String q = mSearchInput.getText().toString().trim();
            if (!q.isEmpty()) {
                doSearch(q);          // 검색 실행
            }
            exitSearchMode();         // 입력칸 닫기
        });

        final LayoutInflater inflater = LayoutInflater.from(context);

        mVoiceKey = findViewById(R.id.suggestions_strip_voice_key);
        mStripVisibilityGroup = new StripVisibilityGroup(this, mSuggestionsStrip);

        for (int pos = 0; pos < SuggestedWords.MAX_SUGGESTIONS; pos++) {
            final TextView word = new TextView(context, null, R.attr.suggestionWordStyle);
            word.setContentDescription(getResources().getString(R.string.spoken_empty_suggestion));
            word.setOnClickListener(this);
            word.setOnLongClickListener(this);
            mWordViews.add(word);
            final View divider = inflater.inflate(R.layout.suggestion_divider, null);
            mDividerViews.add(divider);
            final TextView info = new TextView(context, null, R.attr.suggestionWordStyle);
            info.setTextColor(Color.WHITE);
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DEBUG_INFO_TEXT_SIZE_IN_DIP);
            mDebugInfoViews.add(info);
        }

        mLayoutHelper = new SuggestionStripLayoutHelper(
                context, attrs, defStyle, mWordViews, mDividerViews, mDebugInfoViews);

        mMoreSuggestionsContainer = inflater.inflate(R.layout.more_suggestions, null);
        mMoreSuggestionsView = mMoreSuggestionsContainer
                .findViewById(R.id.more_suggestions_view);
        mMoreSuggestionsBuilder = new MoreSuggestions.Builder(context, mMoreSuggestionsView);

        final Resources res = context.getResources();
        mMoreSuggestionsModalTolerance = res.getDimensionPixelOffset(
                R.dimen.config_more_suggestions_modal_tolerance);
        mMoreSuggestionsSlidingDetector = new GestureDetector(
                context, mMoreSuggestionsSlidingListener);

        final TypedArray keyboardAttr = context.obtainStyledAttributes(attrs,
                R.styleable.Keyboard, defStyle, R.style.SuggestionStripView);
        final Drawable iconVoice = keyboardAttr.getDrawable(R.styleable.Keyboard_iconShortcutKey);
        final Drawable iconIncognito = keyboardAttr.getDrawable(R.styleable.Keyboard_iconIncognitoKey);
        final Drawable iconClipboard = keyboardAttr.getDrawable(R.styleable.Keyboard_iconClipboardNormalKey);
        keyboardAttr.recycle();
        mVoiceKey.setImageDrawable(iconVoice);
        mVoiceKey.setOnClickListener(this);
    }

    private void showRagResults(List<String> results) {
        // 1) 기존 추천 뷰 초기화
        mSuggestionsStrip.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (String text : results) {
            TextView tv = new TextView(getContext(), null, R.attr.suggestionWordStyle);
            tv.setText(text);
            tv.setPadding(16, 0, 16, 0);
            tv.setOnClickListener(v -> {
                // 클릭 시 실제 텍스트 입력
                mListener.onTextInput(text);
                clear(); // 스트립 숨기기
            });
            mSuggestionsStrip.addView(tv);
        }
        // 2) 스트립 보여주기
        mSuggestionsStrip.setVisibility(VISIBLE);
    }


    /**
     * A connection back to the input method.
     *
     * @param listener
     */
    public void setListener(final Listener listener, final View inputView) {
        mListener = listener;
        mMainKeyboardView = inputView.findViewById(R.id.keyboard_view);
    }

    public void updateVisibility(final boolean shouldBeVisible, final boolean isFullscreenMode) {

        dumpVis("-- updateVisibility IN  should=" + shouldBeVisible
                + " fs=" + isFullscreenMode + " search=" + mIsSearchMode);

        if (mIsSearchMode) {          // ← 검색 모드일 땐 strip 전체를
            return;                   //    건드리지 않는다
        }

        final int visibility = shouldBeVisible ? VISIBLE : (isFullscreenMode ? GONE : INVISIBLE);
        setVisibility(visibility);

        dumpVis("-- updateVisibility OUT");

        final SettingsValues currentSettingsValues = Settings.getInstance().getCurrent();
        mVoiceKey.setVisibility(currentSettingsValues.mShowsVoiceInputKey ? VISIBLE : GONE);
    }

    public void setSuggestions(final SuggestedWords suggestedWords, final boolean isRtlLanguage) {
        if (mIsSearchMode) return;    // ← 검색 모드에서는 후보 갱신 금지
        clear();
        mStripVisibilityGroup.setLayoutDirection(isRtlLanguage);
        mSuggestedWords = suggestedWords;
        mStartIndexOfMoreSuggestions = mLayoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
                getContext(), mSuggestedWords, mSuggestionsStrip, this);
        mStripVisibilityGroup.showSuggestionsStrip();
    }

    public void setMoreSuggestionsHeight(final int remainingHeight) {
        mLayoutHelper.setMoreSuggestionsHeight(remainingHeight);
    }

    public void clear() {
        if (mIsSearchMode) return;    // ← 검색 모드에서 strip 초기화 금지
        mSuggestionsStrip.removeAllViews();
        removeAllDebugInfoViews();
        mStripVisibilityGroup.showSuggestionsStrip();
        dismissMoreSuggestionsPanel();
    }

    private void removeAllDebugInfoViews() {
        // The debug info views may be placed as children views of this {@link SuggestionStripView}.
        for (final View debugInfoView : mDebugInfoViews) {
            final ViewParent parent = debugInfoView.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(debugInfoView);
            }
        }
    }

    private final MoreSuggestionsListener mMoreSuggestionsListener = new MoreSuggestionsListener() {
        @Override
        public void onSuggestionSelected(final SuggestedWordInfo wordInfo) {
            mListener.pickSuggestionManually(wordInfo);
            dismissMoreSuggestionsPanel();
        }

        @Override
        public void onCancelInput() {
            dismissMoreSuggestionsPanel();
        }
    };

    private final MoreKeysPanel.Controller mMoreSuggestionsController =
            new MoreKeysPanel.Controller() {
                @Override
                public void onDismissMoreKeysPanel() {
                    mMainKeyboardView.onDismissMoreKeysPanel();
                }

                @Override
                public void onShowMoreKeysPanel(final MoreKeysPanel panel) {
                    mMainKeyboardView.onShowMoreKeysPanel(panel);
                }

                @Override
                public void onCancelMoreKeysPanel() {
                    dismissMoreSuggestionsPanel();
                }
            };

    public boolean isShowingMoreSuggestionPanel() {
        return mMoreSuggestionsView.isShowingInParent();
    }

    public void dismissMoreSuggestionsPanel() {
        mMoreSuggestionsView.dismissMoreKeysPanel();
    }

    @Override
    public boolean onLongClick(final View view) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                Constants.NOT_A_CODE, this);
        return showMoreSuggestions();
    }

    boolean showMoreSuggestions() {
        final Keyboard parentKeyboard = mMainKeyboardView.getKeyboard();
        if (parentKeyboard == null) {
            return false;
        }
        final SuggestionStripLayoutHelper layoutHelper = mLayoutHelper;
        if (mSuggestedWords.size() <= mStartIndexOfMoreSuggestions) {
            return false;
        }
        final int stripWidth = getWidth();
        final View container = mMoreSuggestionsContainer;
        final int maxWidth = stripWidth - container.getPaddingLeft() - container.getPaddingRight();
        final MoreSuggestions.Builder builder = mMoreSuggestionsBuilder;
        builder.layout(mSuggestedWords, mStartIndexOfMoreSuggestions, maxWidth,
                (int) (maxWidth * layoutHelper.mMinMoreSuggestionsWidth),
                layoutHelper.getMaxMoreSuggestionsRow(), parentKeyboard);
        mMoreSuggestionsView.setKeyboard(builder.build());
        container.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        final MoreKeysPanel moreKeysPanel = mMoreSuggestionsView;
        final int pointX = stripWidth / 2;
        final int pointY = -layoutHelper.mMoreSuggestionsBottomGap;
        moreKeysPanel.showMoreKeysPanel(this, mMoreSuggestionsController, pointX, pointY,
                mMoreSuggestionsListener);
        mOriginX = mLastX;
        mOriginY = mLastY;
        for (int i = 0; i < mStartIndexOfMoreSuggestions; i++) {
            mWordViews.get(i).setPressed(false);
        }
        return true;
    }

    // Working variables for {@link onInterceptTouchEvent(MotionEvent)} and
    // {@link onTouchEvent(MotionEvent)}.
    private int mLastX;
    private int mLastY;
    private int mOriginX;
    private int mOriginY;
    private final int mMoreSuggestionsModalTolerance;
    private boolean mNeedsToTransformTouchEventToHoverEvent;
    private boolean mIsDispatchingHoverEventToMoreSuggestions;
    private final GestureDetector mMoreSuggestionsSlidingDetector;
    private final GestureDetector.OnGestureListener mMoreSuggestionsSlidingListener =
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onScroll(MotionEvent down, MotionEvent me, float deltaX, float deltaY) {
                    final float dy = me.getY() - down.getY();
                    if (deltaY > 0 && dy < 0) {
                        return showMoreSuggestions();
                    }
                    return false;
                }
            };

    @Override
    public boolean onInterceptTouchEvent(final MotionEvent me) {
        // Detecting sliding up finger to show {@link MoreSuggestionsView}.
        if (!mMoreSuggestionsView.isShowingInParent()) {
            mLastX = (int) me.getX();
            mLastY = (int) me.getY();
            return mMoreSuggestionsSlidingDetector.onTouchEvent(me);
        }
        if (mMoreSuggestionsView.isInModalMode()) {
            return false;
        }

        final int action = me.getAction();
        final int index = me.getActionIndex();
        final int x = (int) me.getX(index);
        final int y = (int) me.getY(index);
        if (Math.abs(x - mOriginX) >= mMoreSuggestionsModalTolerance
                || mOriginY - y >= mMoreSuggestionsModalTolerance) {
            // Decided to be in the sliding suggestion mode only when the touch point has been moved
            // upward. Further {@link MotionEvent}s will be delivered to
            // {@link #onTouchEvent(MotionEvent)}.
            mNeedsToTransformTouchEventToHoverEvent =
                    AccessibilityUtils.Companion.getInstance().isTouchExplorationEnabled();
            mIsDispatchingHoverEventToMoreSuggestions = false;
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            // Decided to be in the modal input mode.
            mMoreSuggestionsView.setModalMode();
        }
        return false;
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(final AccessibilityEvent event) {
        // Don't populate accessibility event with suggested words and voice key.
        return true;
    }

    @Override
    public boolean onTouchEvent(final MotionEvent me) {
        if (!mMoreSuggestionsView.isShowingInParent()) {
            // Ignore any touch event while more suggestions panel hasn't been shown.
            // Detecting sliding up is done at {@link #onInterceptTouchEvent}.
            return true;
        }
        // In the sliding input mode. {@link MotionEvent} should be forwarded to
        // {@link MoreSuggestionsView}.
        final int index = me.getActionIndex();
        final int x = mMoreSuggestionsView.translateX((int) me.getX(index));
        final int y = mMoreSuggestionsView.translateY((int) me.getY(index));
        me.setLocation(x, y);
        if (!mNeedsToTransformTouchEventToHoverEvent) {
            mMoreSuggestionsView.onTouchEvent(me);
            return true;
        }
        // In sliding suggestion mode with accessibility mode on, a touch event should be
        // transformed to a hover event.
        final int width = mMoreSuggestionsView.getWidth();
        final int height = mMoreSuggestionsView.getHeight();
        final boolean onMoreSuggestions = (x >= 0 && x < width && y >= 0 && y < height);
        if (!onMoreSuggestions && !mIsDispatchingHoverEventToMoreSuggestions) {
            // Just drop this touch event because dispatching hover event isn't started yet and
            // the touch event isn't on {@link MoreSuggestionsView}.
            return true;
        }
        final int hoverAction;
        if (onMoreSuggestions && !mIsDispatchingHoverEventToMoreSuggestions) {
            // Transform this touch event to a hover enter event and start dispatching a hover
            // event to {@link MoreSuggestionsView}.
            mIsDispatchingHoverEventToMoreSuggestions = true;
            hoverAction = MotionEvent.ACTION_HOVER_ENTER;
        } else if (me.getActionMasked() == MotionEvent.ACTION_UP) {
            // Transform this touch event to a hover exit event and stop dispatching a hover event
            // after this.
            mIsDispatchingHoverEventToMoreSuggestions = false;
            mNeedsToTransformTouchEventToHoverEvent = false;
            hoverAction = MotionEvent.ACTION_HOVER_EXIT;
        } else {
            // Transform this touch event to a hover move event.
            hoverAction = MotionEvent.ACTION_HOVER_MOVE;
        }
        me.setAction(hoverAction);
        mMoreSuggestionsView.onHoverEvent(me);
        return true;
    }

    @Override
    public void onClick(final View view) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                Constants.CODE_UNSPECIFIED, this);
        if (view == mVoiceKey) {
            mListener.onCodeInput(Constants.CODE_SHORTCUT,
                    Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE,
                    false /* isKeyRepeat */);
            return;
        }

        final Object tag = view.getTag();
        // {@link Integer} tag is set at
        // {@link SuggestionStripLayoutHelper#setupWordViewsTextAndColor(SuggestedWords,int)} and
        // {@link SuggestionStripLayoutHelper#layoutPunctuationSuggestions(SuggestedWords,ViewGroup}
        if (tag instanceof Integer) {
            final int index = (Integer) tag;
            if (index >= mSuggestedWords.size()) {
                return;
            }
            final SuggestedWordInfo wordInfo = mSuggestedWords.getInfo(index);
            mListener.pickSuggestionManually(wordInfo);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        dismissMoreSuggestionsPanel();
    }

    @Override
    protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh) {
        // Called by the framework when the size is known. Show the important notice if applicable.
        // This may be overriden by showing suggestions later, if applicable.
    }

    /**
     * 현재 뷰들의 visibility를 한 줄로 덤프해 준다 (D 레벨)
     */
    private void dumpVis(String where) {
        Log.d(TAG, String.format(
                "%s  [Strip=%s  Input=%s  Buttons=%s] this=%s",
                where,
                visToStr(getVisibility()),
                visToStr(mInputContainer.getVisibility()),
                visToStr(mButtonsContainer.getVisibility()),
                this.hashCode()));
    }

    private static String visToStr(int v) {
        switch (v) {
            case VISIBLE:
                return "V";
            case INVISIBLE:
                return "I";
            case GONE:
                return "G";
            default:
                return String.valueOf(v);
        }
    }
}