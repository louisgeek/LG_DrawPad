package com.louisgeek.myjava_as42;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

@RequiresApi(api = Build.VERSION_CODES.M)
public class TestActivity extends AppCompatActivity implements View.OnContextClickListener,
        GestureDetector.OnContextClickListener {
    private static final String TAG = "TestActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);
        setTitle("上面昨天和下面今天晚上");
        MyVi1 id_my_vi = findViewById(R.id.id_my_vi);
        MyVi111 id_my_vi2 = findViewById(R.id.id_my_vi2);
        Button id_btn = findViewById(R.id.id_btn);
        id_btn.setText("写字笔");
        id_btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (id_btn.getText().equals("写字笔")) {
                    id_btn.setText("橡皮擦");
                } else if (id_btn.getText().equals("橡皮擦")) {
                    id_btn.setText("写字笔");
                }
//                id_my_vi.switchMode();
                id_my_vi2.switchMode();
            }
        });
    }

    @Override
    public boolean onContextClick(View v) {
        Log.e(TAG, "onContextClick: " + v);
        Toast.makeText(this, "olick", Toast.LENGTH_SHORT).show();
        return false;
    }

    @Override
    public boolean onContextClick(MotionEvent e) {
        int bs = e.getButtonState();
        Log.e(TAG, "onContextClick: bs " + bs);
        Toast.makeText(this, bs, Toast.LENGTH_SHORT).show();
        return false;
    }
}