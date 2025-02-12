/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Telephony.Intents;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.Config;
import android.util.EventLog;
import android.util.Log;
import android.util.TimeUtils;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.RegStateResponse;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import com.android.internal.telephony.UiccCardApplication;
import com.android.internal.telephony.UiccManager;
import com.android.internal.telephony.UiccConstants.AppState;
import com.android.internal.telephony.UiccManager.AppFamily;

/**
 * {@hide}
 */
final class GsmServiceStateTracker extends ServiceStateTracker {
    static final String LOG_TAG = "GSM";
    static final boolean DBG = true;

    GSMPhone phone;
    GsmCellLocation cellLoc;
    GsmCellLocation newCellLoc;
    int mPreferredNetworkType;
    RestrictedState rs;

    /* icc stuff */
    UiccManager mUiccManager = null;
    UiccCardApplication m3gppApplication = null;
    SIMRecords mSIMRecords = null;

    /**
     *  Values correspond to ServiceStateTracker.DATA_ACCESS_ definitions.
     */
    private int networkType = 0;
    private int newNetworkType = 0;

    /**
     * GSM roaming status solely based on TS 27.007 7.2 CREG. Only used by
     * handlePollStateResult to store CREG roaming result.
     */
    private boolean mGsmRoaming = false;

    /**
     * Mark when service state is in emergency call only mode
     */
    private boolean mEmergencyOnly = false;
    private boolean mNewEmergencyOnly = false;

    /**
     * Sometimes we get the NITZ time before we know what country we
     * are in. Keep the time zone information from the NITZ string so
     * we can fix the time zone once know the country.
     */
    private boolean mNeedFixZone = false;
    private int mZoneOffset;
    private boolean mZoneDst;
    private long mZoneTime;
    private boolean mGotCountryCode = false;
    private ContentResolver cr;

    String mSavedTimeZone;
    long mSavedTime;
    long mSavedAtTime;

    /**
     * The Notification object given to the NotificationManager.
     */
    private Notification mNotification;

    /** Wake lock used while setting time of day. */
    private PowerManager.WakeLock mWakeLock;
    private static final String WAKELOCK_TAG = "ServiceStateTracker";

    /** Keep track of SPN display rules, so we only broadcast intent if something changes. */
    private String curSpn = null;
    private String curPlmn = null;
    private int curSpnRule = 0;

    /** Notification type. */
    static final int CS_ENABLED = 1003;            // Access Control blocks all voice/sms service
    static final int CS_DISABLED = 1004;           // Access Control enables all voice/sms service
    static final int CS_NORMAL_ENABLED = 1005;     // Access Control blocks normal voice/sms service
    static final int CS_EMERGENCY_ENABLED = 1006;  // Access Control blocks emergency call service

    /** Notification id. */
    static final int CS_NOTIFICATION = 999;  // Id to update and cancel CS restricted

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!phone.mIsTheCurrentActivePhone) {
                Log.e(LOG_TAG, "Received Intent " + intent +
                    " while being destroyed. Ignoring.");
                return;
            }

            if (intent.getAction().equals(Intent.ACTION_LOCALE_CHANGED)) {
                // update emergency string whenever locale changed
                updateSpnDisplay();
            }
        }
    };

    private ContentObserver mAutoTimeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Log.i("GsmServiceStateTracker", "Auto time state changed");
            revertToNitz();
        }
    };

    public GsmServiceStateTracker(GSMPhone phone) {
        super();

        this.phone = phone;
        cm = phone.mCM;
        ss = new ServiceState();
        newSS = new ServiceState();
        cellLoc = new GsmCellLocation();
        newCellLoc = new GsmCellLocation();
        rs = new RestrictedState();
        mSignalStrength = new SignalStrength();

        PowerManager powerManager =
                (PowerManager)phone.getContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

        cm.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        cm.registerForOn(this, EVENT_RADIO_ON, null);
        cm.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);

        cm.registerForNetworkStateChanged(this, EVENT_NETWORK_STATE_CHANGED, null);
        cm.setOnNITZTime(this, EVENT_NITZ_TIME, null);
        cm.setOnSignalStrengthUpdate(this, EVENT_SIGNAL_STRENGTH_UPDATE, null);
        cm.registerForRestrictedStateChanged(this, EVENT_RESTRICTED_STATE_CHANGED, null);

        mUiccManager = UiccManager.getInstance(phone.getContext(), this.cm);
        mUiccManager.registerForIccChanged(this, EVENT_ICC_CHANGED, null);

        cr = phone.getContext().getContentResolver();
        cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.AUTO_TIME), true,
                mAutoTimeObserver);
        setSignalStrengthDefaultValues();

        // Monitor locale change
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        phone.getContext().registerReceiver(mIntentReceiver, filter);
    }

    public void dispose() {
        // Unregister for all events.
        cm.unregisterForAvailable(this);
        cm.unregisterForOn(this);
        cm.unregisterForRadioStateChanged(this);
        cm.unregisterForNetworkStateChanged(this);

        cm.unSetOnSignalStrengthUpdate(this);
        cm.unregisterForRestrictedStateChanged(this);
        cm.unSetOnNITZTime(this);
        cr.unregisterContentObserver(this.mAutoTimeObserver);

        //cleanup icc stuff
        mUiccManager.unregisterForIccChanged(this);
        if (m3gppApplication != null) {
            m3gppApplication.unregisterForReady(this);
        }
        if(mSIMRecords != null) {
            mSIMRecords.unregisterForRecordsEvents(this);
            mSIMRecords.unregisterForRecordsLoaded(this);
        }
        m3gppApplication = null;
        mUiccManager = null;
        mSIMRecords = null;
    }

    protected void finalize() {
        if(DBG) Log.d(LOG_TAG, "GsmServiceStateTracker finalized");
    }

    void registerForNetworkAttach(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        networkAttachedRegistrants.add(r);

        if (ss.getState() == ServiceState.STATE_IN_SERVICE) {
            r.notifyRegistrant();
        }
    }

    void unregisterForNetworkAttach(Handler h) {
        networkAttachedRegistrants.remove(h);
    }

    public void handleMessage (Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;
        Message message;

        if (!phone.mIsTheCurrentActivePhone) {
            Log.e(LOG_TAG, "Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }
        switch (msg.what) {
            case EVENT_RADIO_AVAILABLE:
                //this is unnecessary
                break;

            case EVENT_ICC_CHANGED:
                updateIccAvailability();
                break;

            case EVENT_ICC_RECORD_EVENTS:
                ar = (AsyncResult)msg.obj;
                processIccRecordEvents((Integer)ar.result);
                break;

            case EVENT_SIM_READY:
                // The SIM is now ready i.e if it was locked
                // it has been unlocked. At this stage, the radio is already
                // powered on.
                boolean skipRestoringSelection = phone.getContext().getResources().getBoolean(
                        com.android.internal.R.bool.skip_restoring_network_selection);

                if (!skipRestoringSelection) {
                    // restore the previous network selection.
                    phone.restoreSavedNetworkSelection(null);
                }
                pollState();
                // Signal strength polling stops when radio is off
                queueNextSignalStrengthPoll();
                break;

            case EVENT_RADIO_STATE_CHANGED:
                // This will do nothing in the radio not
                // available case
                pollState();
                break;

            case EVENT_NETWORK_STATE_CHANGED:
                pollState();
                break;

            case EVENT_GET_SIGNAL_STRENGTH:
                // This callback is called when signal strength is polled
                // all by itself

                if (!(cm.getRadioState().isOn())) {
                    // Polling will continue when radio turns back on and not CDMA
                    return;
                }
                ar = (AsyncResult) msg.obj;
                onSignalStrengthResult(ar, phone, true);
                queueNextSignalStrengthPoll();

                break;

            case EVENT_GET_LOC_DONE:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    RegStateResponse r = (RegStateResponse)ar.result;
                    String states[] = r.getRecord(0);
                    int lac = -1;
                    int cid = -1;
                    if (states.length >= 3) {
                        try {
                            if (states[1] != null && states[1].length() > 0) {
                                lac = Integer.parseInt(states[1], 16);
                            }
                            if (states[2] != null && states[2].length() > 0) {
                                cid = Integer.parseInt(states[2], 16);
                            }
                        } catch (NumberFormatException ex) {
                            Log.w(LOG_TAG, "error parsing location: " + ex);
                        }
                    }
                    cellLoc.setLacAndCid(lac, cid);
                    phone.notifyLocationChanged();
                }

                // Release any temporary cell lock, which could have been
                // acquired to allow a single-shot location update.
                disableSingleLocationUpdate();
                break;

            case EVENT_POLL_STATE_REGISTRATION:
            case EVENT_POLL_STATE_OPERATOR:
            case EVENT_POLL_STATE_NETWORK_SELECTION_MODE:
                ar = (AsyncResult) msg.obj;

                handlePollStateResult(msg.what, ar);
                break;

            case EVENT_POLL_SIGNAL_STRENGTH:
                // Just poll signal strength...not part of pollState()

                cm.getSignalStrength(obtainMessage(EVENT_GET_SIGNAL_STRENGTH));
                break;

            case EVENT_NITZ_TIME:
                ar = (AsyncResult) msg.obj;

                String nitzString = (String)((Object[])ar.result)[0];
                long nitzReceiveTime = ((Long)((Object[])ar.result)[1]).longValue();

                setTimeFromNITZString(nitzString, nitzReceiveTime);
                break;

            case EVENT_SIGNAL_STRENGTH_UPDATE:
                // This is a notification from
                // CommandsInterface.setOnSignalStrengthUpdate

                ar = (AsyncResult) msg.obj;

                // The radio is telling us about signal strength changes
                // we don't have to ask it
                dontPollSignalStrength = true;

                onSignalStrengthResult(ar, phone, true);
                break;

            case EVENT_SIM_RECORDS_LOADED:
                updateSpnDisplay();
                break;

            case EVENT_LOCATION_UPDATES_ENABLED:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    cm.getRegistrationState(obtainMessage(EVENT_GET_LOC_DONE, null));
                }
                break;

            case EVENT_SET_PREFERRED_NETWORK_TYPE:
                ar = (AsyncResult) msg.obj;
                // Don't care the result, only use for dereg network (COPS=2)
                message = obtainMessage(EVENT_RESET_PREFERRED_NETWORK_TYPE, ar.userObj);
                cm.setPreferredNetworkType(mPreferredNetworkType, message);
                break;

            case EVENT_RESET_PREFERRED_NETWORK_TYPE:
                ar = (AsyncResult) msg.obj;
                if (ar.userObj != null) {
                    AsyncResult.forMessage(((Message) ar.userObj)).exception
                            = ar.exception;
                    ((Message) ar.userObj).sendToTarget();
                }
                break;

            case EVENT_GET_PREFERRED_NETWORK_TYPE:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    mPreferredNetworkType = ((int[])ar.result)[0];
                } else {
                    mPreferredNetworkType = RILConstants.NETWORK_MODE_GLOBAL;
                }

                message = obtainMessage(EVENT_SET_PREFERRED_NETWORK_TYPE, ar.userObj);
                int toggledNetworkType = RILConstants.NETWORK_MODE_GLOBAL;

                cm.setPreferredNetworkType(toggledNetworkType, message);
                break;

            case EVENT_RESTRICTED_STATE_CHANGED:
                // This is a notification from
                // CommandsInterface.registerForRestrictedStateChanged

                Log.d(LOG_TAG, "[DSAC DEB] " + "EVENT_RESTRICTED_STATE_CHANGED");

                ar = (AsyncResult) msg.obj;

                onRestrictedStateChanged(ar);
                break;

            default:
                Log.e(LOG_TAG, "Unhandled message with number: " + msg.what);
            break;
        }
    }

    public void updateEons() {
        boolean needsUpdate = false;
        int lac = -1;

        if (cellLoc != null) lac = cellLoc.getLac();
        if (phone.mSIMRecords == null) return;
        needsUpdate = phone.mSIMRecords.updateEons(ss.getOperatorNumeric(), lac);
        if (needsUpdate) {
            String eonsLong = phone.mSIMRecords.getEons();
            if (eonsLong != null) {
                // Update operator long name with EONS Long.
                ss.setOperatorName(eonsLong, ss.getOperatorAlphaShort(),
                      ss.getOperatorNumeric());
            }
            updateSpnDisplay();
        }
    }

    public void updateSpnDisplay() {
        if (mSIMRecords == null) {
            Log.e(LOG_TAG, "mSIMRecords null while updateSpnDisplay was called.");
            return;
        }
            int rule = mSIMRecords.getDisplayRule(ss.getOperatorNumeric());
            String spn = phone.mSIMRecords.getServiceProviderName();
            String plmn = ss.getOperatorAlphaLong();

            // For emergency calls only, pass the EmergencyCallsOnly string via
            // EXTRA_PLMN
            if (mEmergencyOnly && cm.getRadioState().isOn()) {
                plmn = Resources.getSystem().getText(
                        com.android.internal.R.string.emergency_calls_only).toString();
            }

            if (rule != curSpnRule || !TextUtils.equals(spn, curSpn)
                    || !TextUtils.equals(plmn, curPlmn)) {

                boolean showSpn = !mEmergencyOnly
                    && (rule & SIMRecords.SPN_RULE_SHOW_SPN) == SIMRecords.SPN_RULE_SHOW_SPN
                    && (rule & SIMRecords.SPN_RULE_SHOW_SPN) == SIMRecords.SPN_RULE_SHOW_SPN
                    && (ss.getState() == ServiceState.STATE_IN_SERVICE);
                boolean showPlmn =
                    (rule & SIMRecords.SPN_RULE_SHOW_PLMN) == SIMRecords.SPN_RULE_SHOW_PLMN;

                Intent intent = new Intent(Intents.SPN_STRINGS_UPDATED_ACTION);
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                intent.putExtra(Intents.EXTRA_SHOW_SPN, showSpn);
                intent.putExtra(Intents.EXTRA_SPN, spn);
                intent.putExtra(Intents.EXTRA_SHOW_PLMN, showPlmn);
                intent.putExtra(Intents.EXTRA_PLMN, plmn);
                phone.getContext().sendStickyBroadcast(intent);
            }

            curSpnRule = rule;
            curSpn = spn;
            curPlmn = plmn;
    }

    /**
     * Handle the result of one of the pollState()-related requests
     */
    protected void handlePollStateResult (int what, AsyncResult ar) {
        int ints[];
        String states[];

        // Ignore stale requests from last poll
        if (ar.userObj != pollingContext) return;

        if (ar.exception != null) {
            CommandException.Error err=null;

            if (ar.exception instanceof CommandException) {
                err = ((CommandException)(ar.exception)).getCommandError();
            }

            if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                // Radio has crashed or turned off
                cancelPollState();
                return;
            }

            if (!cm.getRadioState().isOn()) {
                // Radio has crashed or turned off
                cancelPollState();
                return;
            }

            if (err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW &&
                    err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW) {
                Log.e(LOG_TAG,
                        "RIL implementation has returned an error where it must succeed" +
                        ar.exception);
            }
        } else try {
            switch (what) {
                case EVENT_POLL_STATE_REGISTRATION:
                    RegStateResponse r = (RegStateResponse)ar.result;
                    states = r.getRecord(0);

                    int lac = -1;
                    int cid = -1;
                    int regState = -1;
                    int psc = -1;
                    int type = 0;
                    if (states.length > 0) {
                        try {
                            regState = Integer.parseInt(states[0]);
                            if (states.length >= 4) {
                                if (states[1] != null && states[1].length() > 0) {
                                    lac = Integer.parseInt(states[1], 16);
                                }
                                if (states[2] != null && states[2].length() > 0) {
                                    cid = Integer.parseInt(states[2], 16);
                                }
                                type = Integer.parseInt(states[3]);
                            }
                            if (states.length > 14) {
                                if (states[14] != null && states[14].length() > 0) {
                                    psc = Integer.parseInt(states[14], 16);
                                }
                            }
                        } catch (NumberFormatException ex) {
                            Log.w(LOG_TAG, "error parsing RegistrationState: " + ex);
                        }
                    }

                    mGsmRoaming = regCodeIsRoaming(regState);
                    newSS.setState (regCodeToServiceState(regState));

                    if (regState == 10 || regState == 12 || regState == 13 || regState == 14) {
                        mEmergencyOnly = true;
                    } else {
                        mEmergencyOnly = false;
                    }

                    // LAC and CID are -1 if not avail
                    newCellLoc.setLacAndCid(lac, cid);
                    newCellLoc.setPsc(psc);
                    newNetworkType = type;
                    newSS.setRadioTechnology(type);
                break;

                case EVENT_POLL_STATE_OPERATOR:
                    String opNames[] = (String[])ar.result;

                    if (opNames != null && opNames.length >= 3) {
                        newSS.setOperatorName (
                                opNames[0], opNames[1], opNames[2]);
                    }
                break;

                case EVENT_POLL_STATE_NETWORK_SELECTION_MODE:
                    ints = (int[])ar.result;
                    newSS.setIsManualSelection(ints[0] == 1);
                break;
            }

        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "Exception while polling service state. "
                            + "Probably malformed RIL response.", ex);
        }

        pollingContext[0]--;

        if (pollingContext[0] == 0) {
            /**
             *  Since the roaming states of gsm service (from +CREG) and
             *  data service (from +CGREG) could be different, the new SS
             *  is set roaming while either one is roaming.
             *
             *  There is an exception for the above rule. The new SS is not set
             *  as roaming while gsm service reports roaming but indeed it is
             *  not roaming between operators.
             */
            boolean roaming = mGsmRoaming;
            if (mGsmRoaming && !isRoamingBetweenOperators(mGsmRoaming, newSS)) {
                roaming = false;
            }
            newSS.setRoaming(roaming);
            newSS.setEmergencyOnly(mEmergencyOnly);
            pollStateDone();
        }
    }

    private void setSignalStrengthDefaultValues() {
        mSignalStrength = new SignalStrength();
        mSignalStrength.setGsm(true);
    }

    /**
     * A complete "service state" from our perspective is
     * composed of a handful of separate requests to the radio.
     *
     * We make all of these requests at once, but then abandon them
     * and start over again if the radio notifies us that some
     * event has changed
     */
    private void pollState() {
        pollingContext = new int[1];
        pollingContext[0] = 0;

        switch (cm.getRadioState()) {
            case RADIO_UNAVAILABLE:
                newSS.setStateOutOfService();
                newCellLoc.setStateInvalid();
                setSignalStrengthDefaultValues();
                mGotCountryCode = false;
                pollStateDone();
            break;

            case RADIO_OFF:
                newSS.setStateOff();
                newCellLoc.setStateInvalid();
                setSignalStrengthDefaultValues();
                mGotCountryCode = false;
                pollStateDone();
            break;

            default:
                // Issue all poll-related commands at once
                // then count down the responses, which
                // are allowed to arrive out-of-order

                pollingContext[0]++;
                cm.getOperator(
                    obtainMessage(
                        EVENT_POLL_STATE_OPERATOR, pollingContext));

                pollingContext[0]++;
                cm.getRegistrationState(
                    obtainMessage(
                        EVENT_POLL_STATE_REGISTRATION, pollingContext));

                pollingContext[0]++;
                cm.getNetworkSelectionMode(
                    obtainMessage(
                        EVENT_POLL_STATE_NETWORK_SELECTION_MODE, pollingContext));
            break;
        }
    }

    private void pollStateDone() {
        if (DBG) {
            Log.d(LOG_TAG, "Poll ServiceState done: " +
                " oldSS=[" + ss + "] newSS=[" + newSS +
                "] ");
        }

        boolean hasRegistered =
            ss.getState() != ServiceState.STATE_IN_SERVICE
            && newSS.getState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered =
            ss.getState() == ServiceState.STATE_IN_SERVICE
            && newSS.getState() != ServiceState.STATE_IN_SERVICE;

        boolean hasNetworkTypeChanged = networkType != newNetworkType;

        boolean hasChanged = !newSS.equals(ss);

        boolean hasRoamingOn = !ss.getRoaming() && newSS.getRoaming();

        boolean hasRoamingOff = ss.getRoaming() && !newSS.getRoaming();

        boolean hasLocationChanged = !newCellLoc.equals(cellLoc);

        // Add an event log when connection state changes
        if (ss.getState() != newSS.getState()) {
            EventLog.writeEvent(EventLogTags.GSM_SERVICE_STATE_CHANGE,
                ss.getState(), newSS.getState());
        }

        ServiceState tss;
        tss = ss;
        ss = newSS;
        newSS = tss;
        // clean slate for next time
        newSS.setStateOutOfService();

        GsmCellLocation tcl = cellLoc;
        cellLoc = newCellLoc;
        newCellLoc = tcl;

        // Add an event log when network type switched
        // TODO: we may add filtering to reduce the event logged,
        // i.e. check preferred network setting, only switch to 2G, etc
        if (hasNetworkTypeChanged) {
            int cid = -1;
            GsmCellLocation loc = ((GsmCellLocation)phone.getCellLocation());
            if (loc != null) cid = loc.getCid();
            EventLog.writeEvent(EventLogTags.GSM_RAT_SWITCHED, cid, networkType, newNetworkType);
            Log.d(LOG_TAG, "RAT switched " + networkType + " -> "
                    + newNetworkType + " at cell " + cid);
        }

        networkType = newNetworkType;

        newSS.setStateOutOfService(); // clean slate for next time

        if (hasRegistered) {
            networkAttachedRegistrants.notifyRegistrants();
        }

        Log.i(LOG_TAG,"Network State Changed, get EONS and update operator name display");
        updateEons();

        if (hasChanged) {
            String operatorNumeric;

            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ALPHA,
                ss.getOperatorAlphaLong());

            operatorNumeric = ss.getOperatorNumeric();
            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, operatorNumeric);

            if (operatorNumeric == null) {
                phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, "");
            } else {
                String iso = "";
                try{
                    iso = MccTable.countryCodeForMcc(Integer.parseInt(
                            operatorNumeric.substring(0,3)));
                } catch ( NumberFormatException ex){
                    Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
                } catch ( StringIndexOutOfBoundsException ex) {
                    Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
                }

                phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, iso);
                mGotCountryCode = true;

                if (mNeedFixZone) {
                    TimeZone zone = null;
                    // If the offset is (0, false) and the timezone property
                    // is set, use the timezone property rather than
                    // GMT.
                    String zoneName = SystemProperties.get(TIMEZONE_PROPERTY);
                    if ((mZoneOffset == 0) && (mZoneDst == false) &&
                        (zoneName != null) && (zoneName.length() > 0) &&
                        (Arrays.binarySearch(GMT_COUNTRY_CODES, iso) < 0)) {
                        zone = TimeZone.getDefault();
                        // For NITZ string without timezone,
                        // need adjust time to reflect default timezone setting
                        long tzOffset;
                        tzOffset = zone.getOffset(System.currentTimeMillis());
                        if (getAutoTime()) {
                            setAndBroadcastNetworkSetTime(System.currentTimeMillis() - tzOffset);
                        } else {
                            // Adjust the saved NITZ time to account for tzOffset.
                            mSavedTime = mSavedTime - tzOffset;
                        }
                    } else if (iso.equals("")){
                        // Country code not found.  This is likely a test network.
                        // Get a TimeZone based only on the NITZ parameters (best guess).
                        zone = getNitzTimeZone(mZoneOffset, mZoneDst, mZoneTime);
                    } else {
                        zone = TimeUtils.getTimeZone(mZoneOffset,
                            mZoneDst, mZoneTime, iso);
                    }

                    mNeedFixZone = false;

                    if (zone != null) {
                        if (getAutoTime()) {
                            setAndBroadcastNetworkSetTimeZone(zone.getID());
                        }
                        saveNitzTimeZone(zone.getID());
                    }
                }
            }

            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISROAMING,
                ss.getRoaming() ? "true" : "false");

            phone.notifyServiceStateChanged(ss);
        }

        if (hasChanged) {
            updateSpnDisplay();
        }

        if (hasRoamingOn) {
            roamingOnRegistrants.notifyRegistrants();
        }

        if (hasRoamingOff) {
            roamingOffRegistrants.notifyRegistrants();
        }

        if (hasLocationChanged) {
            phone.notifyLocationChanged();
        }

    }

    private void processIccRecordEvents(int eventCode) {
        switch (eventCode) {
            case SIMRecords.EVENT_SPN:
                updateSpnDisplay();
                break;
            case SIMRecords.EVENT_EONS:
                int lac = -1;
                if (cellLoc != null) lac = cellLoc.getLac();
                boolean needsSpnUpdate = phone.mSIMRecords.updateEons(ss.getOperatorNumeric(), lac);
                if (needsSpnUpdate) {
                    updateSpnDisplay();
                }
                break;
        }
    }

    void updateIccAvailability() {

        UiccCardApplication new3gppApplication = mUiccManager
                .getCurrentApplication(AppFamily.APP_FAM_3GPP);

        if (m3gppApplication != new3gppApplication) {
            if (m3gppApplication != null) {
                log("Removing stale 3gpp Application.");
                m3gppApplication.unregisterForReady(this);
                if (mSIMRecords != null) {
                    mSIMRecords.unregisterForRecordsEvents(this);
                    mSIMRecords.unregisterForRecordsLoaded(this);
                    mSIMRecords = null;
                }
                m3gppApplication = null;
            }
            if (new3gppApplication != null) {
                log("New 3gpp application found");
                m3gppApplication = new3gppApplication;
                m3gppApplication.registerForReady(this, EVENT_SIM_READY, null);
                mSIMRecords = (SIMRecords) m3gppApplication.getApplicationRecords();
                mSIMRecords.registerForRecordsLoaded(this, EVENT_SIM_RECORDS_LOADED, null);
                mSIMRecords.registerForRecordsEvents(this, EVENT_ICC_RECORD_EVENTS, null);
            }
        }
    }

    /**
     * Returns a TimeZone object based only on parameters from the NITZ string.
     */
    private TimeZone getNitzTimeZone(int offset, boolean dst, long when) {
        TimeZone guess = findTimeZone(offset, dst, when);
        if (guess == null) {
            // Couldn't find a proper timezone.  Perhaps the DST data is wrong.
            guess = findTimeZone(offset, !dst, when);
        }
        if (DBG) {
            Log.d(LOG_TAG, "getNitzTimeZone returning "
                    + (guess == null ? guess : guess.getID()));
        }
        return guess;
    }

    private TimeZone findTimeZone(int offset, boolean dst, long when) {
        int rawOffset = offset;
        if (dst) {
            rawOffset -= 3600000;
        }
        String[] zones = TimeZone.getAvailableIDs(rawOffset);
        TimeZone guess = null;
        Date d = new Date(when);
        for (String zone : zones) {
            TimeZone tz = TimeZone.getTimeZone(zone);
            if (tz.getOffset(when) == offset &&
                tz.inDaylightTime(d) == dst) {
                guess = tz;
                break;
            }
        }

        return guess;
    }

    private void queueNextSignalStrengthPoll() {
        if (dontPollSignalStrength) {
            // The radio is telling us about signal strength changes
            // we don't have to ask it
            return;
        }

        Message msg;

        msg = obtainMessage();
        msg.what = EVENT_POLL_SIGNAL_STRENGTH;

        long nextTime;

        // TODO Don't poll signal strength if screen is off
        sendMessageDelayed(msg, POLL_PERIOD_MILLIS);
    }

    /**
     * Set restricted state based on the OnRestrictedStateChanged notification
     * If any voice or packet restricted state changes, trigger a UI
     * notification and notify registrants when sim is ready.
     *
     * @param ar an int value of RIL_RESTRICTED_STATE_*
     */
    private void onRestrictedStateChanged(AsyncResult ar) {
        Log.d(LOG_TAG, "[DSAC DEB] " + "onRestrictedStateChanged");
        RestrictedState newRs = new RestrictedState();

        Log.d(LOG_TAG, "[DSAC DEB] " + "current rs at enter "+ rs);

        if (ar.exception == null) {
            int[] ints = (int[])ar.result;
            int state = ints[0];

            newRs.setCsEmergencyRestricted(
                    ((state & RILConstants.RIL_RESTRICTED_STATE_CS_EMERGENCY) != 0) ||
                    ((state & RILConstants.RIL_RESTRICTED_STATE_CS_ALL) != 0) );
            //ignore the normal call and data restricted state before SIM READY
            if (m3gppApplication != null && m3gppApplication.getState() == AppState.APPSTATE_READY) {
                newRs.setCsNormalRestricted(
                        ((state & RILConstants.RIL_RESTRICTED_STATE_CS_NORMAL) != 0) ||
                        ((state & RILConstants.RIL_RESTRICTED_STATE_CS_ALL) != 0) );
            }

            Log.d(LOG_TAG, "[DSAC DEB] " + "new rs "+ newRs);

            /**
             * There are two kind of cs restriction, normal and emergency. So
             * there are 4 x 4 combinations in current and new restricted states
             * and we only need to notify when state is changed.
             */
            if (rs.isCsRestricted()) {
                if (!newRs.isCsRestricted()) {
                    // remove all restriction
                    setNotification(CS_DISABLED);
                } else if (!newRs.isCsNormalRestricted()) {
                    // remove normal restriction
                    setNotification(CS_EMERGENCY_ENABLED);
                } else if (!newRs.isCsEmergencyRestricted()) {
                    // remove emergency restriction
                    setNotification(CS_NORMAL_ENABLED);
                }
            } else if (rs.isCsEmergencyRestricted() && !rs.isCsNormalRestricted()) {
                if (!newRs.isCsRestricted()) {
                    // remove all restriction
                    setNotification(CS_DISABLED);
                } else if (newRs.isCsRestricted()) {
                    // enable all restriction
                    setNotification(CS_ENABLED);
                } else if (newRs.isCsNormalRestricted()) {
                    // remove emergency restriction and enable normal restriction
                    setNotification(CS_NORMAL_ENABLED);
                }
            } else if (!rs.isCsEmergencyRestricted() && rs.isCsNormalRestricted()) {
                if (!newRs.isCsRestricted()) {
                    // remove all restriction
                    setNotification(CS_DISABLED);
                } else if (newRs.isCsRestricted()) {
                    // enable all restriction
                    setNotification(CS_ENABLED);
                } else if (newRs.isCsEmergencyRestricted()) {
                    // remove normal restriction and enable emergency restriction
                    setNotification(CS_EMERGENCY_ENABLED);
                }
            } else {
                if (newRs.isCsRestricted()) {
                    // enable all restriction
                    setNotification(CS_ENABLED);
                } else if (newRs.isCsEmergencyRestricted()) {
                    // enable emergency restriction
                    setNotification(CS_EMERGENCY_ENABLED);
                } else if (newRs.isCsNormalRestricted()) {
                    // enable normal restriction
                    setNotification(CS_NORMAL_ENABLED);
                }
            }

            rs = newRs;
        }
        Log.d(LOG_TAG, "[DSAC DEB] " + "current rs at return "+ rs);
    }

    /** code is registration state 0-5 from TS 27.007 7.2 */
    private int regCodeToServiceState(int code) {
        phone.setPowerSaveStatus(false);
        switch (code) {
            case 0:
                phone.setPowerSaveStatus(true);
                return ServiceState.STATE_OUT_OF_SERVICE;

            case 2: // 2 is "searching"
            case 3: // 3 is "registration denied"
            case 4: // 4 is "unknown" no vaild in current baseband
            case 10:// same as 0, but indicates that emergency call is possible.
            case 12:// same as 2, but indicates that emergency call is possible.
            case 13:// same as 3, but indicates that emergency call is possible.
            case 14:// same as 4, but indicates that emergency call is possible.
                return ServiceState.STATE_OUT_OF_SERVICE;

            case 1:
                return ServiceState.STATE_IN_SERVICE;

            case 5:
                // in service, roam
                return ServiceState.STATE_IN_SERVICE;

            default:
                Log.w(LOG_TAG, "unexpected service state " + code);
                return ServiceState.STATE_OUT_OF_SERVICE;
        }
    }


    /**
     * code is registration state 0-5 from TS 27.007 7.2
     * returns true if registered roam, false otherwise
     */
    private boolean regCodeIsRoaming (int code) {
        // 5 is  "in service -- roam"
        return 5 == code;
    }

    /**
     * Set roaming state when gsmRoaming is true and, if operator mcc is the
     * same as sim mcc, ons is different from spn
     * @param gsmRoaming TS 27.007 7.2 CREG registered roaming
     * @param s ServiceState hold current ons
     * @return true for roaming state set
     */
    private boolean isRoamingBetweenOperators(boolean gsmRoaming, ServiceState s) {

        boolean mvnoRoaming = Settings.System.getInt(
                phone.getContext().getContentResolver(),
                Settings.Secure.MVNO_ROAMING, 0) == 1;

        String spn;

        spn = SystemProperties.get(TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA, "empty");

        String onsl = s.getOperatorAlphaLong();
        String onss = s.getOperatorAlphaShort();

        boolean equalsOnsl = onsl != null && spn.equals(onsl);
        boolean equalsOnss = onss != null && spn.equals(onss);

        String simNumeric = SystemProperties.get(
                TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, "");
        String  operatorNumeric = s.getOperatorNumeric();

        boolean equalsMcc = true;
        try {
            equalsMcc = simNumeric.substring(0, 3).
                    equals(operatorNumeric.substring(0, 3));
        } catch (Exception e){
        }

        return gsmRoaming && !(equalsMcc && (equalsOnsl || equalsOnss || mvnoRoaming));
    }

    private static int twoDigitsAt(String s, int offset) {
        int a, b;

        a = Character.digit(s.charAt(offset), 10);
        b = Character.digit(s.charAt(offset+1), 10);

        if (a < 0 || b < 0) {

            throw new RuntimeException("invalid format");
        }

        return a*10 + b;
    }

    /**
     * Provides the name of the algorithmic time zone for the specified
     * offset.  Taken from TimeZone.java.
     */
    private static String displayNameFor(int off) {
        off = off / 1000 / 60;

        char[] buf = new char[9];
        buf[0] = 'G';
        buf[1] = 'M';
        buf[2] = 'T';

        if (off < 0) {
            buf[3] = '-';
            off = -off;
        } else {
            buf[3] = '+';
        }

        int hours = off / 60;
        int minutes = off % 60;

        buf[4] = (char) ('0' + hours / 10);
        buf[5] = (char) ('0' + hours % 10);

        buf[6] = ':';

        buf[7] = (char) ('0' + minutes / 10);
        buf[8] = (char) ('0' + minutes % 10);

        return new String(buf);
    }

    /**
     * nitzReceiveTime is time_t that the NITZ time was posted
     */
    private void setTimeFromNITZString (String nitz, long nitzReceiveTime) {
        // "yy/mm/dd,hh:mm:ss(+/-)tz"
        // tz is in number of quarter-hours

        long start = SystemClock.elapsedRealtime();
        Log.i(LOG_TAG, "NITZ: " + nitz + "," + nitzReceiveTime +
                        " start=" + start + " delay=" + (start - nitzReceiveTime));

        try {
            /* NITZ time (hour:min:sec) will be in UTC but it supplies the timezone
             * offset as well (which we won't worry about until later) */
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

            c.clear();
            c.set(Calendar.DST_OFFSET, 0);

            String[] nitzSubs = nitz.split("[/:,+-]");

            int year = 2000 + Integer.parseInt(nitzSubs[0]);
            c.set(Calendar.YEAR, year);

            // month is 0 based!
            int month = Integer.parseInt(nitzSubs[1]) - 1;
            c.set(Calendar.MONTH, month);

            int date = Integer.parseInt(nitzSubs[2]);
            c.set(Calendar.DATE, date);

            int hour = Integer.parseInt(nitzSubs[3]);
            c.set(Calendar.HOUR, hour);

            int minute = Integer.parseInt(nitzSubs[4]);
            c.set(Calendar.MINUTE, minute);

            int second = Integer.parseInt(nitzSubs[5]);
            c.set(Calendar.SECOND, second);

            boolean sign = (nitz.indexOf('-') == -1);

            int tzOffset = Integer.parseInt(nitzSubs[6]);

            int dst = (nitzSubs.length >= 8 ) ? Integer.parseInt(nitzSubs[7])
                                              : 0;

            // The zone offset received from NITZ is for current local time,
            // so DST correction is already applied.  Don't add it again.
            //
            // tzOffset += dst * 4;
            //
            // We could unapply it if we wanted the raw offset.

            tzOffset = (sign ? 1 : -1) * tzOffset * 15 * 60 * 1000;

            TimeZone    zone = null;

            // As a special extension, the Android emulator appends the name of
            // the host computer's timezone to the nitz string. this is zoneinfo
            // timezone name of the form Area!Location or Area!Location!SubLocation
            // so we need to convert the ! into /
            if (nitzSubs.length >= 9) {
                String  tzname = nitzSubs[8].replace('!','/');
                zone = TimeZone.getTimeZone( tzname );
            }

            String iso = SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY);

            if (zone == null) {

                if (mGotCountryCode) {
                    if (iso != null && iso.length() > 0) {
                        zone = TimeUtils.getTimeZone(tzOffset, dst != 0,
                                c.getTimeInMillis(),
                                iso);
                    } else {
                        // We don't have a valid iso country code.  This is
                        // most likely because we're on a test network that's
                        // using a bogus MCC (eg, "001"), so get a TimeZone
                        // based only on the NITZ parameters.
                        zone = getNitzTimeZone(tzOffset, (dst != 0), c.getTimeInMillis());
                    }
                }
            }

            if (zone == null) {
                // We got the time before the country, so we don't know
                // how to identify the DST rules yet.  Save the information
                // and hope to fix it up later.

                mNeedFixZone = true;
                mZoneOffset  = tzOffset;
                mZoneDst     = dst != 0;
                mZoneTime    = c.getTimeInMillis();
            }

            if (zone != null) {
                if (getAutoTime()) {
                    setAndBroadcastNetworkSetTimeZone(zone.getID());
                }
                saveNitzTimeZone(zone.getID());
            }

            String ignore = SystemProperties.get("gsm.ignore-nitz");
            if (ignore != null && ignore.equals("yes")) {
                Log.i(LOG_TAG, "NITZ: Not setting clock because gsm.ignore-nitz is set");
                return;
            }

            try {
                mWakeLock.acquire();

                if (getAutoTime()) {
                    long millisSinceNitzReceived
                            = SystemClock.elapsedRealtime() - nitzReceiveTime;

                    if (millisSinceNitzReceived < 0) {
                        // Sanity check: something is wrong
                        Log.i(LOG_TAG, "NITZ: not setting time, clock has rolled "
                                            + "backwards since NITZ time was received, "
                                            + nitz);
                        return;
                    }

                    if (millisSinceNitzReceived > Integer.MAX_VALUE) {
                        // If the time is this far off, something is wrong > 24 days!
                        Log.i(LOG_TAG, "NITZ: not setting time, processing has taken "
                                        + (millisSinceNitzReceived / (1000 * 60 * 60 * 24))
                                        + " days");
                        return;
                    }

                    // Note: with range checks above, cast to int is safe
                    c.add(Calendar.MILLISECOND, (int)millisSinceNitzReceived);

                    Log.i(LOG_TAG, "NITZ: Setting time of day to " + c.getTime()
                        + " NITZ receive delay(ms): " + millisSinceNitzReceived
                        + " gained(ms): "
                        + (c.getTimeInMillis() - System.currentTimeMillis())
                        + " from " + nitz);

                    setAndBroadcastNetworkSetTime(c.getTimeInMillis());
                    Log.i(LOG_TAG, "NITZ: after Setting time of day");
                }
                SystemProperties.set("gsm.nitz.time", String.valueOf(c.getTimeInMillis()));
                saveNitzTime(c.getTimeInMillis());
                if (Config.LOGV) {
                    long end = SystemClock.elapsedRealtime();
                    Log.v(LOG_TAG, "NITZ: end=" + end + " dur=" + (end - start));
                }
            } finally {
                mWakeLock.release();
            }
        } catch (NumberFormatException ex) {
            Log.e(LOG_TAG, "NITZ: Parsing NITZ time " + nitz, ex);
        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "NITZ: Parsing NITZ time " + nitz, ex);
        }
    }

    private boolean getAutoTime() {
        try {
            return Settings.System.getInt(phone.getContext().getContentResolver(),
                    Settings.System.AUTO_TIME) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    private void saveNitzTimeZone(String zoneId) {
        mSavedTimeZone = zoneId;
    }

    private void saveNitzTime(long time) {
        mSavedTime = time;
        mSavedAtTime = SystemClock.elapsedRealtime();
    }

    /**
     * Set the timezone and send out a sticky broadcast so the system can
     * determine if the timezone was set by the carrier.
     *
     * @param zoneId timezone set by carrier
     */
    private void setAndBroadcastNetworkSetTimeZone(String zoneId) {
        AlarmManager alarm =
            (AlarmManager) phone.getContext().getSystemService(Context.ALARM_SERVICE);
        alarm.setTimeZone(zoneId);
        Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_SET_TIMEZONE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("time-zone", zoneId);
        phone.getContext().sendStickyBroadcast(intent);
    }

    /**
     * Set the time and Send out a sticky broadcast so the system can determine
     * if the time was set by the carrier.
     *
     * @param time time set by network
     */
    private void setAndBroadcastNetworkSetTime(long time) {
        SystemClock.setCurrentTimeMillis(time);
        Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_SET_TIME);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("time", time);
        phone.getContext().sendStickyBroadcast(intent);
    }

    private void revertToNitz() {
        if (Settings.System.getInt(phone.getContext().getContentResolver(),
                Settings.System.AUTO_TIME, 0) == 0) {
            return;
        }
        Log.d(LOG_TAG, "Reverting to NITZ: tz='" + mSavedTimeZone
                + "' mSavedTime=" + mSavedTime
                + " mSavedAtTime=" + mSavedAtTime);
        if (mSavedTimeZone != null && mSavedTime != 0 && mSavedAtTime != 0) {
            setAndBroadcastNetworkSetTimeZone(mSavedTimeZone);
            setAndBroadcastNetworkSetTime(mSavedTime
                    + (SystemClock.elapsedRealtime() - mSavedAtTime));
        }
    }

    /**
     * Post a notification to NotificationManager for restricted state
     *
     * @param notifyType is one state of PS/CS_*_ENABLE/DISABLE
     */
    private void setNotification(int notifyType) {

        Log.d(LOG_TAG, "[DSAC DEB] " + "create notification " + notifyType);
        Context context = phone.getContext();

        mNotification = new Notification();
        mNotification.when = System.currentTimeMillis();
        mNotification.flags = Notification.FLAG_AUTO_CANCEL;
        mNotification.icon = com.android.internal.R.drawable.stat_sys_warning;
        Intent intent = new Intent();
        mNotification.contentIntent = PendingIntent
        .getActivity(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);

        CharSequence details = "";
        CharSequence title = context.getText(com.android.internal.R.string.RestrictedChangedTitle);
        int notificationId = CS_NOTIFICATION;

        switch (notifyType) {
        case CS_ENABLED:
            details = context.getText(com.android.internal.R.string.RestrictedOnAll);;
            break;
        case CS_NORMAL_ENABLED:
            details = context.getText(com.android.internal.R.string.RestrictedOnNormal);;
            break;
        case CS_EMERGENCY_ENABLED:
            details = context.getText(com.android.internal.R.string.RestrictedOnEmergency);;
            break;
        case CS_DISABLED:
            // do nothing and cancel the notification later
            break;
        }

        Log.d(LOG_TAG, "[DSAC DEB] " + "put notification " + title + " / " +details);
        mNotification.tickerText = title;
        mNotification.setLatestEventInfo(context, title, details,
                mNotification.contentIntent);

        NotificationManager notificationManager = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (notifyType == CS_DISABLED) {
            // cancel previous post notification
            notificationManager.cancel(notificationId);
        } else {
            // update restricted state notification
            notificationManager.notify(notificationId, mNotification);
        }
    }

    private void log(String s) {
        Log.d(LOG_TAG, "[GsmServiceStateTracker] " + s);
    }
}
