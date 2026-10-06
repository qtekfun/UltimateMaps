package org.ultimatemaps.spike;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.FrameLayout;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Prototipo descartable: MapLibre Native + PMTiles local (estilo protomaps basemaps).
 * Extras (am start --es/--ez/--ed): style (nombre en getExternalFilesDir, def. style.json),
 * lat, lon, zoom (double), texture (bool, def. true: TextureView para que gfxinfo vea los frames),
 * demo (bool: secuencia de zoom+giro programatica tras el primer render completo).
 */
public class MainActivity extends Activity {
    private static final String TAG = "SPIKE";
    private final long t0 = SystemClock.elapsedRealtime();
    private MapView mapView;
    private MapLibreMap map;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean logged = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MapLibre.getInstance(this);
        final double lat = getIntent().getDoubleExtra("lat", 40.4168);
        final double lon = getIntent().getDoubleExtra("lon", -3.7038);
        final double zoom = getIntent().getDoubleExtra("zoom", 15.0);
        final boolean demo = getIntent().getBooleanExtra("demo", false);
        boolean texture = getIntent().getBooleanExtra("texture", true);
        final String styleName = getIntent().hasExtra("style") ? getIntent().getStringExtra("style") : "style.json";

        MapLibreMapOptions opts = MapLibreMapOptions.createFromAttributes(this)
                .textureMode(texture)
                .camera(new CameraPosition.Builder().target(new LatLng(lat, lon)).zoom(zoom).build());
        mapView = new MapView(this, opts);
        setContentView(new FrameLayout(this) {{ addView(mapView); }});
        mapView.onCreate(b);
        Log.i(TAG, "mapview_created ms=" + (SystemClock.elapsedRealtime() - t0) + " texture=" + texture);

        mapView.addOnDidFinishRenderingMapListener(fully -> {
            if (fully && !logged) {
                logged = true;
                Log.i(TAG, "first_full_render ms=" + (SystemClock.elapsedRealtime() - t0));
                if (demo) h.postDelayed(this::runDemo, 500);
            }
        });
        mapView.getMapAsync(m -> {
            map = m;
            try {
                File dir = getIntent().getBooleanExtra("external", false) ? getExternalFilesDir(null) : getFilesDir();
                String json = new String(readAll(new File(dir, styleName)), StandardCharsets.UTF_8)
                        .replace("@DIR@", dir.getAbsolutePath());
                m.setStyle(new Style.Builder().fromJson(json), s ->
                        Log.i(TAG, "style_loaded ms=" + (SystemClock.elapsedRealtime() - t0)));
            } catch (Exception e) {
                Log.e(TAG, "style error", e);
            }
        });
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(f)) { return in.readAllBytes(); }
    }

    /** Secuencia reproducible: zoom in/out y giro, 10 s en total. */
    private void runDemo() {
        Log.i(TAG, "demo_start");
        CameraPosition p = map.getCameraPosition();
        double z = p.zoom;
        long[] t = {0};
        step(t, 2000, z - 3, 0, 40);   // zoom out 3 niveles
        step(t, 2000, z, 0, 40);       // zoom in
        step(t, 2000, z, 120, 40);     // giro 120 grados con inclinacion
        step(t, 2000, z, -120, 0);     // giro inverso
        h.postDelayed(() -> Log.i(TAG, "demo_end"), t[0] + 100);
    }

    private void step(long[] t, int dur, double zoom, double bearing, double tilt) {
        h.postDelayed(() -> map.easeCamera(CameraUpdateFactory.newCameraPosition(
                new CameraPosition.Builder().zoom(zoom).bearing(bearing).tilt(tilt).build()), dur), t[0]);
        t[0] += dur + 100;
    }

    @Override protected void onStart() { super.onStart(); mapView.onStart(); }
    @Override protected void onResume() { super.onResume(); mapView.onResume(); }
    @Override protected void onPause() { mapView.onPause(); super.onPause(); }
    @Override protected void onStop() { mapView.onStop(); super.onStop(); }
    @Override public void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }
    @Override protected void onDestroy() { mapView.onDestroy(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); mapView.onSaveInstanceState(o); }
}
