package com.gathin.tvhome;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.util.AttributeSet;
import android.widget.TextClock;

/**
 * Home's date: a TextClock that can carry text after the date on the same line (header_weather_app's icon and
 * words), kept through every tick, in the date's own font, case and colour.
 */
public class DateClock extends TextClock {
    /** What the clock last wrote: the date alone. */
    private CharSequence date;
    /** After the date; null for nothing. */
    private CharSequence suffix;

    public DateClock(Context context) {
        super(context);
    }

    public DateClock(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setSuffix(CharSequence suffix) {
        this.suffix = suffix;
        if (date != null) setText(date, BufferType.NORMAL);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        date = text;
        super.setText(suffix == null || text == null ? text : new SpannableStringBuilder(text).append(suffix), type);
    }
}
