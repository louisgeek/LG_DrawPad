package com.louisgeek.myjava_as42;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by louisgeek on 2021/6/21.
 * quadTo
 */
public class DrawPadView extends View {
    private static final String TAG = "DrawPadView";
    private static final float TOUCH_TOLERANCE = 3;
    private PaintFlagsDrawFilter mPaintFlagsDrawFilter;

    private List<Paint> mPaintList;
    private List<Path> mPathList;

    private float mX;
    private float mY;

    private static final int MODE_PEN = 0;
    private static final int MODE_ERASER = 1;
    private int mMode = MODE_PEN;

    private boolean mIsClear = true;

    public DrawPadView(Context context) {
        super(context);
        init();
    }


    public DrawPadView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DrawPadView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public DrawPadView(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private Path mPath;


    private void init() {
        //关闭硬件加速
        setLayerType(LAYER_TYPE_SOFTWARE, null);
//        setBackgroundColor(Color.WHITE);
        //画布抗锯齿
        mPaintFlagsDrawFilter = new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        //
        mPathList = new ArrayList<>();
        mPaintList = new ArrayList<>();
        //
//        mPath = new Path();
        //
        mmTempBitmapPaint = new Paint();
        //画笔抗锯齿,锯齿不显示，更加清楚
        mmTempBitmapPaint.setAntiAlias(true);
        //
        mmTempBitmapPaint.setFilterBitmap(true);
        //是否抖动,更加饱满清晰
        mmTempBitmapPaint.setDither(true);
        //设置线条拐角样式：圆角，其他是锐角尖锐突出，梯形往回收
        mmTempBitmapPaint.setStrokeJoin(Paint.Join.ROUND);
        //设置画笔线条端点的样式；圆头， canvas.drawPoint 时候也有效
        mmTempBitmapPaint.setStrokeCap(Paint.Cap.ROUND);

    }

    private Bitmap mTempBitmap;
    private Paint mmTempBitmapPaint;

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
                    mIsClear = false;
                    if (listener != null) {
                        listener.onState(mIsClear);
                    }
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
        if (mTempBitmap != null) {
            canvas.drawBitmap(mTempBitmap, 0, 0, mmTempBitmapPaint);
        }

        for (int i = 0; i < mPathList.size(); i++) {
            canvas.drawPath(mPathList.get(i), mPaintList.get(i));
            //
        }

    }

    private int bs;

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

    public void switchEraser() {
        //切换成橡皮擦
        mMode = MODE_ERASER;
    }

    public void switchPen() {
//切换成画笔
        mMode = MODE_PEN;
    }

    public void clear() {
        // 路径重置
        if (mPath != null) {
            mPath.reset();
        }
        for (Path path : mPathList) {
            path.reset();
        }
        if (mTempBitmap != null) {
            mTempBitmap = Bitmap.createBitmap(getWidth(), getHeight(), mTempBitmap.getConfig());
        }
        //
        mIsClear = true;
        if (listener != null) {
            listener.onState(mIsClear);
        }
        // 刷新绘制
        invalidate();
    }


    public void loadImageFromBase64(String base64) {
        Bitmap bitmap = base64ToBitmap(base64);
        if (bitmap != null) {
            mTempBitmap = bitmap.copy(bitmap.getConfig(), true);
        }
        ////
        if (mTempBitmap != null) {
//            mTempBitmapCanvas.setDrawFilter(mPaintFlagsDrawFilter);
//            mTempBitmapCanvas.drawColor(Color.WHITE);
//            mTempBitmapCanvas.drawBitmap(mTempBitmap, 0, 0, null);
            //
            mIsClear = false;
            if (listener != null) {
                listener.onState(mIsClear);
            }
            invalidate();
        }
    }

    public String saveImageToBase64() {
        Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.setDrawFilter(mPaintFlagsDrawFilter);
//        canvas.drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER);
        //把 view 画到画布上
        this.draw(canvas);
//        mTempBitmapCanvas.save();
//        mTempBitmapCanvas.restore();
        return bitmapToBase64(bitmap);
    }

    private String bitmapToBase64(Bitmap bitmap) {
        String result = null;
        ByteArrayOutputStream baos = null;
        if (bitmap == null) {
            return result;
        }
        try {
            baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] bitmapBytes = baos.toByteArray();
            result = Base64.encodeToString(bitmapBytes, Base64.DEFAULT);
            //
            baos.flush();
            baos.close();
            Log.e(TAG, "bitmapToBase64:  success ");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    private Bitmap base64ToBitmap(String base64) {
        //将字符串转换成Bitmap类型
        Bitmap bitmap = null;
        try {
            byte[] bitmapArray = Base64.decode(base64, Base64.DEFAULT);
            bitmap = BitmapFactory.decodeByteArray(bitmapArray, 0, bitmapArray.length);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return bitmap;
    }


    @Deprecated
    public void saveImageToFile() {
        Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.setDrawFilter(mPaintFlagsDrawFilter);
//        canvas.drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER);
        //把 view 画到画布上
        this.draw(canvas);
//        mTempBitmapCanvas.save();
//        mTempBitmapCanvas.restore();
        bitmapToFile(bitmap);
    }

    @Deprecated
    public void loadImageFromFile() {
        Bitmap bitmap = fileToBitmap();
//            mTempBitmap = BitmapFactory.decodeFile(getResources(), )
        mTempBitmap = bitmap.copy(bitmap.getConfig(), true);
        ////
        if (mTempBitmap != null) {
//            mTempBitmapCanvas.setDrawFilter(mPaintFlagsDrawFilter);
//            mTempBitmapCanvas.drawColor(Color.WHITE);
//            mTempBitmapCanvas.drawBitmap(mTempBitmap, 0, 0, null);
            //
            mIsClear = false;
            if (listener != null) {
                listener.onState(mIsClear);
            }
            invalidate();
        }

    }

    private void bitmapToFile(Bitmap bitmap) {
        File file = new File("/sdcard/louis/");
        if (!file.exists()) {
            file.mkdirs();
        }
        try {
            FileOutputStream fos = new FileOutputStream(file.getPath() + "/bmp.png");
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            //
            fos.flush();
            fos.close();
            Log.e(TAG, "bitmapToFile:  success ");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Bitmap fileToBitmap() {
        Bitmap bitmap = null;
        File file = new File("/sdcard/louis/");
        if (!file.exists()) {
            file.mkdirs();
        }
        try {
            FileInputStream fis = new FileInputStream(file.getPath() + "/bmp.png");
            bitmap = BitmapFactory.decodeStream(fis);  ///把流转化为Bitmap图片
        } catch (Exception e) {
            e.printStackTrace();
        }
        return bitmap;
    }

    public boolean isClear() {
        if (!mIsClear) {
            //再判断是否纯白
            Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.setDrawFilter(mPaintFlagsDrawFilter);
//        canvas.drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER);
            //把 view 画到画布上
            this.draw(canvas);
            //
            boolean realClear = cal(bitmap);
            mIsClear = realClear;
        }
        return mIsClear;
    }

    private OnStateListener listener;

    public void setOnStateListener(OnStateListener listener) {
        this.listener = listener;
    }


    private boolean cal(Bitmap bitmap) {
        //
        boolean bitmapAllPixelIsWhiteOrTransparent = true;
        Log.e(TAG, "cal: start");
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height]; // 通过位图的大小创建像素点数组
        int stride = width;
        bitmap.getPixels(pixels, 0, stride, 0, 0, width, height);
        int alpha = 0xFF << 24;
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                int grey = pixels[width * i + j];
                //
                int red = ((grey & 0x00FF0000) >> 16);//取高两位
                int green = ((grey & 0x0000FF00) >> 8);//取中两位
                int blue = (grey & 0x000000FF);//取低两位
                //!透明 && !白色
                if (grey != 0x00000000 && grey != 0xffffffff) {
                    bitmapAllPixelIsWhiteOrTransparent = false;
                    break;
                }

            }
        }
        return bitmapAllPixelIsWhiteOrTransparent;
    }
}
