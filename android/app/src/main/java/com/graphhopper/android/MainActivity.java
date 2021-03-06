package com.graphhopper.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.Toast;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.PathWrapper;
import com.graphhopper.reader.dem.SRTMProvider;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.DataFlagEncoder;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.Constants;
import com.graphhopper.util.Parameters.Algorithms;
import com.graphhopper.util.Parameters.Routing;
import com.graphhopper.util.PointList;
import com.graphhopper.util.StopWatch;

import org.apache.commons.io.FileUtils;
import org.oscim.android.MapView;
import org.oscim.android.canvas.AndroidGraphics;
import org.oscim.backend.canvas.Bitmap;
import org.oscim.core.GeoPoint;
import org.oscim.core.Tile;
import org.oscim.event.Gesture;
import org.oscim.event.GestureListener;
import org.oscim.event.MotionEvent;
import org.oscim.layers.Layer;
import org.oscim.layers.marker.ItemizedLayer;
import org.oscim.layers.marker.MarkerItem;
import org.oscim.layers.marker.MarkerSymbol;
import org.oscim.layers.tile.buildings.BuildingLayer;
import org.oscim.layers.tile.vector.VectorTileLayer;
import org.oscim.layers.tile.vector.labeling.LabelLayer;
import org.oscim.layers.vector.PathLayer;
import org.oscim.layers.vector.geometries.Style;
import org.oscim.theme.VtmThemes;
import org.oscim.tiling.source.mapfile.MapFileTileSource;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity implements LocationListener {
    private static final int NEW_MENU_ID = Menu.FIRST + 1;
    private MapView mapView;
    private GraphHopper hopper;
    private GeoPoint start;
    private GeoPoint end;
    private LocationManager locationManager;
    private MarkerItem locationMarker;
    private volatile boolean prepareInProgress = true;
    private volatile boolean shortestPathRunning = false;
    private boolean isCheckedSteps = false;
    private boolean isCheckedUpaths = false;
    private int inclineValue = 90;
    private String currentArea = "new-jersey";
    private File mapsFolder;
    private ItemizedLayer<MarkerItem> itemizedLayer;
    private PathLayer pathLayer;

    @Override
    public void onLocationChanged(Location location) {
        itemizedLayer.removeItem(locationMarker);
        locationMarker = createMarkerItem(new GeoPoint(location.getLatitude(), location.getLongitude()),
                R.drawable.marker_icon_current_location);
        itemizedLayer.addItem(locationMarker);
        mapView.map().updateMap(true);
    }

    public void onStatusChanged(String s, int i, Bundle bundle) {}

    public void onProviderEnabled(String s) {}

    public void onProviderDisabled(String s) {}

    protected boolean onLongPress(GeoPoint p) {
        if (!isReady())
            return false;

        if (shortestPathRunning) {
            logUser("Calculation still in progress");
            return false;
        }

        if (start != null && end == null) {
            end = p;
            shortestPathRunning = true;
            itemizedLayer.addItem(createMarkerItem(p, R.drawable.marker_icon_red));
            mapView.map().updateMap(true);

            calcPath(start.getLatitude(), start.getLongitude(), end.getLatitude(),
                    end.getLongitude());
        } else {
            start = p;
            end = null;

            // remove routing layers
            mapView.map().layers().remove(pathLayer);
            itemizedLayer.removeAllItems();

            // Map position
            Location lastLocation = getLastBestLocation();
            GeoPoint locationPoint = new GeoPoint(lastLocation.getLatitude(), lastLocation.getLongitude());
            locationMarker = createMarkerItem(locationPoint, R.drawable.marker_icon_current_location);
            itemizedLayer.addItem(locationMarker);

            itemizedLayer.addItem(createMarkerItem(start, R.drawable.marker_icon_green));
            mapView.map().updateMap(true);
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main2);

        Tile.SIZE = Tile.calculateTileSize(getResources().getDisplayMetrics().scaledDensity);
        mapView = new MapView(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);

        boolean greaterOrEqKitkat = Build.VERSION.SDK_INT >= 19;
        if (greaterOrEqKitkat) {
            if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                logUser("NAVAX is not usable without an external storage!");
                return;
            }
            mapsFolder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "/graphhopper/maps/");
        } else
            mapsFolder = new File(Environment.getExternalStorageDirectory(), "/graphhopper/maps/");

        Button button = (Button) findViewById(R.id.start_button);
        button.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                isCheckedSteps = ((CheckBox)findViewById(R.id.steps)).isChecked();
                isCheckedUpaths = ((CheckBox)findViewById(R.id.unpaved_paths)).isChecked();
                if (((CheckBox)findViewById(R.id.inclines)).isChecked())
                    inclineValue = Integer.parseInt(((Spinner) findViewById(R.id.inclines_spinner))
                            .getSelectedItem().toString().split("\\s")[0]);
                File areaFolder = new File(mapsFolder, currentArea + "-gh");
                loadMap(areaFolder);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hopper != null)
            hopper.close();

        hopper = null;
        // necessary?
        System.gc();

        // Cleanup VTM
        mapView.map().destroy();
    }

    boolean isReady() {
        // only return true if already loaded
        if (hopper != null)
            return true;

        if (prepareInProgress) {
            logUser("Preparation still in progress");
            return false;
        }
        logUser("Prepare finished but app not ready. This happens when there was an error while loading the files.");
        return false;
    }

    /**
     * @return the last know best location
     */
    private Location getLastBestLocation() {
        Location locationGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location locationNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

        if (locationGPS == null && locationNet == null)
            return null;
        long GPSLocationTime = 0;
        if (null != locationGPS) { GPSLocationTime = locationGPS.getTime(); }

        long NetLocationTime = 0;

        if (null != locationNet) {
            NetLocationTime = locationNet.getTime();
        }

        if ( 0 < GPSLocationTime - NetLocationTime ) {
            return locationGPS;
        }
        else {
            return locationNet;
        }
    }

    void loadMap(File areaFolder) {
        logUser("loading map");

        // Long press receiver
        mapView.map().layers().add(new LongPressLayer(mapView.map()));

        // Map file source
        MapFileTileSource tileSource = new MapFileTileSource();
        tileSource.setMapFile(new File(areaFolder, currentArea + ".map").getAbsolutePath());
        VectorTileLayer l = mapView.map().setBaseMap(tileSource);
        mapView.map().setTheme(VtmThemes.DEFAULT);
        mapView.map().layers().add(new BuildingLayer(mapView.map(), l));
        mapView.map().layers().add(new LabelLayer(mapView.map(), l));

        // Map position
        Location lastLocation = getLastBestLocation();
        GeoPoint mapCenter;
        if (lastLocation != null) {
            mapCenter = new GeoPoint(lastLocation.getLatitude(), lastLocation.getLongitude());
            mapView.map().setMapPosition(lastLocation.getLatitude(), lastLocation.getLongitude(), 1 << 17);
        }
        else {
            mapCenter = tileSource.getMapInfo().boundingBox.getCenterPoint();
            mapView.map().setMapPosition(mapCenter.getLatitude(), mapCenter.getLongitude(), 1 << 17);
        }
//
//        // Map fixed position
//        mapView.map().setMapPosition(40.34648, -74.658457, 1 << 17);

        // Markers layer
        itemizedLayer = new ItemizedLayer<>(mapView.map(), (MarkerSymbol) null);
        itemizedLayer.addItem(createMarkerItem(mapCenter, R.drawable.marker_icon_current_location));
        mapView.map().layers().add(itemizedLayer);

        setContentView(mapView);
        loadGraphStorage();
    }

    void loadGraphStorage() {
        logUser("loading graph (" + Constants.VERSION + ") ... ");
        new GHAsyncTask<Void, Void, Path>() {
            protected Path saveDoInBackground(Void... v) throws Exception {
                try {
                    FileUtils.deleteDirectory(new File (new File(mapsFolder, currentArea).getAbsolutePath() + "-gh2"));
                    log("delete worked");
                } catch (IllegalArgumentException a) {
                    log("delete didn't work");
                    log(a.toString());
                }
                try {
                    FileUtils.copyDirectory(new File (new File(mapsFolder, currentArea).getAbsolutePath() + "-gh"),
                            new File (new File(mapsFolder, currentArea).getAbsolutePath() + "-gh2"));
                    log("it worked");
                    log(Long.toString(FileUtils.sizeOf(new File (new File(mapsFolder, currentArea).getAbsolutePath() + "-gh2"))));
                    log(new File (new File(mapsFolder, currentArea).getAbsolutePath() + "-gh2").getAbsolutePath());
                } catch (IllegalArgumentException a) {
                    log("it didn't work");
                }
                GraphHopper tmpHopp = new GraphHopper().forMobile();
                SRTMProvider srtmProvider = new SRTMProvider();
                tmpHopp.setElevation(true);
                tmpHopp.setElevationProvider(srtmProvider);
                tmpHopp.setEncodingManager(new EncodingManager("generic"));
                tmpHopp.setCHEnabled(false);
                tmpHopp.load(new File(mapsFolder, currentArea).getAbsolutePath() + "-gh2");
                log("found graph " + tmpHopp.getGraphHopperStorage().toString() + ", nodes:" + tmpHopp.getGraphHopperStorage().getNodes());
                DataFlagEncoder dataFlagEncoder = (DataFlagEncoder) tmpHopp.getGraphHopperStorage()
                        .getEncodingManager().getEncoder("generic");
                GraphHopperStorage graphHopperStorage = tmpHopp.getGraphHopperStorage();
                AllEdgesIterator edges = graphHopperStorage.getAllEdges();
                String [] surfaceTypes = new String [] {"paved", "asphalt", "sett", "concrete",
                        "concrete:lanes", "concrete:plates", "paving_stones", "metal", "wood"};
                List<String> surfaceTypesList = new ArrayList(Arrays.asList(surfaceTypes));
                while (edges.next()) {
                    String highway = dataFlagEncoder.getHighwayAsString(edges);
                    String surface = dataFlagEncoder.getSurfaceAsString(edges);
                    NodeAccess nodeAccess = tmpHopp.getGraphHopperStorage().getNodeAccess();
                    double elevation1 = nodeAccess.getElevation(edges.getBaseNode());
                    double elevation2 = nodeAccess.getElevation(edges.getAdjNode());
                    if (((Math.atan(Math.abs((elevation2 - elevation1)
                            / edges.getDistance())) * 180 / Math.PI) > ((double) inclineValue))
                            || (highway == "steps" && isCheckedSteps)
                            || (surface != null && !surfaceTypesList.contains(surface) && isCheckedUpaths)) {
//                        edges.setDistance(Double.POSITIVE_INFINITY);
                        long flags = edges.getFlags();
//                        edges.setFlags(dataFlagEncoder.setAccess(flags, false, false));
                        edges.setFlags(dataFlagEncoder.setEdgeNoAccess(flags));
                        log("found incline");
                        double a = (Math.atan(Math.abs((elevation2 - elevation1) / edges.getDistance())) * 180 / Math.PI);
                        log(Double.toString(a));
                        log(Double.toString((double) inclineValue));
//                        EdgeIteratorState state2 = tmpHopp.getGraphHopperStorage().getBaseGraph().getEdgeIteratorState(edges.getEdge(), edges.getAdjNode());
//                        log("state is: " + state2.getDistance());
                    }
                }
                hopper = tmpHopp;
                return null;
            }

            protected void onPostExecute(Path o) {
                if (hasError()) {
                    logUser("An error happened while creating graph:"
                            + getErrorMessage());
                } else {
                    logUser("Finished loading graph. Press long to define where to start and end the route.");
                }

                finishPrepare();
            }
        }.execute();
    }

    private void finishPrepare() {
        prepareInProgress = false;
    }

    private PathLayer createPathLayer(PathWrapper response) {
        Style style = Style.builder()
                .generalization(Style.GENERALIZATION_SMALL)
                .strokeColor(0x9900cc33)
                .strokeWidth(4 * getResources().getDisplayMetrics().density)
                .build();
        PathLayer pathLayer = new PathLayer(mapView.map(), style);
        List<GeoPoint> geoPoints = new ArrayList<>();
        PointList pointList = response.getPoints();
        for (int i = 0; i < pointList.getSize(); i++)
            geoPoints.add(new GeoPoint(pointList.getLatitude(i), pointList.getLongitude(i)));
        pathLayer.setPoints(geoPoints);
        return pathLayer;
    }

    @SuppressWarnings("deprecation")
    private MarkerItem createMarkerItem(GeoPoint p, int resource) {
        Drawable drawable = getResources().getDrawable(resource);
        Bitmap bitmap = AndroidGraphics.drawableToBitmap(drawable);
        MarkerSymbol markerSymbol = new MarkerSymbol(bitmap, 0.5f, 1);
        MarkerItem markerItem = new MarkerItem("", "", p);
        markerItem.setMarker(markerSymbol);
        return markerItem;
    }

    public void calcPath(final double fromLat, final double fromLon,
                         final double toLat, final double toLon) {

        log("calculating path ...");
        new AsyncTask<Void, Void, PathWrapper>() {
            float time;

            protected PathWrapper doInBackground(Void... v) {
                StopWatch sw = new StopWatch().start();
                GHRequest req = new GHRequest(fromLat, fromLon, toLat, toLon).
                        setAlgorithm(Algorithms.DIJKSTRA_BI);
                req.getHints().
                        put(Routing.INSTRUCTIONS, "false");
                req.setVehicle("generic");
                req.setLocale("en-US");

//                  req.getHints().put("highways.steps", "0");
                req.getHints().put("isCheckedSteps", isCheckedSteps);
                req.getHints().put("isCheckedUpaths", isCheckedUpaths);
                req.getHints().put("inclineValue", inclineValue);

                GHResponse resp = hopper.route(req);
                time = sw.stop().getSeconds();
                log(resp.getDebugInfo());
                shortestPathRunning = false;
                return resp.getBest();
            }

            protected void onPostExecute(PathWrapper resp) {
                if (!resp.hasErrors()) {
                    log("from:" + fromLat + "," + fromLon + " to:" + toLat + ","
                            + toLon + " found path with distance:" + resp.getDistance()
                            / 1000f + ", nodes:" + resp.getPoints().getSize() + ", time:"
                            + time + " " + resp.getDebugInfo());
                    logUser("the route is " + (int) (resp.getDistance() / 100) / 10f
                            + "km long, time:" + resp.getTime() / 60000f + "min, debug:" + time);

                    pathLayer = createPathLayer(resp);
                    mapView.map().layers().add(pathLayer);
                    mapView.map().updateMap(true);
                } else {
                    logUser("Error:" + resp.getErrors());
                }
                shortestPathRunning = false;
            }
        }.execute();
    }

    private void log(String str) {
        Log.i("GH", str);
    }

    private void log(String str, Throwable t) {
        Log.i("GH", str, t);
    }

    private void logUser(String str) {
        log(str);
        Toast.makeText(this, str, Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, NEW_MENU_ID, 0, "Google");
        // menu.add(0, NEW_MENU_ID + 1, 0, "Other");
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case NEW_MENU_ID:
                if (start == null || end == null) {
                    logUser("tap screen to set start and end of route");
                    break;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW);
                // get rid of the dialog
                intent.setClassName("com.google.android.apps.maps",
                        "com.google.android.maps.MapsActivity");
                intent.setData(Uri.parse("http://maps.google.com/maps?saddr="
                        + start.getLatitude() + "," + start.getLongitude() + "&daddr="
                        + end.getLatitude() + "," + end.getLongitude()));
                startActivity(intent);
                break;
        }
        return true;
    }

    public interface MySpinnerListener {
        void onSelect(String selectedArea, String selectedFile);
    }

    class LongPressLayer extends Layer implements GestureListener {

        LongPressLayer(org.oscim.map.Map map) {
            super(map);
        }

        @Override
        public boolean onGesture(Gesture g, MotionEvent e) {
            if (g instanceof Gesture.LongPress) {
                GeoPoint p = mMap.viewport().fromScreenPoint(e.getX(), e.getY());
                return onLongPress(p);
            }
            return false;
        }
    }
}
