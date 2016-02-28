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
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.wearable.provider.WearableCalendarContract;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import android.provider.CalendarContract;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class Dilithium extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final int MSG_LOAD_CALENDAR = 1;

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

    static boolean mConnected = true;
    public static class NodeListenerService extends WearableListenerService {
        private static final String TAG = "NodeListenerService";

        @Override
        public void onPeerDisconnected(Node peer) {
            mConnected = false;
        }

        @Override
        public void onPeerConnected(Node peer) {
            mConnected = true;
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener, MessageApi.MessageListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Bitmap mBackgroundBitmap;
        Bitmap mBackgroundCalendarBitmap;
        Bitmap mBackgroundScaledBitmap;
        Bitmap mBackgroundCalendarScaledBitmap;
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

        private GoogleApiClient mGoogleApiClient;
        boolean mApiConnected = false;

        float mXOffset;
        float mYOffset;

        int mBatteryPercent;
        boolean mRegisteredBatteryReceiver = false;
        final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                mBatteryPercent = (level*100) / scale;
                invalidate();
            }
        };

        //https://developer.android.com/training/wearables/data-layer/events.html#Listen

        float mTemp = 0;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mBurnInProtection;

        boolean showCalendar = false;
        String calendarFrom = "";
        String calendarTo = "";
        String calendarFromText = "";
        String calendarToText = "";

        long latestFrom; long earliestTo; long fromStart;

        private void calendarUpdate() {
            long begin = System.currentTimeMillis();

            showCalendar = false;

            calendarFromText = calendarToText = "";

            if (latestFrom > begin - DateUtils.MINUTE_IN_MILLIS*15 && calendarFrom.length() > 0) {
                showCalendar = true;
                calendarFromText = calendarFrom;
            }
            if (earliestTo < begin + DateUtils.MINUTE_IN_MILLIS*30 &&
                    fromStart < begin - DateUtils.MINUTE_IN_MILLIS*10 &&
                    calendarTo.length() > 0) {
                showCalendar = true;
                calendarToText = calendarTo;
            }

            if (earliestTo < begin && !calendarTo.equals("")) {
                calendarFromText = calendarTo;
                calendarToText = "";
            }

            if (showCalendar) {
                if (calendarFromText.equals("")) {
                    calendarFromText = "  __";
                } else if (calendarToText.equals("")) {
                    calendarToText = "  __";
                }
            }
        }

        //TODO: https://developer.android.com/samples/WatchFace/Wearable/src/com.example.android.wearable.watchface/CalendarWatchFaceService.html
        private class CalendarSync extends AsyncTask<Void, Void, Void> {
            @Override
            protected Void doInBackground(Void... voids) {
                if (!mConnected) {
                    return null;
                }
                long begin = System.currentTimeMillis();
                Uri.Builder builder =
                        WearableCalendarContract.Instances.CONTENT_URI.buildUpon();
                ContentUris.appendId(builder, begin);//TODO
                ContentUris.appendId(builder, begin + DateUtils.DAY_IN_MILLIS);

                final String[] INSTANCE_PROJECTION = new String[] {
                        CalendarContract.Instances.EVENT_ID,      // 0
                        CalendarContract.Instances.BEGIN,         // 1
                        CalendarContract.Instances.END,          // 2
                        CalendarContract.Instances.DESCRIPTION,   // 3
                        CalendarContract.Instances.ALL_DAY      // 4
                };
                final Cursor cursor = getContentResolver().query(builder.build(),
                        INSTANCE_PROJECTION, null, null, null);
                //long fromEventID = -1, toEventID = -1;
                latestFrom = -1; fromStart = -1;
                earliestTo = -1; long toEnd = -1;
                String toDescription = null;
                String fromDescription = null;
                while (cursor.moveToNext()) {
                    long start = cursor.getLong(1);
                    long end = cursor.getLong(2);
                    String all_day = cursor.getString(4);
                    if (start < begin && (end > latestFrom || latestFrom == -1) && !all_day.equals("1")) {
                        //fromEventID = cursor.getLong(0);
                        latestFrom = end;
                        fromStart = start;
                        fromDescription = cursor.getString(3);
                    }
                    if (start > begin && (start <= earliestTo || earliestTo == -1) && !all_day.equals("1")) {
                        if (start < earliestTo || end < toEnd) {
                            //toEventID = cursor.getLong(0);
                            earliestTo = start;
                            toEnd = end;
                            toDescription = cursor.getString(3);
                        }
                    }
                }

                cursor.close();

                if (fromDescription != null) {
                    calendarFrom = fromDescription;
                    String[] arr = calendarFrom.split(" ");
                    calendarFrom = "";
                    for (int i = 0; i < arr.length; ++i) {
                        if (calendarFrom.length() + arr[i].length() <= 4) {
                            if (i == 0) {
                                calendarFrom = arr[i];
                            } else {
                                calendarFrom += " " + arr[i];
                            }
                        } else {
                            break;
                        }
                    }
                    if (!calendarFrom.equals("")) {
                        for (int i = 0; i < 5 - calendarFrom.length(); ++i) {
                            calendarFrom = " " + calendarFrom;
                        }
                    }
                    if (fromDescription.equals("Study Room")) {
                        calendarFrom = " Free";
                    } else if (fromDescription.equals("Sports Hall")) {
                        calendarFrom = " Sprt";
                    }
                } else {
                    calendarFrom = "";
                }

                if (toDescription != null) {
                    calendarTo = toDescription;
                    String[] arr = calendarTo.split(" ");
                    calendarTo = "";
                    for (int i = 0; i < arr.length; ++i) {
                        if (calendarTo.length() + arr[i].length() <= 4) {
                            if (i == 0) {
                                calendarTo = arr[i];
                            } else {
                                calendarTo += " " + arr[i];
                            }
                        } else {
                            break;
                        }
                    }
                    if (!calendarTo.equals("")) {
                        for (int i = 0; i < 5 - calendarTo.length(); ++i) {
                            calendarTo = " " + calendarTo;
                        }
                    }
                    if (toDescription.equals("Study Room")) {
                        calendarTo = " Free";
                    } else if (toDescription.equals("Sports Hall")) {
                        calendarTo = " Sprt";
                    }
                } else {
                    calendarTo = "";
                }

                return null;
            }

            @Override
            protected void onPostExecute(Void data) {
                invalidate();
            }
        }

        private AsyncTask<Void, Void, Void> mLoadCalendarTask;

        /* Handler to load the calendar once a minute in interactive mode. */
        final Handler mLoadCalendarHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_LOAD_CALENDAR:
                        cancelLoadCalendarTask();
                        mLoadCalendarTask = new CalendarSync();
                        mLoadCalendarTask.execute();
                        break;
                }
            }
        };

        private void cancelLoadCalendarTask() {
            if (mLoadCalendarTask != null) {
                mLoadCalendarTask.cancel(true);
            }
        }

        private final static String TAG = "thjread.watchface";

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(Dilithium.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setAmbientPeekMode(WatchFaceStyle.AMBIENT_PEEK_MODE_HIDDEN)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = Dilithium.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.background1);
            mBackgroundCalendarBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.background2);

            mAmbientBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ambient1);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(Color.BLACK);

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(Color.BLACK);

            mCalendar = Calendar.getInstance();

            mLoadCalendarHandler.sendEmptyMessage(MSG_LOAD_CALENDAR);

            mGoogleApiClient = new GoogleApiClient.Builder(Dilithium.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .useDefaultAccount()//TODO necessary?
                    .build();
        }

        private static final String WEATHER_CAPABILITY_NAME = "weather_data";
        private static final String WEATHER_PATH = "/weather_data";

        @Override
        public void onConnected(Bundle connectionHint) {
            mApiConnected = true;
            Wearable.MessageApi.addListener(mGoogleApiClient, this);

            Wearable.CapabilityApi.getCapability(mGoogleApiClient, WEATHER_CAPABILITY_NAME,
                    CapabilityApi.FILTER_REACHABLE).setResultCallback(
                    new ResultCallback<CapabilityApi.GetCapabilityResult>() {
                        @Override
                        public void onResult(@NonNull CapabilityApi.GetCapabilityResult getCapabilityResult) {
                            updateWeatherCapability(getCapabilityResult.getCapability());
                        }
                    }
            );

            CapabilityApi.CapabilityListener capabilityListener =
                    new CapabilityApi.CapabilityListener() {
                        @Override
                        public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
                            updateWeatherCapability(capabilityInfo);
                        }
                    };

            Wearable.CapabilityApi.addCapabilityListener(
                    mGoogleApiClient,
                    capabilityListener,
                    WEATHER_CAPABILITY_NAME);
        }

        private String mWeatherNodeId = null;

        private void updateWeatherCapability(CapabilityInfo capabilityInfo) {
            Set<Node> connectedNodes = capabilityInfo.getNodes();

            mWeatherNodeId = pickBestNodeId(connectedNodes);

            backgroundUpdate();
        }

        private String pickBestNodeId(Set<Node> nodes) {
            String bestNodeId = null;
            // Find a nearby node or pick one arbitrarily
            for (Node node : nodes) {
                if (node.isNearby()) {
                    return node.getId();
                }
                bestNodeId = node.getId();
            }
            return bestNodeId;
        }

        @Override
        public void onMessageReceived(MessageEvent messageEvent) {
            byte[] d = messageEvent.getData();
            mTemp = ByteBuffer.wrap(d).getFloat();
            Log.d(TAG, "Temperature: " + mTemp);
        }

        public void onConnectionSuspended(int cause) {
            mApiConnected = false;
        }

        public void onConnectionFailed(@NonNull ConnectionResult cause) {
            mApiConnected = false;
        }

        @Override
        public void onSurfaceChanged(
                SurfaceHolder holder, int format, int width, int height) {
            if (mBackgroundScaledBitmap == null
                    || mBackgroundScaledBitmap.getWidth() != width
                    || mBackgroundScaledBitmap.getHeight() != height) {
                mBackgroundScaledBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                        width, height, true /* filter */);
                mBackgroundCalendarScaledBitmap = Bitmap.createScaledBitmap(mBackgroundCalendarBitmap,
                        width, height, true /* filter */);
                mAmbientScaledBitmap = Bitmap.createScaledBitmap(mAmbientBitmap,
                        width, height, true /* filter */);
            }
            super.onSurfaceChanged(holder, format, width, height);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            mLoadCalendarHandler.removeMessages(MSG_LOAD_CALENDAR);
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
                mLoadCalendarHandler.sendEmptyMessage(MSG_LOAD_CALENDAR);
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                if (mGoogleApiClient != null) {
                    mGoogleApiClient.connect();
                }
                backgroundUpdate();
            } else {
                unregisterReceiver();
                mLoadCalendarHandler.removeMessages(MSG_LOAD_CALENDAR);
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.MessageApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
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
            checkBackgroundUpdate();
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

            calendarUpdate();

            // Draw the background.
            if (mAmbient && !showCalendar) {
                canvas.drawColor(Color.BLACK);
            } else if (mAmbient) {
                canvas.drawBitmap(mAmbientScaledBitmap, 0, 0, mBackgroundPaint);
            }else if (!showCalendar){
                canvas.drawBitmap(mBackgroundScaledBitmap, 0, 0, mBackgroundPaint);
            } else {
                canvas.drawBitmap(mBackgroundCalendarScaledBitmap, 0, 0, mBackgroundPaint);
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
                    mCalendar.get(Calendar.MONTH) + 1,
                    mCalendar.get(Calendar.DAY_OF_MONTH));
            mXOffset = width / 360.0f * 109f;
            mYOffset = width / 360.0f * 253f;
            mTextPaint.setTextSize(width / 360.0f * 49.0f);
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);

            if (!(mAmbient && (mLowBitAmbient || mBurnInProtection))) {
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

                if (!showCalendar) {
                    text = String.format("%02d", Math.round(mTemp));
                    mXOffset = width / 360.0f * 238f;
                    mYOffset = width / 360.0f * 76f;
                    mTextPaint.setTextSize(width / 360.0f * 31.0f);
                    canvas.drawText(text, mXOffset, mYOffset, mTextPaint);
                }
            }
            if (showCalendar) {
                if (calendarFrom != null) {
                    text = calendarFromText;
                    mXOffset = width / 360.0f * 129f;
                    mYOffset = width / 360.0f * 76f;
                    mTextPaint.setTextSize(width / 360.0f * 31.0f);
                    canvas.drawText(text, mXOffset, mYOffset, mTextPaint);
                }

                if (calendarTo != null) {
                    text = calendarToText;
                    mXOffset = width / 360.0f * 216f;
                    mYOffset = width / 360.0f * 76f;
                    mTextPaint.setTextSize(width / 360.0f * 31.0f);
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

        long lastBackgroundUpdate;

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
                checkBackgroundUpdate();
            }
        }

        private void checkBackgroundUpdate() {
            if (!mConnected) {
                return;
            }

            mCalendar.setTimeInMillis(System.currentTimeMillis());

            if (mCalendar.getTimeInMillis()-lastBackgroundUpdate > DateUtils.MINUTE_IN_MILLIS*3
                    && mCalendar.get(Calendar.MINUTE)%5 <= 1) {
                backgroundUpdate();
            }
        }

        private void backgroundUpdate() {
            lastBackgroundUpdate = mCalendar.getTimeInMillis();

            mLoadCalendarHandler.sendEmptyMessage(MSG_LOAD_CALENDAR);

            byte[] data = {};

            Log.d(TAG, "Background update");

            if (mWeatherNodeId != null && mApiConnected) {
                Wearable.MessageApi.sendMessage(mGoogleApiClient, mWeatherNodeId,
                        WEATHER_PATH, data).setResultCallback(
                        new ResultCallback<MessageApi.SendMessageResult>() {
                            @Override
                            public void onResult(@NonNull MessageApi.SendMessageResult result) {
                            }
                        }
                );
            }
        }
    }
}
