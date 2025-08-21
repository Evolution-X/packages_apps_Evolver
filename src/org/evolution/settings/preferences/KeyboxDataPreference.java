package org.evolution.settings.preferences;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settings.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;

public class KeyboxDataPreference extends Preference {

    private static final String TAG = "KeyboxDataPref";
    private ActivityResultLauncher<Intent> mFilePickerLauncher;

    public KeyboxDataPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.keybox_data_pref);
    }

    public void setFilePickerLauncher(ActivityResultLauncher<Intent> launcher) {
        this.mFilePickerLauncher = launcher;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final Context ctx = getContext();
        final ContentResolver cr = ctx.getContentResolver();

        TextView title = (TextView) holder.findViewById(R.id.title);
        TextView summary = (TextView) holder.findViewById(R.id.summary);
        ImageButton deleteButton = (ImageButton) holder.findViewById(R.id.delete_button);

        title.setText(getTitle());

        boolean hasData = Settings.Secure.getString(
                cr, Settings.Secure.KEYBOX_DATA) != null;

        summary.setText(ctx.getString(
                hasData ? R.string.keybox_data_loaded_summary : R.string.keybox_data_summary));

        deleteButton.setVisibility(hasData ? View.VISIBLE : View.GONE);
        deleteButton.setEnabled(hasData);

        holder.itemView.setOnClickListener(v -> {
            if (mFilePickerLauncher != null) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/xml", "application/xml"});
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                mFilePickerLauncher.launch(intent);
            }
        });

        deleteButton.setOnClickListener(v -> {
            if (!callChangeListener(Boolean.FALSE)) return;
            Settings.Secure.putString(cr, Settings.Secure.KEYBOX_DATA, null);
            Toast.makeText(ctx, ctx.getString(R.string.keybox_toast_file_cleared), Toast.LENGTH_SHORT).show();
            notifyChanged();
        });
    }


    public void handleFileSelected(Uri uri) {
        final Context ctx = getContext();
        final ContentResolver cr = ctx.getContentResolver();

        if (uri == null) {
            Toast.makeText(ctx,
                ctx.getString(R.string.keybox_toast_invalid_file_selected), Toast.LENGTH_SHORT).show();
            return;
        }

        final String type = cr.getType(uri);
        boolean isXmlMime = "text/xml".equals(type) || "application/xml".equals(type);
        boolean hasXmlExt = (uri.getPath() != null && uri.getPath().toLowerCase().endsWith(".xml"));
        if (!isXmlMime && !hasXmlExt) {
            Toast.makeText(ctx,
                ctx.getString(R.string.keybox_toast_invalid_file_selected), Toast.LENGTH_SHORT).show();
            return;
        }

        try (InputStream inputStream = cr.openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder xmlContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                xmlContent.append(line).append('\n');
            }

            String xml = xmlContent.toString();
            if (!validateXml(xml)) {
                Toast.makeText(ctx,
                    ctx.getString(R.string.keybox_toast_missing_data), Toast.LENGTH_SHORT).show();
                return;
            }

            if (!callChangeListener(Boolean.TRUE)) return;
            Settings.Secure.putString(cr, Settings.Secure.KEYBOX_DATA, xml);
            Toast.makeText(ctx,
                    ctx.getString(R.string.keybox_toast_file_loaded), Toast.LENGTH_SHORT).show();
            notifyChanged();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read XML file", e);
            Toast.makeText(ctx,
                ctx.getString(R.string.keybox_toast_invalid_file_selected), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean validateXml(String xml) {
        boolean hasEcdsaKey = false, hasRsaKey = false;
        boolean hasEcdsaPrivKey = false, hasRsaPrivKey = false;
        int ecdsaCertCount = 0, rsaCertCount = 0;
        int numberOfKeyboxes = -1;

        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new StringReader(xml));

            String currentAlg = null;

            for (int eventType = parser.next(); eventType != XmlPullParser.END_DOCUMENT; eventType = parser.next()) {
                if (eventType == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    switch (name) {
                        case "NumberOfKeyboxes":
                            parser.next(); // move to TEXT event
                            if (parser.getEventType() == XmlPullParser.TEXT) {
                                try {
                                    numberOfKeyboxes = Integer.parseInt(parser.getText().trim());
                                } catch (NumberFormatException e) {
                                    numberOfKeyboxes = -1;
                                }
                            }
                            break;

                        case "Key":
                            currentAlg = parser.getAttributeValue(null, "algorithm");
                            if ("ecdsa".equalsIgnoreCase(currentAlg)) {
                                hasEcdsaKey = true;
                            } else if ("rsa".equalsIgnoreCase(currentAlg)) {
                                hasRsaKey = true;
                            } else {
                                currentAlg = null; // unsupported key
                            }
                            break;

                        case "PrivateKey": {
                            String format = parser.getAttributeValue(null, "format");
                            if (!"pem".equalsIgnoreCase(format)) {
                                Log.w(TAG, "Invalid or missing format for PrivateKey");
                                return false;
                            }
                            if ("ecdsa".equalsIgnoreCase(currentAlg)) {
                                hasEcdsaPrivKey = true;
                            } else if ("rsa".equalsIgnoreCase(currentAlg)) {
                                hasRsaPrivKey = true;
                            }
                            break;
                        }

                        case "Certificate": {
                            String format = parser.getAttributeValue(null, "format");
                            if (!"pem".equalsIgnoreCase(format)) {
                                Log.w(TAG, "Invalid or missing format for Certificate");
                                return false;
                            }

                            if ("ecdsa".equalsIgnoreCase(currentAlg)) {
                                ecdsaCertCount++;
                            } else if ("rsa".equalsIgnoreCase(currentAlg)) {
                                rsaCertCount++;
                            }
                            break;
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG && "Key".equals(parser.getName())) {
                    currentAlg = null;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "XML validation failed", e);
            return false;
        }

        return numberOfKeyboxes == 1
                && hasEcdsaKey && hasEcdsaPrivKey && ecdsaCertCount >= 1
                && hasRsaKey && hasRsaPrivKey && rsaCertCount >= 1;
    }
}
