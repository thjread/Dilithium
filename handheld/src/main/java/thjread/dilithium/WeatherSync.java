package thjread.dilithium;

import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.squareup.okhttp.OkHttpClient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import retrofit.Call;
import retrofit.GsonConverterFactory;
import retrofit.Response;
import retrofit.Retrofit;

public class WeatherSync extends WearableListenerService implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    private static final String WEATHER_CAPABILITY_NAME = "weather_data";
    private static final String WEATHER_PATH = "/weather_data";

    private static final String TAG = "thjread.dilithium";

    private GoogleApiClient mGoogleApiClient;

    private String mNodeId = null;

    private boolean processingMessage = false;//TODO

    private static String key;

    float temperature = 0;

    @Override
    public void onCreate() {
        key = getResources().getString(R.string.forecast_api_key);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        mGoogleApiClient.connect();

        Log.e("thjread.dilithium", "Weather Sync listener created");
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Connected to Google Api Service");
        }
        Wearable.MessageApi.addListener(mGoogleApiClient, this);

        WeatherSyncTask task = new WeatherSyncTask();
        task.execute();
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        Log.d(TAG, "Connection failed");
    }

    public void onConnectionSuspended(int r) {
        Log.d(TAG, "Connection suspended");
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        Log.e("thjread.dilithium", "Message received");
        if (messageEvent.getPath().equals(WEATHER_PATH) && !processingMessage) {//TODO
            Log.e(TAG, "Weather message received");
            mNodeId = messageEvent.getSourceNodeId();
            WeatherSyncTask task = new WeatherSyncTask();
            processingMessage = true;
            task.execute();
        }
    }

    @Override
    public void onPeerConnected(Node peer) {
        Log.e(TAG, "Peer connected: " + peer.getDisplayName());
    }

    @Override
    public void onPeerDisconnected(Node peer) {
        Log.e(TAG, "Peer disconnected: " + peer.getDisplayName());
    }

    private class WeatherSyncTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            Log.e("thjread.dilithium", "Weather Sync task run");
            if (mGoogleApiClient.isConnected()) {
                byte[] data = ByteBuffer.allocate(4).putFloat(weatherData()).array();
                Log.e(TAG, "Current temperature: " + Float.toString(temperature));
                Wearable.MessageApi.sendMessage(mGoogleApiClient, mNodeId,
                        WEATHER_PATH, data).setResultCallback(
                        new ResultCallback<MessageApi.SendMessageResult>() {
                            @Override
                            public void onResult(@NonNull MessageApi.SendMessageResult result) {
                                Log.e(TAG, "Return message sent");
                                processingMessage = false;//TODO
                            }
                        }
                );
            }
            return null;
        }
    }

    private float weatherData() {
        OkHttpClient client = new OkHttpClient();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://api.forecast.io/")
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build();

        WeatherService service = retrofit.create(WeatherService.class);

        Call call = service.getWeatherData(key, 52.2050f, 0.1190f);//TODO
        try {
            Response<WeatherService.WeatherData> r = call.execute();
            WeatherService.WeatherData data = r.body();
            if (data != null && data.currently != null && data.currently.temperature != null) {
                double temp = data.currently.temperature;
                temperature = (float) temp;
            }
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        }

        return temperature;
    }
}