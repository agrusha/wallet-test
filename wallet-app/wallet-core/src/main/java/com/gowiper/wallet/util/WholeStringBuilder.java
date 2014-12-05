package com.gowiper.wallet.util;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;

public class WholeStringBuilder extends SpannableStringBuilder {
    public static CharSequence bold(final CharSequence text) {
        return new WholeStringBuilder(text, new StyleSpan(Typeface.BOLD));
    }

    public WholeStringBuilder(final CharSequence text, final Object span) {
        super(text);

        setSpan(span, 0, text.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
