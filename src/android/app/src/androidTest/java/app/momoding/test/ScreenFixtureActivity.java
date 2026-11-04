package app.momoding.test;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Standalone target APK: Android classes only, without the target app's Kotlin runtime. */
public class ScreenFixtureActivity extends Activity {
    private SharedPreferences prefs;
    private TextView counter;
    private TextView deletions;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("m41_screen_fixture", MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 100, 40, 40);
        TextView title = new TextView(this);
        title.setText("M41 Screen Fixture");
        title.setTextSize(24f);
        layout.addView(title);
        counter = new TextView(this);
        counter.setTextSize(22f);
        layout.addView(counter);
        deletions = new TextView(this);
        deletions.setTextSize(22f);
        layout.addView(deletions);
        Button mark = new Button(this);
        mark.setText("M41 MARK");
        mark.setOnClickListener(v -> setCount(prefs.getInt("count", 0) + 1));
        layout.addView(mark);
        Button send = new Button(this);
        send.setText("M41 SEND FIXTURE");
        send.setOnClickListener(v -> setCount(prefs.getInt("count", 0) + 1));
        layout.addView(send);
        Button reset = new Button(this);
        reset.setText("M41 RESET");
        reset.setOnClickListener(v -> reset());
        layout.addView(reset);
        Button delete = new Button(this);
        delete.setText("M41 DELETE FIXTURE");
        delete.setOnClickListener(v -> {
            prefs.edit().putInt("deletions", prefs.getInt("deletions", 0) + 1).commit();
            setCount(0);
        });
        layout.addView(delete);
        EditText input = new EditText(this);
        input.setHint("M41 fixture text");
        layout.addView(input);
        setContentView(layout);
        if (getIntent().getBooleanExtra("reset", false)) reset();
        else setCount(prefs.getInt("count", 0));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("reset", false)) reset();
    }

    private void reset() {
        prefs.edit().putInt("deletions", 0).commit();
        setCount(0);
    }

    private void setCount(int value) {
        prefs.edit().putInt("count", value).commit();
        counter.setText("M41 counter: " + value);
        deletions.setText("M41 deletions: " + prefs.getInt("deletions", 0));
    }
}
