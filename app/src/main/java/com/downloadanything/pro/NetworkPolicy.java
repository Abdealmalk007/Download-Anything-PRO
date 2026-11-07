package com.downloadanything.pro;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Runtime-only network gate. User preference is never rewritten by adaptive policy. */
public final class NetworkPolicy {
    private NetworkPolicy() {}

    public static boolean wifiOnly(Context c) {
        return c.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE).getBoolean("wifi_only", false);
    }

    public static boolean isAllowed(Context c) {
        try {
            ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n=cm==null?null:cm.getActiveNetwork();
            NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
            if (caps==null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
            if (!wifiOnly(c)) return true;
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        } catch (Exception e) { return false; }
    }

    public static String state(Context c) {
        if (!isAllowed(c)) return wifiOnly(c)?"waiting_for_wifi":"offline";
        try {
            ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n=cm==null?null:cm.getActiveNetwork(); NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
            if (caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
            if (caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        } catch (Exception ignored) {}
        return "connected";
    }
}
