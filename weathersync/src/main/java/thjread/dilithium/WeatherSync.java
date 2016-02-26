package thjread.dilithium;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.IBinder;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class WeatherSync extends WearableListenerService {
    @Override
    public void onCreate() {
        Log.e("thjread.dilithium", "Weather Sync listener created");
        WeatherSyncTask task = new WeatherSyncTask();
        task.execute();
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        Log.e("thjread.dilithium", "Weather Sync message received");
    }

    private class WeatherSyncTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            Log.e("thjread.dilithium", "Weather Sync task run");
            return null;
        }
    }
}