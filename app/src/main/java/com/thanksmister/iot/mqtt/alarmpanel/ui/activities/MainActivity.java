/*
 * <!--
 *   ~ Copyright (c) 2017. ThanksMister LLC
 *   ~
 *   ~ Licensed under the Apache License, Version 2.0 (the "License");
 *   ~ you may not use this file except in compliance with the License. 
 *   ~ You may obtain a copy of the License at
 *   ~
 *   ~ http://www.apache.org/licenses/LICENSE-2.0
 *   ~
 *   ~ Unless required by applicable law or agreed to in writing, software distributed 
 *   ~ under the License is distributed on an "AS IS" BASIS, 
 *   ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 *   ~ See the License for the specific language governing permissions and 
 *   ~ limitations under the License.
 *   -->
 */

package com.thanksmister.iot.mqtt.alarmpanel.ui.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.thanksmister.iot.mqtt.alarmpanel.BaseActivity;
import com.thanksmister.iot.mqtt.alarmpanel.R;
import com.thanksmister.iot.mqtt.alarmpanel.network.MQTTService;
import com.thanksmister.iot.mqtt.alarmpanel.network.model.SubscriptionData;
import com.thanksmister.iot.mqtt.alarmpanel.tasks.SubscriptionDataTask;
import com.thanksmister.iot.mqtt.alarmpanel.ui.Configuration;
import com.thanksmister.iot.mqtt.alarmpanel.ui.fragments.ControlsFragment;
import com.thanksmister.iot.mqtt.alarmpanel.ui.views.AlarmDisableView;
import com.thanksmister.iot.mqtt.alarmpanel.ui.views.AlarmTriggeredView;
import com.thanksmister.iot.mqtt.alarmpanel.ui.views.SettingsCodeView;
import com.thanksmister.iot.mqtt.alarmpanel.utils.AlarmUtils;

import org.eclipse.paho.client.mqttv3.MqttException;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;
import timber.log.Timber;

import static com.thanksmister.iot.mqtt.alarmpanel.ui.Configuration.PREF_ARM_AWAY;
import static com.thanksmister.iot.mqtt.alarmpanel.ui.Configuration.PREF_AWAY_TRIGGERED_PENDING;
import static com.thanksmister.iot.mqtt.alarmpanel.ui.Configuration.PREF_HOME_TRIGGERED_PENDING;
import static com.thanksmister.iot.mqtt.alarmpanel.ui.Configuration.PREF_TRIGGERED;
import static com.thanksmister.iot.mqtt.alarmpanel.ui.Configuration.PREF_TRIGGERED_PENDING;

public class MainActivity extends BaseActivity implements ControlsFragment.OnControlsFragmentListener, 
        MQTTService.MqttManagerListener {
    
    @Bind(R.id.triggeredView)
    View triggeredView;

    @Bind(R.id.mainView)
    View mainView;

    @OnClick(R.id.buttonSettings)
    void buttonSettingsClicked() {
        showSettingsCodeDialog();
    }

    @OnClick(R.id.buttonLogs)
    void buttonLogsClicked() {
        Intent intent = LogActivity.createStartIntent(MainActivity.this);
        startActivity(intent);
    }
    
    @OnClick(R.id.buttonSleep)
    void buttonSleep() {
        showScreenSaver();
    }

    private MQTTService mqttService;
    private SubscriptionDataTask subscriptionDataTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ButterKnife.bind(this);

        if(getConfiguration().isFirstTime()) {
            showAlertDialog(getString(R.string.dialog_first_time), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    getConfiguration().setAlarmCode(1234); // set default code
                    Intent intent = SettingsActivity.createStartIntent(MainActivity.this);
                    startActivity(intent);
                }
            });
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        registerReceiver(connReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        initializeMqttService();
        resetInactivityTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(connReceiver);
        } catch (IllegalArgumentException e) {
            Timber.e(e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (subscriptionDataTask != null) {
            subscriptionDataTask.cancel(true);
        }
        clearMqttService();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.global, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.action_settings) {
            Intent intent = SettingsActivity.createStartIntent(MainActivity.this);
            startActivity(intent);
        } 
        return super.onOptionsItemSelected(item);
    }

    /**
     * We need to initialize or reset the MQTT service if our setup changes or
     * the activity is destroyed.  
     */
    private void initializeMqttService() {
        Timber.d("initializeMqttService");
        if (mqttService == null) {
            try {
                mqttService = new MQTTService(getApplicationContext(), readMqttOptions(), this);
            } catch (Throwable t) {
                // TODO should we loop back and try again? 
                Timber.e("Could not create MQTTPublisher" + t.getMessage());
            }
        } else if (readMqttOptions().hasUpdates()) {
            Timber.d("readMqttOptions().hasUpdates(): " + readMqttOptions().hasUpdates());
            try {
                mqttService.reconfigure(readMqttOptions());
            } catch (Throwable t) {
                // TODO should we loop back and try again? 
                Timber.e("Could not create MQTTPublisher" + t.getMessage());
            }
        }
    }

    private void clearMqttService() {
        Timber.d("clearMqttService");
        if(mqttService != null) {
            try {
                mqttService.close();
            } catch (MqttException e) {
                e.printStackTrace();
            }
            mqttService = null;
        }
    }

    public void publishArmedHome() {
        if(mqttService != null) {
            mqttService.publish(AlarmUtils.COMMAND_ARM_HOME);
        }
    }

    @Override
    public void publishArmedAway() {
        if(mqttService != null) {
            mqttService.publish(AlarmUtils.COMMAND_ARM_AWAY);
        }
    }

    @Override
    public void publishDisarmed() {
        if(mqttService != null) {
            mqttService.publish(AlarmUtils.COMMAND_DISARM);
        }
    }

    /**
     * Handles the state change and shows triggered view and remove any dialogs or screen savers if 
     * state is triggered. Returns to normal state if disarmed from HASS.
     * @param state
     */
    @AlarmUtils.AlarmStates
    private void handleStateChange(String state) {
        switch (state) {
            case AlarmUtils.STATE_DISARM:
                awakenDeviceForAction();
                resetInactivityTimer();
                hideDisableDialog();
                hideTriggeredView();
                break;
            case AlarmUtils.STATE_ARM_AWAY:
            case AlarmUtils.STATE_ARM_HOME:
                hideDisableDialog();
                resetInactivityTimer();
                hideDialog();
                break;
            case AlarmUtils.STATE_PENDING:
                hideDialog();
                hideProgressDialog();
                if (getConfiguration().getAlarmMode().equals(Configuration.PREF_ARM_HOME)
                        || getConfiguration().getAlarmMode().equals(PREF_ARM_AWAY)) {
                    getConfiguration().setAlarmMode(PREF_TRIGGERED_PENDING);
                    if (getConfiguration().getAlarmMode().equals(Configuration.PREF_ARM_HOME)){
                        getConfiguration().setAlarmMode(PREF_HOME_TRIGGERED_PENDING);
                    } else if(getConfiguration().getAlarmMode().equals(PREF_ARM_AWAY)) {
                        getConfiguration().setAlarmMode(PREF_AWAY_TRIGGERED_PENDING);
                    } else {
                        getConfiguration().setAlarmMode(PREF_TRIGGERED_PENDING);
                    }
                    awakenDeviceForAction();
                    if(getConfiguration().getPendingTime() > 0) {
                        showAlarmDisableDialog(true, getConfiguration().getPendingTime());
                    }
                } else {
                    awakenDeviceForAction();
                }
                break;
            case AlarmUtils.STATE_TRIGGERED:
                hideDialog();
                hideDisableDialog();
                hideProgressDialog();
                awakenDeviceForAction();
                showAlarmTriggered();
                getConfiguration().setAlarmMode(PREF_TRIGGERED);
                break;
            default:
                break;
        }
    }

    private void showAlarmTriggered() {
        int code = getConfiguration().getAlarmCode();
        final AlarmTriggeredView view = (AlarmTriggeredView) findViewById(R.id.alarmTriggeredView);
        view.setCode(code);
        view.setListener(new AlarmTriggeredView.ViewListener() {
            @Override
            public void onComplete(int code) {
                publishDisarmed();
            }
            @Override
            public void onError() {
                Toast.makeText(MainActivity.this, R.string.toast_code_invalid, Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onCancel() {
            }
        });
        mainView.setVisibility(View.GONE);
        triggeredView.setVisibility(View.VISIBLE);
    }

    private void hideTriggeredView() {
        mainView.setVisibility(View.VISIBLE);
        triggeredView.setVisibility(View.GONE);
    }

    /**
     * We need to awaken the device and allow the user to take action.
     */
    public void awakenDeviceForAction() {
        stopDisconnectTimer(); // stop screen saver mode
        closeScreenSaver(); // close screen saver
    }
    
    private SubscriptionDataTask getUpdateMqttDataTask() {
        SubscriptionDataTask dataTask = new SubscriptionDataTask(getStoreManager());
        dataTask.setOnExceptionListener(new SubscriptionDataTask.OnExceptionListener() {
            public void onException(Exception exception) {
                Timber.e("Update Exception: " + exception.getMessage());
            }
        });
        dataTask.setOnCompleteListener(new SubscriptionDataTask.OnCompleteListener<Boolean>() {
            public void onComplete(Boolean response) {
                if (!response) {
                    Timber.e("Update Exception response: " + response);
                }
            }
        });
        return dataTask;
    }
    
    private void showAlarmDisableDialog(boolean beep, int timeRemaining) {
        showAlarmDisableDialog(new AlarmDisableView.ViewListener() {
            @Override
            public void onComplete(int pin) {
                publishDisarmed();
                hideDialog();
            }
            @Override
            public void onError() {
                Toast.makeText(MainActivity.this, R.string.toast_code_invalid, Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onCancel() {
                hideDialog();
            }
        }, getConfiguration().getAlarmCode(), beep, timeRemaining);
    }

    private void showSettingsCodeDialog() {
        showSettingsCodeDialog(getConfiguration().getAlarmCode(), new SettingsCodeView.ViewListener() {
            @Override
            public void onComplete(int code) {
                if (code == getConfiguration().getAlarmCode()) {
                    Intent intent = SettingsActivity.createStartIntent(MainActivity.this);
                    startActivity(intent);
                }
            }
            @Override
            public void onError() {
                Toast.makeText(MainActivity.this, R.string.toast_code_invalid, Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onCancel() {
                hideDialog();
            }
        });
    }

    @Override
    public void subscriptionMessage(final String topic, final String payload, final String id) {
        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(AlarmUtils.hasSupportedStates(payload)) {
                    subscriptionDataTask = getUpdateMqttDataTask();
                    subscriptionDataTask.execute(new SubscriptionData(topic, payload, id));
                    handleStateChange(payload);
                }
            }
        });
    }

    @Override
    public void handleMqttException(final String errorMessage) {
        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showAlertDialog(errorMessage);
            }
        });
    }

    @Override
    public void handleMqttDisconnected() {
        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showAlertDialog(getString(R.string.error_mqtt_connection), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        subscriptionDataTask = getUpdateMqttDataTask();
                        subscriptionDataTask.execute(new SubscriptionData(readMqttOptions().getStateTopic(), AlarmUtils.STATE_ERROR, "0"));
                        clearMqttService();
                        initializeMqttService();
                    }
                });
                Timber.d("Unable to connect client.");
            }
        });
    }

    /**
     * Network connectivity receiver to notify client of the network disconnect issues and
     * to clear any network notifications when reconnected. It is easy for network connectivity
     * to run amok that is why we only notify the user once for network disconnect with 
     * a boolean flag. 
     */
    private BroadcastReceiver connReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager connectivityManager = ((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE));
            NetworkInfo currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
            if (currentNetworkInfo != null
                    && currentNetworkInfo.isConnected()) {
                Timber.d("Network Connected");
                hasNetwork.set(true);
                handleNetworkConnect();
            } else if (hasNetwork.get()) {
                Timber.d("Network Disconnected");
                hasNetwork.set(false);
                handleNetworkDisconnect();
            }
        }
    };
}