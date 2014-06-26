/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.settings.wifi;

import com.android.settings.R;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.NetworkInfo.DetailedState;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.preference.Preference;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.util.Map;


class AccessPoint extends Preference {
    static final String TAG = "Settings.AccessPoint";

    private static final String KEY_DETAILEDSTATE = "key_detailedstate";
    private static final String KEY_WIFIINFO = "key_wifiinfo";
    private static final String KEY_SCANRESULT = "key_scanresult";
    private static final String KEY_CONFIG = "key_config";

    private static final int[] STATE_SECURED = {
        R.attr.state_encrypted
    };
    private static final int[] STATE_NONE = {};

    private static int[] wifi_signal_attributes = { R.attr.wifi_signal };

    /** These values are matched in string arrays -- changes must be kept in sync */
    static final int SECURITY_NONE = 0;
    static final int SECURITY_WEP = 1;
    static final int SECURITY_PSK = 2;
    static final int SECURITY_EAP = 3;

    enum PskType {
        UNKNOWN,
        WPA,
        WPA2,
        WPA_WPA2
    }

    String ssid;
    String bssid;
    int security;
    int networkId = -1;
    boolean wpsAvailable = false;

    PskType pskType = PskType.UNKNOWN;

    private WifiConfiguration mConfig;
    /* package */ScanResult mScanResult;

    private int mRssi = Integer.MAX_VALUE;
    private long mSeen = 0;

    private WifiInfo mInfo;
    private DetailedState mState;

    private static final int VISIBILITY_MAX_AGE_IN_MILLI = 1000000;
    private static final int VISIBILITY_OUTDATED_AGE_IN_MILLI = 20000;
    private static final int LOWER_FREQ_24GHZ = 2400;
    private static final int HIGHER_FREQ_24GHZ = 2500;
    private static final int LOWER_FREQ_5GHZ = 4900;
    private static final int HIGHER_FREQ_5GHZ = 5900;
    private static final int SECOND_TO_MILLI = 1000;

    /** Experiemental: we should be able to show the user the list of BSSIDs and bands
     *  for that SSID.
     *  For now this data is used only with Verbose Logging so as to show the band and number
     *  of BSSIDs on which that network is seen.
     */
    public LruCache<String, ScanResult> scanResultCache;

    static int getSecurity(WifiConfiguration config) {
        if (config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
            return SECURITY_PSK;
        }
        if (config.allowedKeyManagement.get(KeyMgmt.WPA_EAP) ||
                config.allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {
            return SECURITY_EAP;
        }
        return (config.wepKeys[0] != null) ? SECURITY_WEP : SECURITY_NONE;
    }

    private static int getSecurity(ScanResult result) {
        if (result.capabilities.contains("WEP")) {
            return SECURITY_WEP;
        } else if (result.capabilities.contains("PSK")) {
            return SECURITY_PSK;
        } else if (result.capabilities.contains("EAP")) {
            return SECURITY_EAP;
        }
        return SECURITY_NONE;
    }

    public String getSecurityString(boolean concise) {
        Context context = getContext();
        switch(security) {
            case SECURITY_EAP:
                return concise ? context.getString(R.string.wifi_security_short_eap) :
                    context.getString(R.string.wifi_security_eap);
            case SECURITY_PSK:
                switch (pskType) {
                    case WPA:
                        return concise ? context.getString(R.string.wifi_security_short_wpa) :
                            context.getString(R.string.wifi_security_wpa);
                    case WPA2:
                        return concise ? context.getString(R.string.wifi_security_short_wpa2) :
                            context.getString(R.string.wifi_security_wpa2);
                    case WPA_WPA2:
                        return concise ? context.getString(R.string.wifi_security_short_wpa_wpa2) :
                            context.getString(R.string.wifi_security_wpa_wpa2);
                    case UNKNOWN:
                    default:
                        return concise ? context.getString(R.string.wifi_security_short_psk_generic)
                                : context.getString(R.string.wifi_security_psk_generic);
                }
            case SECURITY_WEP:
                return concise ? context.getString(R.string.wifi_security_short_wep) :
                    context.getString(R.string.wifi_security_wep);
            case SECURITY_NONE:
            default:
                return concise ? "" : context.getString(R.string.wifi_security_none);
        }
    }

    private static PskType getPskType(ScanResult result) {
        boolean wpa = result.capabilities.contains("WPA-PSK");
        boolean wpa2 = result.capabilities.contains("WPA2-PSK");
        if (wpa2 && wpa) {
            return PskType.WPA_WPA2;
        } else if (wpa2) {
            return PskType.WPA2;
        } else if (wpa) {
            return PskType.WPA;
        } else {
            Log.w(TAG, "Received abnormal flag string: " + result.capabilities);
            return PskType.UNKNOWN;
        }
    }

    AccessPoint(Context context, WifiConfiguration config) {
        super(context);
        loadConfig(config);
        refresh();
    }

    AccessPoint(Context context, ScanResult result) {
        super(context);
        loadResult(result);
        refresh();
    }

    AccessPoint(Context context, Bundle savedState) {
        super(context);

        mConfig = savedState.getParcelable(KEY_CONFIG);
        if (mConfig != null) {
            loadConfig(mConfig);
        }
        mScanResult = (ScanResult) savedState.getParcelable(KEY_SCANRESULT);
        if (mScanResult != null) {
            loadResult(mScanResult);
        }
        mInfo = (WifiInfo) savedState.getParcelable(KEY_WIFIINFO);
        if (savedState.containsKey(KEY_DETAILEDSTATE)) {
            mState = DetailedState.valueOf(savedState.getString(KEY_DETAILEDSTATE));
        }
        update(mInfo, mState);
    }

    public void saveWifiState(Bundle savedState) {
        savedState.putParcelable(KEY_CONFIG, mConfig);
        savedState.putParcelable(KEY_SCANRESULT, mScanResult);
        savedState.putParcelable(KEY_WIFIINFO, mInfo);
        if (mState != null) {
            savedState.putString(KEY_DETAILEDSTATE, mState.toString());
        }
    }

    private void loadConfig(WifiConfiguration config) {
        ssid = (config.SSID == null ? "" : removeDoubleQuotes(config.SSID));
        bssid = config.BSSID;
        security = getSecurity(config);
        networkId = config.networkId;
        mConfig = config;
    }

    private void loadResult(ScanResult result) {
        ssid = result.SSID;
        bssid = result.BSSID;
        security = getSecurity(result);
        wpsAvailable = security != SECURITY_EAP && result.capabilities.contains("WPS");
        if (security == SECURITY_PSK)
            pskType = getPskType(result);
        mRssi = result.level;
        mScanResult = result;
        if (result.seen > mSeen) {
            mSeen = result.seen;
        }
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        updateIcon(getLevel(), getContext());
        notifyChanged();
    }

    protected void updateIcon(int level, Context context) {
        if (level == -1) {
            setIcon(null);
        } else {
            Drawable drawable = getIcon();

            if (drawable == null) {
                drawable = context.getTheme().obtainStyledAttributes(
                        wifi_signal_attributes).getDrawable(0);
                setIcon(drawable);
            }

            if (drawable != null) {
                drawable.setLevel(level);
                drawable.setState((security != SECURITY_NONE) ? STATE_SECURED : STATE_NONE);
            }
        }
    }

    @Override
    public int compareTo(Preference preference) {
        if (!(preference instanceof AccessPoint)) {
            return 1;
        }
        AccessPoint other = (AccessPoint) preference;
        // Active one goes first.
        if (mInfo != null && other.mInfo == null) return -1;
        if (mInfo == null && other.mInfo != null) return 1;

        // Reachable one goes before unreachable one.
        if (mRssi != Integer.MAX_VALUE && other.mRssi == Integer.MAX_VALUE) return -1;
        if (mRssi == Integer.MAX_VALUE && other.mRssi != Integer.MAX_VALUE) return 1;
        if (mRssi == Integer.MAX_VALUE && other.mRssi != Integer.MAX_VALUE) return 1;

        // Configured one goes before unconfigured one.
        if (networkId != WifiConfiguration.INVALID_NETWORK_ID
                && other.networkId == WifiConfiguration.INVALID_NETWORK_ID) return -1;
        if (networkId == WifiConfiguration.INVALID_NETWORK_ID
                && other.networkId != WifiConfiguration.INVALID_NETWORK_ID) return 1;

        // Sort by signal strength.
        int difference = WifiManager.compareSignalLevel(other.mRssi, mRssi);
        if (difference != 0) {
            return difference;
        }
        // Sort by ssid.
        return ssid.compareToIgnoreCase(other.ssid);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AccessPoint)) return false;
        return (this.compareTo((AccessPoint) other) == 0);
    }

    @Override
    public int hashCode() {
        int result = 0;
        if (mInfo != null) result += 13 * mInfo.hashCode();
        result += 19 * mRssi;
        result += 23 * networkId;
        result += 29 * ssid.hashCode();
        return result;
    }

    boolean update(ScanResult result) {
        if (result.seen > mSeen) {
            mSeen = result.seen;
        }
        if (WifiSettings.mVerboseLogging > 0) {
            if (scanResultCache == null) {
                scanResultCache = new LruCache<String, ScanResult>(32);
            }
            scanResultCache.put(result.BSSID, result);
        }

        if (ssid.equals(result.SSID) && security == getSecurity(result)) {
            if (WifiManager.compareSignalLevel(result.level, mRssi) > 0) {
                int oldLevel = getLevel();
                mRssi = result.level;
                if (getLevel() != oldLevel) {
                    notifyChanged();
                }
            }
            // This flag only comes from scans, is not easily saved in config
            if (security == SECURITY_PSK) {
                pskType = getPskType(result);
            }
            mScanResult = result;
            refresh();
            return true;
        }
        return false;
    }

    void update(WifiInfo info, DetailedState state) {
        boolean reorder = false;
        if (info != null && networkId != WifiConfiguration.INVALID_NETWORK_ID
                && networkId == info.getNetworkId()) {
            reorder = (mInfo == null);
            mRssi = info.getRssi();
            mInfo = info;
            mState = state;
            refresh();
        } else if (mInfo != null) {
            reorder = true;
            mInfo = null;
            mState = null;
            refresh();
        }
        if (reorder) {
            notifyHierarchyChanged();
        }
    }

    int getLevel() {
        if (mRssi == Integer.MAX_VALUE) {
            return -1;
        }
        return WifiManager.calculateSignalLevel(mRssi, 4);
    }

    WifiConfiguration getConfig() {
        return mConfig;
    }

    WifiInfo getInfo() {
        return mInfo;
    }

    DetailedState getState() {
        return mState;
    }

    static String removeDoubleQuotes(String string) {
        int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"')
                && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    static String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }

    /** visibility status of the WifiConfiguration
     * @return autojoin debugging information
     * TODO: use a string formatter
     * ["rssi 5Ghz", "num results on 5GHz" / "rssi 5Ghz", "num results on 5GHz"]
     * For instance [-40,5/-30,2]
     */
    private String getVisibilityStatus() {
        StringBuilder visibility = new StringBuilder();

        long now = System.currentTimeMillis();
        long age = (now - mSeen);
        if (age < VISIBILITY_MAX_AGE_IN_MILLI) {
            //show age in seconds, in the form xx
            visibility.append(Long.toString((age / SECOND_TO_MILLI) % SECOND_TO_MILLI))
                    .append("s");
        } else {
            //not seen for more than 1000 seconds
            visibility.append("!");
        }

        if (mInfo != null) {
            visibility.append(" sc=").append(Integer.toString(mInfo.score));
            visibility.append(" ");
            visibility.append(String.format("tx=%.1f,", mInfo.txSuccessRate));
            visibility.append(String.format("%.1f,", mInfo.txRetriesRate));
            visibility.append(String.format("%.1f ", mInfo.txBadRate));
            visibility.append(String.format("rx=%.1f", mInfo.rxSuccessRate));
        }

        if (scanResultCache != null) {
            int rssi5 = WifiConfiguration.INVALID_RSSI;
            int rssi24 = WifiConfiguration.INVALID_RSSI;
            int num5 = 0;
            int num24 = 0;
            Map<String, ScanResult> list = scanResultCache.snapshot();
            for (ScanResult result : list.values()) {
                if (result.seen == 0)
                    continue;

                if (result.frequency > LOWER_FREQ_5GHZ
                        && result.frequency < HIGHER_FREQ_5GHZ) {
                    //strictly speaking: [4915, 5825]
                    //number of known BSSID on 5GHz band
                    num5 = num5 + 1;
                } else if (result.frequency > LOWER_FREQ_24GHZ
                        && result.frequency < HIGHER_FREQ_24GHZ) {
                    //strictly speaking: [2412, 2482]
                    //number of known BSSID on 2.4Ghz band
                    num24 = num24 + 1;
                }

                //ignore results seen, older than 20 seconds
                if (now - result.seen > VISIBILITY_OUTDATED_AGE_IN_MILLI) continue;

                if (result.frequency > LOWER_FREQ_5GHZ
                        &&result.frequency < HIGHER_FREQ_5GHZ) {
                    if (result.level > rssi5) {
                        rssi5 = result.level;
                    }
                } else if (result.frequency > LOWER_FREQ_24GHZ
                        && result.frequency < HIGHER_FREQ_24GHZ) {
                    if (result.level > rssi24) {
                        rssi24 = result.level;
                    }
                }
            }
            visibility.append(" [");
            if (num24 > 0 || rssi24 > WifiConfiguration.INVALID_RSSI) {
                visibility.append(Integer.toString(rssi24));
                visibility.append(",");
                visibility.append(Integer.toString(num24));
            }
            visibility.append(";");
            if (num5 > 0 || rssi5 > WifiConfiguration.INVALID_RSSI) {
                visibility.append(Integer.toString(rssi5));
                visibility.append(",");
                visibility.append(Integer.toString(num5));
            }
            visibility.append("]");
        } else {
            if (mRssi != Integer.MAX_VALUE) {
                visibility.append(", ss=");
                visibility.append(Integer.toString(mRssi));
                if (mScanResult != null) {
                    visibility.append(", ");
                    visibility.append(Integer.toString(mScanResult.frequency));
                }
            }
        }

        return visibility.toString();
    }

    /** Updates the title and summary; may indirectly call notifyChanged()  */
    private void refresh() {
        setTitle(ssid);

        final Context context = getContext();
        updateIcon(getLevel(), context);

        StringBuilder summary = new StringBuilder();

        if (mState != null) { // This is the active connection
            summary.append(Summary.get(context, mState));
        } else if (mConfig != null && ((mConfig.status == WifiConfiguration.Status.DISABLED &&
                mConfig.disableReason != WifiConfiguration.DISABLED_UNKNOWN_REASON)
               || mConfig.autoJoinStatus >= WifiConfiguration.AUTO_JOIN_DISABLED_ON_AUTH_FAILURE)) {
            if (mConfig.autoJoinStatus >= WifiConfiguration.AUTO_JOIN_DISABLED_ON_AUTH_FAILURE) {
                summary.append(context.getString(R.string.wifi_disabled_password_failure));
            } else {
                switch (mConfig.disableReason) {
                    case WifiConfiguration.DISABLED_AUTH_FAILURE:
                        summary.append(context.getString(R.string.wifi_disabled_password_failure));
                        break;
                    case WifiConfiguration.DISABLED_DHCP_FAILURE:
                    case WifiConfiguration.DISABLED_DNS_FAILURE:
                        summary.append(context.getString(R.string.wifi_disabled_network_failure));
                        break;
                    case WifiConfiguration.DISABLED_UNKNOWN_REASON:
                        //this state is not useful anymore as auto-join may attempt joining
                        //those networks
                        summary.append(context.getString(R.string.wifi_disabled_generic));
                }
            }
        } else if (mRssi == Integer.MAX_VALUE) { // Wifi out of range
            summary.append(context.getString(R.string.wifi_not_in_range));
        } else { // In range, not disabled.
            if (mConfig != null) { // Is saved network
                summary.append(context.getString(R.string.wifi_remembered));
            }

            if (security != SECURITY_NONE) {
                String securityStrFormat;
                if (summary.length() == 0) {
                    securityStrFormat = context.getString(R.string.wifi_secured_first_item);
                } else {
                    securityStrFormat = context.getString(R.string.wifi_secured_second_item);
                }
                summary.append(String.format(securityStrFormat, getSecurityString(true)));
            }

            if (mConfig == null && wpsAvailable) { // Only list WPS available for unsaved networks
                if (summary.length() == 0) {
                    summary.append(context.getString(R.string.wifi_wps_available_first_item));
                } else {
                    summary.append(context.getString(R.string.wifi_wps_available_second_item));
                }
            }
        }
        if (WifiSettings.mVerboseLogging > 0) {
            //add RSSI/band information for this config, what was seen up to 6 seconds ago
            //verbose WiFi Logging is only turned on thru developers settings
            if (mInfo != null && mState != null) { // This is the active connection
                summary.append(" (f=" + Integer.toString(mInfo.getFrequency()) + ")");
            }
            summary.append(" " + getVisibilityStatus());
            if (mConfig != null && mConfig.autoJoinStatus > 0) {
                summary.append(" (" + mConfig.autoJoinStatus);
                if (mConfig.blackListTimestamp > 0) {
                    long now = System.currentTimeMillis();
                    long diff = (now - mConfig.blackListTimestamp)/1000;
                    long sec = diff%60; //seconds
                    long min = (diff/60)%60; //minutes
                    long hour = (min/60)%60; //hours
                    summary.append(", ");
                    if (hour > 0) summary.append(Long.toString(hour) + "h ");
                    summary.append( Long.toString(min) + "m ");
                    summary.append( Long.toString(sec) + "s ");
                }
                summary.append(")");
            }
        }
        setSummary(summary.toString());
    }

    /**
     * Generate and save a default wifiConfiguration with common values.
     * Can only be called for unsecured networks.
     * @hide
     */
    protected void generateOpenNetworkConfig() {
        if (security != SECURITY_NONE)
            throw new IllegalStateException();
        if (mConfig != null)
            return;
        mConfig = new WifiConfiguration();
        mConfig.SSID = AccessPoint.convertToQuotedString(ssid);
        mConfig.allowedKeyManagement.set(KeyMgmt.NONE);
    }
}
