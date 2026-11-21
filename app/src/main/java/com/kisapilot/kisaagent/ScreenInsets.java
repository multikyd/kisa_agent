package com.kisapilot.kisaagent;

import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

final class ScreenInsets {
    private ScreenInsets() {}

    static void apply(View content) {
        int left = content.getPaddingLeft();
        int top = content.getPaddingTop();
        int right = content.getPaddingRight();
        int bottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    static void setContentWithToolbar(AppCompatActivity activity, View content, CharSequence title) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        Toolbar toolbar = new Toolbar(activity);
        toolbar.setBackgroundColor(Color.BLACK);
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setTitle(title);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description);
        toolbar.setNavigationOnClickListener(view -> activity.getOnBackPressedDispatcher().onBackPressed());
        int toolbarHeight = Math.round(56 * activity.getResources().getDisplayMetrics().density);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, toolbarHeight));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        activity.setContentView(root);
        apply(root);
    }
}
