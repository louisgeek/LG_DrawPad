package com.louisgeek.myjava_as42;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Created by louisgeek on 2021/6/21.
 */
public class MyVi3 extends View {
    private static final String TAG = "MyVi3";
    private static final float TOUCH_TOLERANCE = 3;
    private PaintFlagsDrawFilter mPaintFlagsDrawFilter;

    private Path mPath;
    private Paint mPaint;

    private float mX;
    private float mY;

    ///
    private Canvas mCanvas;
    private Bitmap mBitmap;
    private Paint mBitmapPaint;

    private static final int MODE_PEN = 0;
    private static final int MODE_ERASER = 1;
    private int mMode = MODE_PEN;

    //
    private PorterDuffXfermode mPorterDuffXfermode;

    public MyVi3(Context context) {
        super(context);
        init();
    }


    public MyVi3(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MyVi3(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public MyVi3(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }


    private void init() {
        //关闭硬件加速
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        //画布抗锯齿
        mPaintFlagsDrawFilter = new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        //
        mPaint = new Paint();
        //画笔抗锯齿,锯齿不显示，更加清楚
        mPaint.setAntiAlias(true);
        //是否抖动,更加饱满清晰
        mPaint.setDither(true);
        //
        mPaint.setFilterBitmap(true);
        //设置非填充
        mPaint.setStyle(Paint.Style.STROKE);
        //设置笔画宽度
        mPaint.setStrokeWidth(3);
        //设置签名颜色
//        mPaint.setColor(Color.BLUE);
        mPaint.setColor(Color.BLACK);
        //设置线条拐角样式：圆角，其他是锐角尖锐突出，梯形往回收
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        //设置画笔线条端点的样式；圆头， canvas.drawPoint 时候也有效
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        //
        mPath = new Path();

        //作用于橡皮擦状态下的 Paint
        mPorterDuffXfermode = new PorterDuffXfermode(PorterDuff.Mode.CLEAR);
        mBitmapPaint = new Paint();
        //画笔抗锯齿,锯齿不显示，更加清楚
        mBitmapPaint.setAntiAlias(true);
        //是否抖动,更加饱满清晰
        mBitmapPaint.setDither(true);
        //
        mBitmapPaint.setFilterBitmap(true);
    }

   /* @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        Log.e(TAG, "onMeasure: " + widthMeasureSpec);
        if (mBitmap == null) {
            mBitmap = Bitmap.createBitmap(getMeasuredWidth(), getMeasuredHeight(), Bitmap.Config.ARGB_8888);
            // 缓存的画布 把所有的 Path 画到这个画布中
            mCanvas = new Canvas(mBitmap);
            mCanvas.setDrawFilter(mPaintFlagsDrawFilter);
        }
    }*/

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBitmap = Bitmap.createBitmap(getMeasuredWidth(), getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        // 缓存的画布 把所有的 Path 画到这个画布中
        mCanvas = new Canvas(mBitmap);
        mCanvas.setDrawFilter(mPaintFlagsDrawFilter);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int bs = event.getButtonState();
//        Log.e(TAG, "onTouchEvent: " + bs);
    /*    int pointerId = event.getPointerId(0);
        try {
            if (event.getToolType(pointerId) != MotionEvent.TOOL_TYPE_STYLUS) {
                //触控笔判断
                return false;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }*/
        //
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX();
                float y = event.getY();
                mX = x;
                mY = y;
                mPath.moveTo(mX, mY);
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
                    //先在缓存画布画 Path
                    //如果不先在缓存 Bitmap上绘制一层颜色(不能是Color.TRANSPARENT)，否则会出现锯齿，即便你针对它设置抗锯齿也不生效
//                    mCanvas.drawColor(Color.WHITE);
                    mCanvas.drawPath(mPath, mPaint);
                    //触发 onDraw
                    invalidate();
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                //抬起来后就情况 否则全是后面设置的效果
                mPath.reset();
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
//        canvas.drawPath(mPath, mPaint);
        //一次性画出缓存画布形成的图
//  不能传入会变的 mPaint ，本意是想解决缓冲bitmap 锯齿问题      canvas.drawBitmap(mBitmap, 0, 0, mPaint);
//        canvas.drawBitmap(mBitmap, 0, 0, null);
        canvas.drawBitmap(mBitmap, 0, 0, mBitmapPaint);
        canvas.drawPath(mPath, mPaint);
    }


    public void switchMode() {
        if (mMode == MODE_PEN) {
            //切换成橡皮擦
            mPaint.setColor(Color.TRANSPARENT);
//            mPaint.setAlpha(0);
            mPaint.setStrokeWidth(9);
            mPaint.setXfermode(mPorterDuffXfermode);
            mMode = MODE_ERASER;
        } else if (mMode == MODE_ERASER) {
            //切换成画笔
            mPaint.setColor(Color.BLACK);
            mPaint.setStrokeWidth(3);
            mPaint.setXfermode(null);
            mMode = MODE_PEN;
        }

    }


}