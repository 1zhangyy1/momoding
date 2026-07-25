package app.momoding.feature.share;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ShareSourceFixtureActivity extends Activity {
    public static final String EXTRA_SCENARIO = "share-fixture-scenario";
    public static final String SCENARIO_TEXT = "text";
    public static final String SCENARIO_SINGLE_IMAGE = "single-image";
    public static final String SCENARIO_MULTI_IMAGE = "multi-image";
    public static final String SCENARIO_TEXT_FILE = "text-file";
    public static final String SCENARIO_MISSING_IMAGE = "missing-image";
    public static final String SCENARIO_CANCEL = "cancel";
    public static final String TEXT_VALUE = "https://example.test/share-browser";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String scenario = getIntent().getStringExtra(EXTRA_SCENARIO);
        Intent send;
        if (SCENARIO_TEXT.equals(scenario)) {
            send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Browser page")
                .putExtra(Intent.EXTRA_TEXT, TEXT_VALUE);
        } else if (SCENARIO_SINGLE_IMAGE.equals(scenario)) {
            send = streamIntent(Intent.ACTION_SEND, "image/png",
                Arrays.asList(png("single.png", Color.rgb(38, 120, 220))));
        } else if (SCENARIO_MULTI_IMAGE.equals(scenario)) {
            send = streamIntent(Intent.ACTION_SEND_MULTIPLE, "image/png", Arrays.asList(
                png("multi-one.png", Color.rgb(80, 150, 60)),
                png("multi-two.png", Color.rgb(220, 100, 50))));
        } else if (SCENARIO_TEXT_FILE.equals(scenario)) {
            send = streamIntent(Intent.ACTION_SEND, "text/markdown", Arrays.asList(
                file("shared-notes.md", "# Share sheet\nText file".getBytes(StandardCharsets.UTF_8))));
        } else if (SCENARIO_MISSING_IMAGE.equals(scenario)) {
            send = streamIntent(Intent.ACTION_SEND, "image/png", Arrays.asList(
                uriFor(new File(root(), "missing.png"))));
        } else if (SCENARIO_CANCEL.equals(scenario)) {
            send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "This share must be cancelled");
        } else {
            throw new IllegalArgumentException("Unknown share fixture scenario");
        }
        startActivity(Intent.createChooser(send, "Share sheet Share fixture"));
        finish();
    }

    private Intent streamIntent(String action, String mimeType, List<Uri> uris) {
        Intent send = new Intent(action).setType(mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clip = ClipData.newUri(getContentResolver(), "Share sheet fixture", uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clip);
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        } else {
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        }
        return send;
    }

    private Uri png(String name, int color) {
        File target = new File(root(), name);
        Bitmap bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream output = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("PNG fixture failed");
            }
        } catch (Exception error) {
            throw new IllegalStateException(error);
        } finally {
            bitmap.recycle();
        }
        return uriFor(target);
    }

    private Uri file(String name, byte[] bytes) {
        File target = new File(root(), name);
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(bytes);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return uriFor(target);
    }

    private File root() {
        File root = new File(getCacheDir(), "share-fixture");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Fixture root failed");
        return root;
    }

    private Uri uriFor(File file) {
        return new Uri.Builder().scheme("content")
            .authority(getPackageName() + ".share-fixture")
            .appendPath(file.getName()).build();
    }
}
