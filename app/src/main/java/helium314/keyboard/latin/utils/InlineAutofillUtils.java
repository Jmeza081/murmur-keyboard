/*
 * Copyright (C) 2019 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0
 */

package helium314.keyboard.latin.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Size;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.inline.InlineContentView;
import android.widget.inline.InlinePresentationSpec;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.autofill.inline.UiVersions;
import androidx.autofill.inline.UiVersions.StylesBuilder;
import androidx.autofill.inline.common.ImageViewStyle;
import androidx.autofill.inline.common.TextViewStyle;
import androidx.autofill.inline.common.ViewStyle;
import androidx.autofill.inline.v1.InlineSuggestionUi;
import androidx.autofill.inline.v1.InlineSuggestionUi.Style;

import java.util.ArrayList;
import java.util.List;

import helium314.keyboard.latin.R;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.settings.Settings;

@SuppressLint("RestrictedApi")
@RequiresApi(api = Build.VERSION_CODES.R)
public class InlineAutofillUtils {

    public static InlineSuggestionsRequest createInlineSuggestionRequest(final Context context) {
        final Colors colors = Settings.getValues().mColors;
        // Murmur: pill chips like the strip's other chips (key color, 18 dp radius)
        final int chipBgDrawableId = R.drawable.murmur_autofill_chip;
        final int chipBgColor = colors.get(ColorType.KEY_BACKGROUND);
        final float density = context.getResources().getDisplayMetrics().density;
        final int pad = (int) (12 * density);
        final int chipTextColor = colors.get(ColorType.KEY_TEXT);
        final int chipTextHintColor = colors.get(ColorType.KEY_HINT_TEXT);

        StylesBuilder stylesBuilder = UiVersions.newStylesBuilder();
        Style style = InlineSuggestionUi.newStyleBuilder()
                // Murmur: icon-only chips are 40 dp squares (rounded) with the icon centered
                .setSingleIconChipStyle(
                        new ViewStyle.Builder()
                                .setBackground(Icon.createWithResource(context, chipBgDrawableId).setTint(chipBgColor))
                                .setPadding(0, 0, 0, 0)
                                .build())
                .setSingleIconChipIconStyle(new ImageViewStyle.Builder()
                        .setLayoutMargin(0, 0, 0, 0)
                        .setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE)
                        .build())
                .setChipStyle(
                        new ViewStyle.Builder()
                                .setBackground(Icon.createWithResource(context, chipBgDrawableId).setTint(chipBgColor))
                                // the start icon brings its own square backdrop, so less room on that side
                                .setPadding((int) (8 * density), 0, (int) (16 * density), 0)
                                .build())
                .setStartIconStyle(new ImageViewStyle.Builder().setLayoutMargin(0, 0, (int) (10 * density), 0).build())
                .setTitleStyle(
                        new TextViewStyle.Builder()
                                .setTextColor(chipTextColor)
                                .setTextSize(14)
                                .build())
                .setSubtitleStyle(
                        new TextViewStyle.Builder()
                                .setTextColor(chipTextHintColor)
                                .setTextSize(11.5f)
                                .build())
                .setEndIconStyle(new ImageViewStyle.Builder().setLayoutMargin(0, 0, 0, 0).build())
                .build();
        stylesBuilder.addStyle(style);
        Bundle stylesBundle = stylesBuilder.build();

        final int height = (int) (40 * density); // Murmur: a 40 dp pill centered in the 48 dp strip
        final Size min = new Size(height, height); // Murmur: an icon-only chip is square
        // Murmur: wide enough for one chip to fill the strip; createView picks each chip's actual width
        final Size max = new Size(context.getResources().getDisplayMetrics().widthPixels, height);

        // Three InlinePresentationSpec are required for some password managers
        final ArrayList<InlinePresentationSpec> presentationSpecs = new ArrayList<>();
        presentationSpecs.add(new InlinePresentationSpec.Builder(min, max).setStyle(stylesBundle).build());
        presentationSpecs.add(new InlinePresentationSpec.Builder(min, max).setStyle(stylesBundle).build());
        presentationSpecs.add(new InlinePresentationSpec.Builder(min, max).setStyle(stylesBundle).build());

        return new InlineSuggestionsRequest.Builder(presentationSpecs)
                .setMaxSuggestionCount(6)
                .build();
    }

    public static InlineContentClipView createView(final List<InlineSuggestion> inlineSuggestions,
                                                   final Context context) {
        LinearLayout container = new LinearLayout(context);
        // Murmur: chips centered vertically, 6 dp apart, inset from the strip's left edge
        final int gap = (int) (6 * context.getResources().getDisplayMetrics().density);
        container.setGravity(android.view.Gravity.CENTER_VERTICAL);
        container.setPadding(gap, 0, gap, 0);
        // Murmur (design/autofill-chips.html, A + C): the first two suggestions, each as wide as its own text,
        // then a "+N" chip that opens every login in a sheet; pinned chips (1Password's icon) stay square, last.
        final float density = context.getResources().getDisplayMetrics().density;
        final int height = (int) (40 * density);
        final List<InlineSuggestion> others = new ArrayList<>();
        final List<InlineSuggestion> pinned = new ArrayList<>();
        for (InlineSuggestion s : inlineSuggestions) (s.getInfo().isPinned() ? pinned : others).add(s);
        final int shown = Math.min(2, others.size());
        final List<InlineSuggestion> ordered = new ArrayList<>(others.subList(0, shown));
        ordered.addAll(pinned);
        final View[] slots = new View[ordered.size() + 1]; // + the "+N" chip, which sits after the suggestions
        final int moreSlot = shown;
        if (others.size() > shown) {
            // A suggestion can only be drawn once, so the extra logins are drawn now, at the sheet's width,
            // and the sheet shows those views when "+N" is tapped
            final List<InlineSuggestion> extra = others.subList(shown, others.size());
            final View[] sheetViews = new View[extra.size()];
            final int sheetWidth = context.getResources().getDisplayMetrics().widthPixels - (int) (48 * density);
            for (int i = 0; i < extra.size(); i++) {
                final int slot = i;
                extra.get(i).inflate(context, new Size(sheetWidth, height), context.getMainExecutor(), (view) -> {
                    if (view == null) return;
                    view.setZOrderedOnTop(true);
                    sheetViews[slot] = view;
                });
            }
            final View more = moreChip(context, extra.size(), height, () ->
                    dev.murmur.autofill.AutofillSheet.INSTANCE.show(context, sheetViews));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height);
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            lp.setMarginEnd(gap);
            more.setLayoutParams(lp);
            slots[moreSlot] = more;
            int index = 0;
            for (int j = 0; j < moreSlot; j++) if (slots[j] != null) index++;
            container.addView(more, index);
        }
        for (int i = 0; i < ordered.size(); i++) {
            final int slot = i < shown ? i : i + 1; // pinned chips go after "+N"
            final InlineSuggestion inlineSuggestion = ordered.get(i);
            final Size size = new Size(ViewGroup.LayoutParams.WRAP_CONTENT, inlineSuggestion.getInfo().isPinned()
                    ? ViewGroup.LayoutParams.WRAP_CONTENT : height);
            inlineSuggestion.inflate(context, size, context.getMainExecutor(), (view) -> {
                if (view == null) return;
                // Murmur: draw the chip above the keyboard window; Murmur's opaque strip hides it otherwise
                view.setZOrderedOnTop(true);
                // keep the size the password manager rendered the chip at; only add spacing
                final ViewGroup.LayoutParams given = view.getLayoutParams();
                final LinearLayout.LayoutParams lp = given != null ? new LinearLayout.LayoutParams(given)
                        : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = android.view.Gravity.CENTER_VERTICAL;
                lp.setMarginEnd(gap);
                view.setLayoutParams(lp);
                slots[slot] = view;
                // insert after every earlier slot that has already arrived
                int index = 0;
                for (int j = 0; j < slot; j++) if (slots[j] != null) index++;
                container.addView(view, index);
            });
        }

        HorizontalScrollView inlineSuggestionView = new HorizontalScrollView(context);
        inlineSuggestionView.setHorizontalScrollBarEnabled(false);
        inlineSuggestionView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        inlineSuggestionView.addView(container);

        InlineContentClipView scrollableSuggestionsClip = new InlineContentClipView(context);
        scrollableSuggestionsClip.addView(inlineSuggestionView);
        return scrollableSuggestionsClip;
    }

    /** Murmur: the "+N" chip, outlined in the key text color, that opens the saved-logins sheet. */
    private static View moreChip(final Context context, final int count, final int height, final Runnable onClick) {
        final float density = context.getResources().getDisplayMetrics().density;
        final Colors colors = Settings.getValues().mColors;
        final android.widget.TextView v = new android.widget.TextView(context);
        v.setText("+" + count);
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        v.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        v.setTextColor(colors.get(ColorType.KEY_TEXT));
        v.setGravity(android.view.Gravity.CENTER);
        v.setMinWidth(height);
        v.setPadding((int) (12 * density), 0, (int) (12 * density), 0);
        final android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setStroke((int) (1.5f * density), colors.get(ColorType.KEY_HINT_TEXT));
        v.setBackground(bg);
        v.setContentDescription(count + " more saved logins");
        v.setOnClickListener(x -> onClick.run());
        return v;
    }

    /**
     * This class is a container for showing {@link InlineContentView}s for cases
     * where you want to ensure they appear only in a given area in your app. An
     * example is having a scrollable list of items. Note that without this container
     * the InlineContentViews' surfaces would cover parts of your app as these surfaces
     * are owned by another process and always appearing on top of your app.
     */
    public static class InlineContentClipView extends FrameLayout {
        @NonNull
        private final ViewTreeObserver.OnDrawListener mOnDrawListener =
                this::clipDescendantInlineContentViews;
        @NonNull
        private final Rect mParentBounds = new Rect();
        @NonNull
        private final Rect mContentBounds = new Rect();
        public InlineContentClipView(@NonNull Context context) {
            this(context, /*attrs*/ null, /*defStyleAttr*/ 0);
        }
        public InlineContentClipView(@NonNull Context context, @Nullable AttributeSet attrs,
                                     @AttrRes int defStyleAttr) {
            super(context, attrs, defStyleAttr);
            SurfaceView mBackgroundView = new SurfaceView(context);
            mBackgroundView.setZOrderOnTop(true);
            mBackgroundView.getHolder().setFormat(PixelFormat.TRANSPARENT);
            addView(mBackgroundView);
        }
        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnDrawListener(mOnDrawListener);
        }
        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            getViewTreeObserver().removeOnDrawListener(mOnDrawListener);
        }
        private void clipDescendantInlineContentViews() {
            mParentBounds.right = getWidth();
            mParentBounds.bottom = getHeight();
            clipDescendantInlineContentViews(this);
        }
        private void clipDescendantInlineContentViews(@Nullable View root) {
            if (root == null) return;
            if (root instanceof InlineContentView inlineContentView) {
                mContentBounds.set(mParentBounds);
                offsetRectIntoDescendantCoords(inlineContentView, mContentBounds);
                inlineContentView.setClipBounds(mContentBounds);
                return;
            }
            if (root instanceof ViewGroup rootGroup) {
                final int childCount = rootGroup.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    final View child = rootGroup.getChildAt(i);
                    clipDescendantInlineContentViews(child);
                }
            }
        }
    }
}