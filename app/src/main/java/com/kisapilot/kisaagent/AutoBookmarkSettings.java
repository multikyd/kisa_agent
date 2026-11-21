package com.kisapilot.kisaagent;

import android.content.Intent;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;

final class AutoBookmarkSettings {
    static void attach(AppCompatActivity activity, LinearLayout layout) {
        Button settings=new Button(activity);
        settings.setText(R.string.bookmark_storage_settings);
        settings.setAllCaps(false);
        settings.setOnClickListener(v -> activity.startActivity(new Intent(activity,BookmarkServerActivity.class)));
        layout.addView(settings);
    }
}
