package thjread.dilithium;

import java.util.ArrayList;
import java.util.List;

import retrofit.Call;
import retrofit.http.GET;
import retrofit.http.Path;

public interface WeatherService {

    @GET("forecast/{api_key}/{latitude},{longitude}?units=si")
    Call<WeatherData> getWeatherData(@Path("api_key") String api_key,
                                     @Path("latitude") float latitude,
                                     @Path("longitude") float longitude);

    public class Daily {

        public String summary;
        public String icon;
        public List<Datum> data = new ArrayList<Datum>();
    }

    public class Hourly {

        public String summary;
        public String icon;
        public List<Datum> data = new ArrayList<Datum>();
    }

    public class Minutely {

        public String summary;
        public String icon;
        public List<Datum> data = new ArrayList<Datum>();
    }

    public class Datum {

        public Integer time;
        public String summary;
        public String icon;
        public Integer sunriseTime;
        public Integer sunsetTime;
        public Double moonPhase;
        public Double precipIntensity;
        public Double precipIntensityMax;
        public Integer precipIntensityMaxTime;
        public Double precipProbability;
        public String precipType;
        public Double temperature;
        public Double temperatureMin;
        public Integer temperatureMinTime;
        public Double temperatureMax;
        public Integer temperatureMaxTime;
        public Double apparentTemperatureMin;
        public Integer apparentTemperatureMinTime;
        public Double apparentTemperatureMax;
        public Integer apparentTemperatureMaxTime;
        public Double dewPoint;
        public Double humidity;
        public Double windSpeed;
        public Integer windBearing;
        public Double visibility;
        public Double cloudCover;
        public Double pressure;
        public Double ozone;
    }

    public class Flags {
        public List<String> sources = new ArrayList<String>();
        public List<String> darkskyStations = new ArrayList<String>();
        public List<String> datapointStations = new ArrayList<String>();
        public String metnoLicense;
        public List<String> isdStations = new ArrayList<String>();
        public List<String> madisStations = new ArrayList<String>();
        public String units;
    }

    public class WeatherData {
        public Double latitude;
        public Double longitude;
        public String timezone;
        public Integer offset;
        public Datum currently;
        public Minutely minutely;
        public Hourly hourly;
        public Daily daily;
        public Flags flags;
    }
}
