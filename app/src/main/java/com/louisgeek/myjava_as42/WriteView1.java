package com.louisgeek.myjava_as42;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Created by louisgeek on 2021/6/23.
 */
public class WriteView1 extends View {
    private static final float TOUCH_TOLERANCE = 4;
    // 画笔，定义绘制属性
    private Paint mPaint;
    // 绘制路径
    private Path mPath;
    // 画布及其底层位图
    private Bitmap mBitmap;
    private Canvas mCanvas;
    private float mX, mY;

    private static final int MODE_PEN = 0;
    private static final int MODE_ERASER = 1;
    private int mMode = MODE_PEN;

    public WriteView1(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }


    /**
     * 初始化工作
     */
    private void initialize() {

        // 绘制自由曲线用的画笔
        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setDither(true);
        mPaint.setColor(Color.BLACK);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeJoin(Paint.Join.ROUND);//设置圆弧连接
        mPaint.setStrokeCap(Paint.Cap.ROUND);//设置圆线帽
        mPaint.setStrokeWidth(6);
        mPaint.setPathEffect(new CornerPathEffect(3));

        mPath = new Path();

    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        mCanvas = new Canvas(mBitmap);//绘制Bitamp的画布对象
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touch_start(x, y);
                invalidate();
                break;
            case MotionEvent.ACTION_MOVE:
                touch_move(x, y);
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
                touch_up();
                invalidate();
                mPath.reset();
                break;
            default:
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        //如果不现在自己的Bitmap上绘制一层颜色，会出现锯齿
        mCanvas.drawColor(Color.CYAN);
        //将路径绘制在自己的Bitmap上
        mCanvas.drawPath(mPath, mPaint);
        //将Bitmap绘制到界面上
        canvas.drawBitmap(mBitmap, 0, 0, mPaint);

        // 直接绘制路径
//        canvas.drawPath(mPath, mPaint);
    }


    private void touch_start(float x, float y) {
        mPath.moveTo(x, y);
        mX = x;
        mY = y;
    }

    private void touch_move(float x, float y) {
        if (getUsefulPoint(x, y, mX, mY)) {
            mPath.quadTo(mX, mY, (x + mX) / 2, (y + mY) / 2);
//            mPath.quadTo((x + 2 * mX) / 3f, (y + 2 * mY) / 3f, (x + mX) / 2, (y + mY) / 2);
            mX = x;
            mY = y;
        }
    }

    private void touch_up() {
        mPath.lineTo(mX, mY);
    }

    /**
     * 过滤掉距离过小的点
     */
    private boolean getUsefulPoint(float x1, float y1, float x2, float y2) {
        return (Math.abs(x1 - x2) >= TOUCH_TOLERANCE || Math.abs(y1 - y2) >= TOUCH_TOLERANCE);
    }

    /**
     * 清除整个图像
     */
    public void clear() {
        // 清除方法1：重新生成位图
        // mBitmap = Bitmap
        // .createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
        // mCanvas = new Canvas(mBitmap);
        // 清除方法2：将位图清除为白色
        if (mBitmap != null)
            mBitmap.eraseColor(Color.TRANSPARENT);
        // 两种清除方法都必须加上后面这两步：
        // 路径重置
        mPath.reset();
        // 刷新绘制
        invalidate();
    }
    public void switchMode() {
        if (mMode == MODE_PEN) {
            //切换成橡皮擦
            mPaint.setColor(Color.TRANSPARENT);
//            mPaint.setAlpha(0);
            mPaint.setStrokeWidth(9);
            mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
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