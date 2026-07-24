package com.autoreplybot;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

public class ReplyHistoryActivity extends AppCompatActivity {
    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_reply_history);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        ReplyHistoryAdapter adapter = new ReplyHistoryAdapter();
        recycler.setAdapter(adapter);
        View progress = findViewById(R.id.progress);
        TextView empty = findViewById(R.id.text_empty);
        progress.setVisibility(View.VISIBLE);
        new ReplyHistoryRepository(this).loadRecent(100).addOnCompleteListener(task -> {
            progress.setVisibility(View.GONE);
            if (!task.isSuccessful() || task.getResult() == null) {
                Toast.makeText(this, R.string.history_load_failed, Toast.LENGTH_LONG).show();
                return;
            }
            adapter.submit(task.getResult());
            empty.setVisibility(task.getResult().isEmpty() ? View.VISIBLE : View.GONE);
        });
    }
}
