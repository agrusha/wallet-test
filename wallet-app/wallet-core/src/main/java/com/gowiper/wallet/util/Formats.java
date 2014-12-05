package com.gowiper.wallet.util;

import com.gowiper.wallet.Constants;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Formats {
    public static final Pattern PATTERN_MONETARY_SPANNABLE = Pattern.compile("(?:([\\p{Alpha}\\p{Sc}]++)\\s?+)?" // prefix
            + "([\\+\\-" + Constants.CURRENCY_PLUS_SIGN + Constants.CURRENCY_MINUS_SIGN + "]?+(?:\\d*+\\.\\d{0,2}+|\\d++))" // significant
            + "(\\d++)?"); // insignificant

    public static int PATTERN_GROUP_PREFIX = 1; // optional
    public static int PATTERN_GROUP_SIGNIFICANT = 2; // mandatory
    public static int PATTERN_GROUP_INSIGNIFICANT = 3; // optional

    private static final Pattern PATTERN_OUTER_HTML_PARAGRAPH = Pattern.compile("<p[^>]*>(.*)</p>\n?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static String maybeRemoveOuterHtmlParagraph(final String html) {
        final Matcher m = PATTERN_OUTER_HTML_PARAGRAPH.matcher(html);
        if (m.matches())
            return m.group(1);
        else
            return html;
    }
}
