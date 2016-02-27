package thjread.dilithium;

import android.os.AsyncTask;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class WeatherSync extends WearableListenerService {

    private static final String WEATHER_CAPABILITY_NAME = "weather_data";
    private static final String WEATHER_PATH = "/weather_data";

    private static final String TAG = "thjread.dilithium";

    @Override
    public void onCreate() {
        Log.e("thjread.dilithium", "Weather Sync listener created");
        WeatherSyncTask task = new WeatherSyncTask();
        task.execute();
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        Log.e("thjread.dilithium", "Message received");
        if (messageEvent.getPath().equals(WEATHER_PATH)) {
            Log.e(TAG, "Weather message received");
            WeatherSyncTask task = new WeatherSyncTask();
            task.execute();
        }
    }

    private class WeatherSyncTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            Log.e("thjread.dilithium", "Weather Sync task run");
            return null;
        }
    }
}