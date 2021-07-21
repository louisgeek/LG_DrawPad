package com.louisgeek.myjava_as42;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Created by louisgeek on 2021/6/21.
 * lineTo
 */
public class MyVi0 extends View {
    private Paint mPaint;
    private Path mPath;

    public MyVi0(Context context) {
        super(context);
        init();
    }


    public MyVi0(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MyVi0(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public MyVi0(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        mPaint = new Paint();
        //设置抗锯齿,锯齿不显示
        mPaint.setAntiAlias(true);
        //设置非填充
        mPaint.setStyle(Paint.Style.STROKE);
        //设置笔画宽度
        mPaint.setStrokeWidth(5);
        //设置签名颜色
        mPaint.setColor(Color.BLUE);

        //
        mPath = new Path();
    }


    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                reset();
                mPath.moveTo(event.getX(), event.getY());
                //消费
                return true;
            case MotionEvent.ACTION_MOVE:
                mPath.lineTo(event.getX(), event.getY());
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);

    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawPath(mPath, mPaint);
    }

    private void reset() {
        mPath.reset();
        invalidate();
    }
}
