package io.github.sumyabazar1.weather;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.HorizontalScrollView;

/**
 * A HorizontalScrollView that keeps its fading edge's length as the margin around the focused child, as the
 * launcher's recent-apps row does, without drawing the fade: on the Mi Box's GPU the fade made every scrolled frame
 * late, while scrolling without it drew none late.
 */
public class MarginScroller extends HorizontalScrollView {
    public MarginScroller(Context context) {
        super(context);
    }

    public MarginScroller(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected float getLeftFadingEdgeStrength() {
        return 0f;
    }

    @Override
    protected float getRightFadingEdgeStrength() {
        return 0f;
    }
}
