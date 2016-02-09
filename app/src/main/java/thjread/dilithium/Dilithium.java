/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package thjread.dilithium;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.WearableListenerService;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class Dilithium extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<Dilithium.Engine> mWeakReference;

        public EngineHandler(Dilithium.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            Dilithium.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Bitmap mBackgroundBitmap;
        Bitmap mBackgroundScaledBitmap;
        Bitmap mAmbientBitmap;
        Bitmap mAmbientScaledBitmap;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        int mBatteryPercent;
        boolean mRegisteredBatteryReceiver = false;
        final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                mBatteryPercent = level / scale;
                invalidate();
            }
        };

        //https://developer.android.com/training/wearables/data-layer/events.html#Listen
        boolean mConnected;
        public class NodeListenerService extends WearableListenerService {
            @Override
            public void onPeerDisconnected(Node peer) {
                mConnected = false;
            }

            @Override
            public void onPeerConnected(Node peer) {
                mConnected = true;
            }
        }

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mBurnInProtection;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(Dilithium.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = Dilithium.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            boolean align = false;
            if (!align) {//TODO
                mBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.background1);
            } else {
                mBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.background1_);
            }
            mAmbientBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ambient1);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(Color.BLACK);

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(Color.BLACK);

            mCalendar = Calendar.getInstance();


        }

        @Override
        public void onSurfaceChanged(
                SurfaceHolder holder, int format, int width, int height) {
            if (mBackgroundScaledBitmap == null
                    || mBackgroundScaledBitmap.getWidth() != width
                    || mBackgroundScaledBitmap.getHeight() != height) {
                mBackgroundScaledBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                        width, height, true /* filter */);
                mAmbientScaledBitmap = Bitmap.createScaledBitmap(mAmbientBitmap,
                        width, height, true /* filter */);
            }
            super.onSurfaceChanged(holder, format, width, height);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            Typeface font = Typeface.createFromAsset(getAssets(), "FINALOLD.TTF");
            paint.setTypeface(font);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                mRegisteredTimeZoneReceiver = true;
                IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
                Dilithium.this.registerReceiver(mTimeZoneReceiver, filter);
            }
            if (!mRegisteredBatteryReceiver) {
                mRegisteredBatteryReceiver = true;
                IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Dilithium.this.registerReceiver(mBatteryReceiver, filter);
            }
        }

        private void unregisterReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                mRegisteredTimeZoneReceiver = false;
                Dilithium.this.unregisterReceiver(mTimeZoneReceiver);
            }
            if (mRegisteredBatteryReceiver) {
                mRegisteredBatteryReceiver = false;
                Dilithium.this.unregisterReceiver(mBatteryReceiver);
            }
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = Dilithium.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
            }

            invalidate();
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = Dilithium.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mCalendar.setTimeInMillis(System.currentTimeMillis());
            float width = bounds.width();
            float height = bounds.height();

            // Draw the background.
            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawColor(Color.BLACK);
            } else if (mAmbient) {
                canvas.drawBitmap(mAmbientScaledBitmap, 0, 0, mBackgroundPaint);
            } else {
                canvas.drawBitmap(mBackgroundScaledBitmap, 0, 0, mBackgroundPaint);
            }

            String text = mAmbient
                    ? String.format("%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY),
                    mCalendar.get(Calendar.MINUTE))
                    : String.format("%02d:%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY),
                        mCalendar.get(Calendar.MINUTE),
                        mCalendar.get(Calendar.SECOND));
            mXOffset = width/360.0f* 101f;
            mYOffset = width/360.0f* 189f;
            mTextPaint.setTextSize(width/360.0f*89.0f);
            mTextPaint.setColor(mAmbient ? Color.WHITE : Color.BLACK);
            if (mAmbient && mBurnInProtection) {
                mTextPaint.setStyle(Paint.Style.STROKE);
                mTextPaint.setStrokeWidth(1);
            } else {
                mTextPaint.setStyle(Paint.Style.FILL);
                mTextPaint.setStrokeWidth(0);
            }
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);

            text = String.format("%04d%02d.%02d", mCalendar.get(Calendar.YEAR),
                        mCalendar.get(Calendar.MONTH)+1,
                        mCalendar.get(Calendar.DAY_OF_MONTH));
            mXOffset = width/360.0f* 109f;
            mYOffset = width / 360.0f*253f;
            mTextPaint.setTextSize(width / 360.0f * 49.0f);
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);

            if (!(mAmbient && (mLowBitAmbient || mBurnInProtection))) {//TODO
                mBatteryPercent = 97;//TODO
                if (mBatteryPercent < 100) {
                    text = String.format("%02d", mBatteryPercent);
                    mXOffset = width / 360.0f * 235f;
                    mYOffset = width / 360.0f * 306f;
                    mTextPaint.setTextSize(width / 360.0f * 34.0f);
                    mTextPaint.setColor(!mAmbient ? Color.rgb(254, 153, 0) : Color.rgb(189, 140, 65));
                    canvas.drawText(text, mXOffset, mYOffset, mTextPaint);
                } else {
                    text = String.format("%03d", mBatteryPercent);
                    mXOffset = width / 360.0f * 232f;
                    mYOffset = width / 360.0f * 305f;
                    mTextPaint.setTextSize(width / 360.0f * 31.0f);
                    mTextPaint.setColor(Color.rgb(254, 153, 0));
                    canvas.drawText(text, mXOffset, mYOffset, mTextPaint);
                }

                mConnected = true; //TODO
                if (mConnected) {
                    text = "ONLINE";
                    mXOffset = width / 360.0f * 232f;
                    mYOffset = width / 360.0f * 105f;
                    mTextPaint.setTextSize(width / 360.0f * 25.0f);
                    canvas.drawText(text, mXOffset, mYOffset, mTextPaint);
                } else {
                    text = "OFFLINE";
                    mXOffset = width / 360.0f * 229f;
                    mYOffset = width / 360.0f * 105f;
                    mTextPaint.setTextSize(width / 360.0f * 25.0f);
                    canvas.drawText(text, mXOffset, mYOffset, mTextPaint);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
