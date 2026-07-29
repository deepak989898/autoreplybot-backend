package com.autoreplybot.remote;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.Map;

public class RemoteDeviceInfoActivity extends AppCompatActivity {
    private TextView summary;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_device_info);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        summary = findViewById(R.id.txt_device_info_summary);
        findViewById(R.id.btn_refresh_device_info).setOnClickListener(v -> refresh(true));
        refresh(false);
    }

    private void refresh(boolean publish) {
        Map<String, Object> info = RemoteDeviceInfoCollector.collect(this);
        Object basic = info.get("basic");
        Object battery = info.get("battery");
        summary.setText(String.valueOf(basic) + "\n\n" + battery);
        if (publish) {
            new RemoteDeviceInfoRepository(this).publishCurrent(info, () ->
                    runOnUiThread(() -> Toast.makeText(this, R.string.remote_device_info_synced,
                            Toast.LENGTH_SHORT).show()));
        }
    }
}
