package com.louisgeek.myjava_as42;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by louisgeek on 2021/6/21.
 * quadTo
 */
public class MyVi111 extends View {
    private static final String TAG = "MyVi1";
    private static final float TOUCH_TOLERANCE = 3;
    private PaintFlagsDrawFilter mPaintFlagsDrawFilter;

    private List<Paint> mPaintList;
    private List<Path> mPathList;

    private float mX;
    private float mY;

    private static final int MODE_PEN = 0;
    private static final int MODE_ERASER = 1;
    private int mMode = MODE_PEN;

    public MyVi111(Context context) {
        super(context);
        init();
    }


    public MyVi111(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MyVi111(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public MyVi111(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private Path mPath;

    private void init() {
        //关闭硬件加速
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        //画布抗锯齿
        mPaintFlagsDrawFilter = new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        //
        mPathList = new ArrayList<>();
        mPaintList = new ArrayList<>();
        //
//        mPath = new Path();
        //
    }

    private Paint createPaint() {
        if (mMode == MODE_PEN) {
            return createPenPaint();
        } else if (mMode == MODE_ERASER) {
            return createEraserPaint();
        }
        return null;
    }

    private Paint createPenPaint() {

        Paint mPaint = new Paint();
        //画笔抗锯齿,锯齿不显示，更加清楚
        mPaint.setAntiAlias(true);
        //
        mPaint.setFilterBitmap(true);
        //设置非填充
        mPaint.setStyle(Paint.Style.STROKE);

        //设置笔画宽度
        mPaint.setStrokeWidth(3);
        //设置签名颜色
//        mPaint.setColor(Color.BLUE);
        mPaint.setColor(Color.BLACK);
        //是否抖动,更加饱满清晰
        mPaint.setDither(true);
        //设置线条拐角样式：圆角，其他是锐角尖锐突出，梯形往回收
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        //设置画笔线条端点的样式；圆头， canvas.drawPoint 时候也有效
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        return mPaint;
    }

    private Paint createEraserPaint() {
        Paint mPaint = new Paint();
        //画笔抗锯齿,锯齿不显示，更加清楚
        mPaint.setAntiAlias(true);
        //
        mPaint.setFilterBitmap(true);
        //设置非填充
        mPaint.setStyle(Paint.Style.STROKE);
        //设置笔画宽度
        mPaint.setStrokeWidth(30);
        //设置签名颜色
//        mPaint.setColor(Color.BLUE);
        mPaint.setColor(Color.RED);
        //
        mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        //是否抖动,更加饱满清晰
        mPaint.setDither(true);
        //设置线条拐角样式：圆角，其他是锐角尖锐突出，梯形往回收
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        //设置画笔线条端点的样式；圆头， canvas.drawPoint 时候也有效
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        return mPaint;
    }


    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int bs = event.getButtonState();
//        Log.e(TAG, "onTouchEvent: " + bs);
        int pointerId = event.getPointerId(0);
        try {
            if (event.getToolType(pointerId) != MotionEvent.TOOL_TYPE_STYLUS) {
                //触控笔判断
                return false;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        //
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX();
                float y = event.getY();
                mX = x;
                mY = y;
                mPath = new Path();
                mPath.moveTo(mX, mY);
                //
                mPathList.add(mPath);
                mPaintList.add(createPaint());
                //消费
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                final float x = event.getX();
                final float y = event.getY();
                final float previousX = mX;
                final float previousY = mY;
                final float dx = Math.abs(x - mX);
                final float dy = Math.abs(y - mY);
                if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                    //二阶,如果没有 moveTo，则起点默认是(0,0)，后面如果连续调用quadTo，
                    // 前一个quadTo 的终点，就是下一个quadTo 函数的起点
                    float cX = (x + previousX) / 2;
                    float cY = (y + previousY) / 2;
                    mPath.quadTo(previousX, previousY, cX, cY);
                    //
                    mX = x;
                    mY = y;
                    //
                    invalidate();
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                break;
            }
            default:
        }

        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.setDrawFilter(mPaintFlagsDrawFilter);
        //
        for (int i = 0; i < mPathList.size(); i++) {
            canvas.drawPath(mPathList.get(i), mPaintList.get(i));
        }
    }

    int bs;

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        int pointerId = event.getPointerId(0);
        bs = event.getButtonState();
        Log.e(TAG, "onGenericMotionEvent: " + bs);
        if (event.getToolType(pointerId) == MotionEvent.TOOL_TYPE_STYLUS) {
        }

//        event.getDevice().getSources();
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Toast.makeText(getContext(), "test" + keyCode, Toast.LENGTH_SHORT).show();
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        Toast.makeText(getContext(), "testM" + keyCode + " C" + repeatCount, Toast.LENGTH_SHORT).show();
        return super.onKeyMultiple(keyCode, repeatCount, event);
    }

    public void switchMode() {
        if (mMode == MODE_PEN) {
            //切换成橡皮擦
            mMode = MODE_ERASER;
        } else if (mMode == MODE_ERASER) {
            //切换成画笔
            mMode = MODE_PEN;
        }

    }

    public void clear() {
        // 路径重置
        if (mPath != null) {
            mPath.reset();
        }
        for (Path path : mPathList) {
            path.reset();
        }
        // 刷新绘制
        invalidate();
    }
}
