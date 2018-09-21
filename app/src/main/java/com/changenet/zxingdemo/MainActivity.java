package com.changenet.zxingdemo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.google.zxing.WriterException;

public class MainActivity extends AppCompatActivity {
    private LinearLayout linearLayout;
    private ImageView img;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        linearLayout = findViewById(R.id.ll);
        img = findViewById(R.id.img);

        LinearLayout ly = new LinearLayout(this);
        ly.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        Button button = new Button(this);
        button.setText("点击生成带图片的二维码");
        button.setGravity(Gravity.CENTER);

        Button button1 = new Button(this);
        button1.setText("生成不带图片的二维码");
        button1.setPadding(0, 20, 0, 0);

        ly.addView(button);
        ly.addView(button1);

        linearLayout.addView(ly);


        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    // 调用方法createCode生成二维码
                    Bitmap logo = BitmapFactory.decodeResource(MainActivity.this.getResources(), R.drawable.ic_launcher);
                    Bitmap bm = CodeUtils.createCode(MainActivity.this, "生成带图片的二维码", logo);
                    // 将二维码在界面中显示
                    img.setImageBitmap(bm);
                } catch (WriterException e) {
                    e.printStackTrace();
                }
            }
        });
        button1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Bitmap bm = CodeUtils.createCode(MainActivity.this, "生成没有图片的二维码");
                    img.setImageBitmap(bm);
                } catch (WriterException e) {
                    e.printStackTrace();
                }
            }
        });

    }
}
