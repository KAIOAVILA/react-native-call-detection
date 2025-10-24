package com.pritesh.calldetection;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyCallback;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import java.util.HashMap;
import java.util.Map;

public class CallDetectionManagerModule
        extends ReactContextBaseJavaModule
        implements Application.ActivityLifecycleCallbacks,
        CallDetectionPhoneStateListener.PhoneCallStateUpdate {

    private boolean wasAppInOffHook = false;
    private boolean wasAppInRinging = false;
    private ReactApplicationContext reactContext;
    private TelephonyManager telephonyManager;
    private CallStateUpdateActionModule jsModule = null;
    private CallDetectionPhoneStateListener callDetectionPhoneStateListener;
    private Activity activity = null;
    private static final String PREF_NAME = "gerep_call";
    private static final String KEY_START = "start_ts";
    private static final String KEY_DURATION = "duration_sec";

    private boolean wasAppInOffHook = false;
    private boolean wasAppInRinging = false;
    private boolean callStateListenerRegistered = false;

    private static final String TAG = "CallDetectionMgr";

    public CallDetectionManagerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    private SharedPreferences getPrefs() {
          Context appContext = getReactApplicationContext().getApplicationContext();
          return appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private void clearStoredCall() {
          getPrefs().edit().remove(KEY_START).remove(KEY_DURATION).apply();
    }



     private TelephonyManager getTelephonyManager() {
            Context appContext = getReactApplicationContext().getApplicationContext();
            return (TelephonyManager) appContext.getSystemService(Context.TELEPHONY_SERVICE);
     }


    @Override
    public String getName() {
        return "CallDetectionManagerAndroid";
    }

     @ReactMethod
      public void startListener() {
          if (activity == null) {
              activity = getCurrentActivity();
              if (activity != null) {
                  activity.getApplication().registerActivityLifecycleCallbacks(this);
              }
          }

          telephonyManager = getTelephonyManager();
          if (telephonyManager == null) {
              Log.w(TAG, "TelephonyManager indisponível");
              return;
          }

          clearStoredCall();
          callDetectionPhoneStateListener = new CallDetectionPhoneStateListener(this);

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              if (ContextCompat.checkSelfPermission(
                      reactContext, Manifest.permission.READ_PHONE_STATE)
                      == PackageManager.PERMISSION_GRANTED) {
                  telephonyManager.registerTelephonyCallback(
                          ContextCompat.getMainExecutor(reactContext),
                          callStateListener
                  );
                  callStateListenerRegistered = true;
              } else {
                  Log.w(TAG, "READ_PHONE_STATE não concedida; listener inativo");
              }
          } else {
              telephonyManager.listen(
                      callDetectionPhoneStateListener,
                      PhoneStateListener.LISTEN_CALL_STATE
              );
              callStateListenerRegistered = true;
          }
    }


    @RequiresApi(api = android.os.Build.VERSION_CODES.S)
    private static abstract class CallStateListener extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        abstract public void onCallStateChanged(int state);
    }

    private boolean callStateListenerRegistered = false;

    private CallStateListener callStateListener = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) ?
            new CallStateListener() {
                @Override
                public void onCallStateChanged(int state) {
                    // Handle call state change
                    phoneCallStateUpdated(state, null);
                }
            }
            : null;

    @ReactMethod
    public void stopListener() {
        TelephonyManager manager =
                telephonyManager != null ? telephonyManager : getTelephonyManager();

        if (!callStateListenerRegistered || manager == null) {
            telephonyManager = null;
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                manager.unregisterTelephonyCallback(callStateListener);
            } catch (Exception e) {
                Log.w(TAG, "Falhou ao remover TelephonyCallback", e);
            }
        } else if (callDetectionPhoneStateListener != null) {
            manager.listen(callDetectionPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            callDetectionPhoneStateListener = null;
        }

        callStateListenerRegistered = false;
        telephonyManager = null;
    }

     @ReactMethod
      public void popLastDuration(Promise promise) {
          SharedPreferences prefs = getPrefs();
          long duration = prefs.getLong(KEY_DURATION, -1L);
          prefs.edit().remove(KEY_DURATION).remove(KEY_START).apply();

          if (duration <= 0) {
              promise.resolve(null);
          } else {
              promise.resolve((double) duration);
          }
      }


    /**
     * @return a map of constants this module exports to JS. Supports JSON types.
     */
    public
    Map<String, Object> getConstants() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("Incoming", "Incoming");
        map.put("Offhook", "Offhook");
        map.put("Disconnected", "Disconnected");
        map.put("Missed", "Missed");
        return map;
    }

    // Activity Lifecycle Methods
    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceType) {

    }

    @Override
    public void onActivityStarted(Activity activity) {

    }

    @Override
    public void onActivityResumed(Activity activity) {

    }

    @Override
    public void onActivityPaused(Activity activity) {

    }

    @Override
    public void onActivityStopped(Activity activity) {

    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {

    }

    @Override
    public void onActivityDestroyed(Activity activity) {

    }

    @Override
    public void phoneCallStateUpdated(int state, String phoneNumber) {
        jsModule = this.reactContext.getJSModule(CallStateUpdateActionModule.class);
        SharedPreferences prefs = getPrefs();

        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE:
                if (wasAppInOffHook) {
                    long startTs = prefs.getLong(KEY_START, -1L);
                    if (startTs > 0) {
                        long duration = Math.max(
                                0,
                                (System.currentTimeMillis() - startTs) / 1000
                        );
                        prefs.edit().remove(KEY_START)
                                .putLong(KEY_DURATION, duration)
                                .apply();
                    } else {
                        prefs.edit().remove(KEY_DURATION).apply();
                    }
                    jsModule.callStateUpdated("Disconnected", phoneNumber);
                } else if (wasAppInRinging) {
                    prefs.edit().remove(KEY_START).remove(KEY_DURATION).apply();
                    jsModule.callStateUpdated("Missed", phoneNumber);
                }

                wasAppInRinging = false;
                wasAppInOffHook = false;
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                wasAppInOffHook = true;
                prefs.edit()
                        .putLong(KEY_START, System.currentTimeMillis())
                        .remove(KEY_DURATION)
                        .apply();
                jsModule.callStateUpdated("Offhook", phoneNumber);
                break;

            case TelephonyManager.CALL_STATE_RINGING:
                wasAppInRinging = true;
                jsModule.callStateUpdated("Incoming", phoneNumber);
                break;
        }
    }
}
