/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2010, Code Aurora Forum. All rights reserved.
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

package com.android.internal.telephony.cdma;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
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
import android.provider.Settings.Secure;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Telephony.Intents;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.cdma.CdmaCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.TimeUtils;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.RegStateResponse;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.UiccCardApplication;
import com.android.internal.telephony.UiccManager;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.UiccConstants.AppState;
import com.android.internal.telephony.UiccManager.AppFamily;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * {@hide}
 */
final class CdmaServiceStateTracker extends ServiceStateTracker {
    static final String LOG_TAG = "CDMA";

    CDMAPhone phone;
    CdmaCellLocation cellLoc;
    CdmaCellLocation newCellLoc;

    /* icc stuff */
    UiccManager mUiccManager = null;
    UiccCardApplication m3gpp2Application = null;
    RuimRecords mRuimRecords = null;

    int mCdmaSubscriptionSource = Phone.CDMA_SUBSCRIPTION_NV;
    private CdmaSubscriptionSourceManager mCdmaSSM;

     /** if time between NTIZ updates is less than mNitzUpdateSpacing the update may be ignored. */
    private static final int NITZ_UPDATE_SPACING_DEFAULT = 1000 * 60 * 10;
    private int mNitzUpdateSpacing = SystemProperties.getInt("ro.nitz_update_spacing",
            NITZ_UPDATE_SPACING_DEFAULT);

    /** If mNitzUpdateSpacing hasn't been exceeded but update is > mNitzUpdate do the update */
    private static final int NITZ_UPDATE_DIFF_DEFAULT = 2000;
    private int mNitzUpdateDiff = SystemProperties.getInt("ro.nitz_update_diff",
            NITZ_UPDATE_DIFF_DEFAULT);

    /**
     *  Values correspond to ServiceStateTracker.DATA_ACCESS_ definitions.
     */
    private int networkType = 0;
    private int newNetworkType = 0;

    private boolean mCdmaRoaming = false;
    private int mRoamingIndicator;
    private boolean mIsInPrl;
    private int mDefaultRoamingIndicator;

    /**
     * Initially assume no data connection.
     */
    private int mRegistrationState = -1;
    private RegistrantList cdmaForSubscriptionInfoReadyRegistrants = new RegistrantList();
    private RegistrantList cdmaSubscriptionSourceChangedRegistrants = new RegistrantList();

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
    String mSavedTimeZone;
    long mSavedTime;
    long mSavedAtTime;

    /** Wake lock used while setting time of day. */
    private PowerManager.WakeLock mWakeLock;
    private static final String WAKELOCK_TAG = "ServiceStateTracker";

    /** Track of SPN display rules, so we only broadcast intent if something changes. */
    private String curSpn = null;
    private int curSpnRule = 0;

    /** Contains the name of the registered network in CDMA (either ONS or ERI text). */
    private String curPlmn = null;

    private String mMdn;
    private int mHomeSystemId[] = null;
    private int mHomeNetworkId[] = null;
    private String mMin;
    private String mPrlVersion;
    private boolean mIsMinInfoReady = false;

    private boolean isEriTextLoaded = false;
    private boolean mSubscriptionSourceUnknown = true;

    /* Used only for debugging purposes. */
    private String mRegistrationDeniedReason;

    private ContentResolver cr;
    private String currentCarrier = null;

    private ContentObserver mAutoTimeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Log.i("CdmaServiceStateTracker", "Auto time state called ...");
            revertToNitz();

        }
    };

    public CdmaServiceStateTracker(CDMAPhone phone) {
        super();

        this.phone = phone;
        cr = phone.getContext().getContentResolver();
        cm = phone.mCM;
        ss = new ServiceState();
        newSS = new ServiceState();
        cellLoc = new CdmaCellLocation();
        newCellLoc = new CdmaCellLocation();
        mSignalStrength = new SignalStrength();
        mCdmaSSM = CdmaSubscriptionSourceManager.getInstance(phone.getContext(), cm,
                new Registrant(this, EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null));
        mCdmaSubscriptionSource = mCdmaSSM.getCdmaSubscriptionSource();

        PowerManager powerManager =
                (PowerManager)phone.getContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

        cm.registerForOn(this, EVENT_RADIO_ON, null);
        cm.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);

        cm.registerForNetworkStateChanged(this, EVENT_NETWORK_STATE_CHANGED_CDMA, null);
        cm.setOnNITZTime(this, EVENT_NITZ_TIME, null);
        cm.setOnSignalStrengthUpdate(this, EVENT_SIGNAL_STRENGTH_UPDATE, null);
        cm.registerForCdmaPrlChanged(this, EVENT_CDMA_PRL_VERSION_CHANGED, null);

        mUiccManager = UiccManager.getInstance(phone.getContext(), this.cm);
        mUiccManager.registerForIccChanged(this, EVENT_ICC_CHANGED, null);

        phone.registerForEriFileLoaded(this, EVENT_ERI_FILE_LOADED, null);
        cm.registerForCdmaOtaProvision(this,EVENT_OTA_PROVISION_STATUS_CHANGE, null);

        cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.AUTO_TIME), true,
                mAutoTimeObserver);
        setSignalStrengthDefaultValues();

    }

    public void dispose() {
        // Unregister for all events.
        cm.unregisterForOn(this);
        cm.unregisterForRadioStateChanged(this);
        cm.unregisterForNetworkStateChanged(this);

        cm.unregisterForCdmaOtaProvision(this);
        phone.unregisterForEriFileLoaded(this);
        cm.unSetOnSignalStrengthUpdate(this);
        cm.unSetOnNITZTime(this);
        cr.unregisterContentObserver(this.mAutoTimeObserver);
        mCdmaSSM.dispose(this);
        cm.unregisterForCdmaPrlChanged(this);

        //cleanup icc stuff
        mUiccManager.unregisterForIccChanged(this);
        if (m3gpp2Application != null) {
            m3gpp2Application.unregisterForReady(this);
        }
        if(mRuimRecords != null) {
            mRuimRecords.unregisterForRecordsLoaded(this);
        }
        m3gpp2Application = null;
        mUiccManager = null;
        mRuimRecords = null;
    }

    @Override
    protected void finalize() {
        if (DBG) log("CdmaServiceStateTracker finalized");
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

    /**
     * Registration point for subscription info ready
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForSubscriptionInfoReady(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        cdmaForSubscriptionInfoReadyRegistrants.add(r);

        if (isMinInfoReady()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForSubscriptionInfoReady(Handler h) {
        cdmaForSubscriptionInfoReadyRegistrants.remove(h);
    }

    /**
     * Registration point for NV ready
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForCdmaSubscriptionSourceChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        cdmaSubscriptionSourceChangedRegistrants.add(r);
    }

    public void unregisterForCdmaSubscriptionSourceChanged(Handler h) {
        cdmaSubscriptionSourceChangedRegistrants.remove(h);
    }

    /**
     * Save current source of cdma subscription
     * @param source - 1 for NV, 0 for RUIM
     */
    private void saveCdmaSubscriptionSource(int source) {
        Log.d(LOG_TAG, "Storing cdma subscription source: " + source);
        Secure.putInt(phone.getContext().getContentResolver(),
                Secure.CDMA_SUBSCRIPTION_MODE,
                source );
    }

    private void getSubscriptionInfoAndStartPollingThreads() {
        cm.getCDMASubscription(obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION));
        cm.getCdmaPrlVersion(obtainMessage(EVENT_GET_CDMA_PRL_VERSION));

        // Get Registration Information
        pollState();
    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;

        if (!phone.mIsTheCurrentActivePhone) {
            Log.e(LOG_TAG, "Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }

        switch (msg.what) {
        case EVENT_RADIO_ON:
            handleCdmaSubscriptionSource();
            cm.getCDMASubscription( obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION));
            cm.getCdmaPrlVersion(obtainMessage(EVENT_GET_CDMA_PRL_VERSION));

            // Signal strength polling stops when radio is off.
            queueNextSignalStrengthPoll();
            break;

        case EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
            handleCdmaSubscriptionSource();
            break;

        case EVENT_ICC_CHANGED:
            updateIccAvailability();
            break;

        case EVENT_RUIM_READY:

            cm.getCDMASubscription(obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION));
            if (DBG) log("Receive EVENT_RUIM_READY and Send Request getCDMASubscription.");
            getSubscriptionInfoAndStartPollingThreads();
            break;

        case EVENT_NV_READY:
            // For Non-RUIM phones, the subscription information is stored in
            // Non Volatile. Here when Non-Volatile is ready, we can poll the CDMA
            // subscription info.
            getSubscriptionInfoAndStartPollingThreads();
            break;

        case EVENT_CDMA_PRL_VERSION_CHANGED :
            cm.getCdmaPrlVersion(obtainMessage(EVENT_GET_CDMA_PRL_VERSION));
            break;

        case EVENT_GET_CDMA_PRL_VERSION :
            ar = (AsyncResult) msg.obj;
            if (ar.exception == null) {
                mPrlVersion = (String) ar.result;
            }

            break;

        case EVENT_RADIO_STATE_CHANGED:
            // This will do nothing in the 'radio not available' case.
            pollState();
            break;

        case EVENT_NETWORK_STATE_CHANGED_CDMA:
            pollState();
            break;

        case EVENT_GET_SIGNAL_STRENGTH:
            // This callback is called when signal strength is polled
            // all by itself.

            if (!(cm.getRadioState().isOn())) {
                // Polling will continue when radio turns back on.
                return;
            }
            ar = (AsyncResult) msg.obj;
            onSignalStrengthResult(ar, phone, false);
            queueNextSignalStrengthPoll();

            break;

        case EVENT_GET_LOC_DONE_CDMA:
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                RegStateResponse r = (RegStateResponse)ar.result;
                String states[] = r.getRecord(0);
                int baseStationId = -1;
                int baseStationLatitude = CdmaCellLocation.INVALID_LAT_LONG;
                int baseStationLongitude = CdmaCellLocation.INVALID_LAT_LONG;
                int systemId = -1;
                int networkId = -1;

                if (states.length > 9) {
                    try {
                        if (states[4] != null) {
                            baseStationId = Integer.parseInt(states[4]);
                        }
                        if (states[5] != null) {
                            baseStationLatitude = Integer.parseInt(states[5]);
                        }
                        if (states[6] != null) {
                            baseStationLongitude = Integer.parseInt(states[6]);
                        }
                        // Some carriers only return lat-lngs of 0,0
                        if (baseStationLatitude == 0 && baseStationLongitude == 0) {
                            baseStationLatitude  = CdmaCellLocation.INVALID_LAT_LONG;
                            baseStationLongitude = CdmaCellLocation.INVALID_LAT_LONG;
                        }
                        if (states[8] != null) {
                            systemId = Integer.parseInt(states[8]);
                        }
                        if (states[9] != null) {
                            networkId = Integer.parseInt(states[9]);
                        }
                    } catch (NumberFormatException ex) {
                        Log.w(LOG_TAG, "error parsing cell location data: " + ex);
                    }
                }

                cellLoc.setCellLocationData(baseStationId, baseStationLatitude,
                        baseStationLongitude, systemId, networkId);
                phone.notifyLocationChanged();
            }

            // Release any temporary cell lock, which could have been
            // acquired to allow a single-shot location update.
            disableSingleLocationUpdate();
            break;

        case EVENT_POLL_STATE_REGISTRATION_CDMA:
        case EVENT_POLL_STATE_OPERATOR_CDMA:
            ar = (AsyncResult) msg.obj;
            handlePollStateResult(msg.what, ar);
            break;

        case EVENT_POLL_STATE_CDMA_SUBSCRIPTION: // Handle RIL_CDMA_SUBSCRIPTION
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                String cdmaSubscription[] = (String[])ar.result;
                if (cdmaSubscription != null && cdmaSubscription.length >= 4) {
                    mMdn = cdmaSubscription[0];
                    if (cdmaSubscription[1] != null) {
                        String[] sid = cdmaSubscription[1].split(",");
                        mHomeSystemId = new int[sid.length];
                        for (int i = 0; i < sid.length; i++) {
                            try {
                                mHomeSystemId[i] = Integer.parseInt(sid[i]);
                            } catch (NumberFormatException ex) {
                                Log.e(LOG_TAG, "error parsing system id: ", ex);
                            }
                        }
                    }
                    Log.d(LOG_TAG,"GET_CDMA_SUBSCRIPTION SID=" + cdmaSubscription[1] );

                    if (cdmaSubscription[2] != null) {
                        String[] nid = cdmaSubscription[2].split(",");
                        mHomeNetworkId = new int[nid.length];
                        for (int i = 0; i < nid.length; i++) {
                            try {
                                mHomeNetworkId[i] = Integer.parseInt(nid[i]);
                            } catch (NumberFormatException ex) {
                                Log.e(LOG_TAG, "error parsing network id: ", ex);
                            }
                        }
                    }
                    Log.d(LOG_TAG,"GET_CDMA_SUBSCRIPTION NID=" + cdmaSubscription[2] );
                    mMin = cdmaSubscription[3];
                    if (cdmaSubscription.length > 4 ){
                        mPrlVersion = cdmaSubscription[4];
                    }
                    Log.d(LOG_TAG,"GET_CDMA_SUBSCRIPTION PRL version=" + mPrlVersion);
                    Log.d(LOG_TAG,"GET_CDMA_SUBSCRIPTION MDN=" + mMdn);
                    //Notify apps subscription info is ready
                    if (cdmaForSubscriptionInfoReadyRegistrants != null) {
                        cdmaForSubscriptionInfoReadyRegistrants.notifyRegistrants();
                    }
                    if (!mIsMinInfoReady) {
                        mIsMinInfoReady = true;
                    }
                    if (mRuimRecords != null) {
                        mRuimRecords.setImsi(getImsi());
                    }
                } else {
                    Log.w(LOG_TAG,"error parsing cdmaSubscription params num="
                            + cdmaSubscription.length);
                }
            }
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
            // This is a notification from CommandsInterface.setOnSignalStrengthUpdate.

            ar = (AsyncResult) msg.obj;

            // The radio is telling us about signal strength changes,
            // so we don't have to ask it.
            dontPollSignalStrength = true;

            onSignalStrengthResult(ar, phone, false);
            break;

        case EVENT_RUIM_RECORDS_LOADED:
            updateSpnDisplay();
            break;

        case EVENT_LOCATION_UPDATES_ENABLED:
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                cm.getRegistrationState(obtainMessage(EVENT_GET_LOC_DONE_CDMA, null));
            }
            break;

        case EVENT_ERI_FILE_LOADED:
            // Repoll the state once the ERI file has been loaded.
            if (DBG) log("[CdmaServiceStateTracker] ERI file has been loaded, repolling.");
            pollState();
            break;

        case EVENT_OTA_PROVISION_STATUS_CHANGE:
            ar = (AsyncResult)msg.obj;
            if (ar.exception == null) {
                ints = (int[]) ar.result;
                int otaStatus = ints[0];
                if (otaStatus == phone.CDMA_OTA_PROVISION_STATUS_COMMITTED
                    || otaStatus == phone.CDMA_OTA_PROVISION_STATUS_OTAPA_STOPPED) {
                    Log.d(LOG_TAG, "Received OTA_PROGRAMMING Complete,Reload MDN ");
                    cm.getCDMASubscription( obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION));
                }
            }
            break;

        default:
            Log.e(LOG_TAG, "Unhandled message with number: " + msg.what);
        break;
        }
    }

    //***** Private Instance Methods

    /**
     * Handles the call to get the subscription source
     *
     * @param holds the new CDMA subscription source value
     */
    private void handleCdmaSubscriptionSource() {
        int newSubscriptionSource = mCdmaSSM.getCdmaSubscriptionSource();
        if (newSubscriptionSource != mCdmaSubscriptionSource) {
            mCdmaSubscriptionSource = newSubscriptionSource;
            saveCdmaSubscriptionSource(mCdmaSubscriptionSource);

            if (newSubscriptionSource == Phone.CDMA_SUBSCRIPTION_NV) {
                // NV is ready when subscription source is NV
                sendMessage(obtainMessage(EVENT_NV_READY));
            }
        }
        pollState();
    }

    @Override
    protected void updateSpnDisplay() {
        String spn = "";
        boolean showSpn = false;
        String plmn = "";
        boolean showPlmn = false;
        int rule = 0;

        if (mCdmaSubscriptionSource == Phone.CDMA_SUBSCRIPTION_RUIM_SIM
                && m3gpp2Application != null
                && m3gpp2Application.getState() == AppState.APPSTATE_READY) {
            // TODO RUIM SPN is not implemnted, EF_SPN has to be read and Display Condition
            //   Character Encoding, Language Indicator and SPN has to be set
            // rule = phone.mRuimRecords.getDisplayRule(ss.getOperatorNumeric());
            // spn = phone.mSIMRecords.getServiceProvideName();
            plmn = ss.getOperatorAlphaLong(); // mOperatorAlphaLong contains the ONS
            // showSpn = (rule & ...
            showPlmn = true; // showPlmn = (rule & ...

        } else {
            // In this case there is no SPN available from RUIM, we show the ERI text
            plmn = ss.getOperatorAlphaLong(); // mOperatorAlphaLong contains the ERI text
            showPlmn = true;
        }

        if (rule != curSpnRule
                || !TextUtils.equals(spn, curSpn)
                || !TextUtils.equals(plmn, curPlmn)) {
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

    @Override
    protected void handlePollStateResult (int what, AsyncResult ar) {
        int ints[];
        String states[];

        // Ignore stale requests from last poll.
        if (ar.userObj != pollingContext) return;

        if (ar.exception != null) {
            CommandException.Error err=null;

            if (ar.exception instanceof CommandException) {
                err = ((CommandException)(ar.exception)).getCommandError();
            }

            if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                // Radio has crashed or turned off.
                cancelPollState();
                return;
            }

            if (!cm.getRadioState().isOn()) {
                // Radio has crashed or turned off.
                cancelPollState();
                return;
            }

            if (err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW &&
                    err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW) {
                Log.e(LOG_TAG,
                        "RIL implementation has returned an error where it must succeed",
                        ar.exception);
            }
        } else try {
            switch (what) {
            case EVENT_POLL_STATE_REGISTRATION_CDMA: // Handle RIL_REQUEST_REGISTRATION_STATE.
                RegStateResponse r = (RegStateResponse)ar.result;
                states = r.getRecord(0);

                int registrationState = 4;     //[0] registrationState
                int radioTechnology = -1;      //[3] radioTechnology
                int baseStationId = -1;        //[4] baseStationId
                //[5] baseStationLatitude
                int baseStationLatitude = CdmaCellLocation.INVALID_LAT_LONG;
                //[6] baseStationLongitude
                int baseStationLongitude = CdmaCellLocation.INVALID_LAT_LONG;
                int cssIndicator = 0;          //[7] init with 0, because it is treated as a boolean
                int systemId = 0;              //[8] systemId
                int networkId = 0;             //[9] networkId
                int roamingIndicator = -1;     //[10] Roaming indicator
                int systemIsInPrl = 0;         //[11] Indicates if current system is in PRL
                int defaultRoamingIndicator = 0;  //[12] Is default roaming indicator from PRL
                int reasonForDenial = 0;       //[13] Denial reason if registrationState = 3

                if (states.length == 14) {
                    try {
                        if (states[0] != null) {
                            registrationState = Integer.parseInt(states[0]);
                        }
                        if (states[3] != null) {
                            radioTechnology = Integer.parseInt(states[3]);
                        }
                        if (states[4] != null) {
                            baseStationId = Integer.parseInt(states[4]);
                        }
                        if (states[5] != null) {
                            baseStationLatitude = Integer.parseInt(states[5]);
                        }
                        if (states[6] != null) {
                            baseStationLongitude = Integer.parseInt(states[6]);
                        }
                        // Some carriers only return lat-lngs of 0,0
                        if (baseStationLatitude == 0 && baseStationLongitude == 0) {
                            baseStationLatitude  = CdmaCellLocation.INVALID_LAT_LONG;
                            baseStationLongitude = CdmaCellLocation.INVALID_LAT_LONG;
                        }
                        if (states[7] != null) {
                            cssIndicator = Integer.parseInt(states[7]);
                        }
                        if (states[8] != null) {
                            systemId = Integer.parseInt(states[8]);
                        }
                        if (states[9] != null) {
                            networkId = Integer.parseInt(states[9]);
                        }
                        if (states[10] != null) {
                            roamingIndicator = Integer.parseInt(states[10]);
                        }
                        if (states[11] != null) {
                            systemIsInPrl = Integer.parseInt(states[11]);
                        }
                        if (states[12] != null) {
                            defaultRoamingIndicator = Integer.parseInt(states[12]);
                        }
                        if (states[13] != null) {
                            reasonForDenial = Integer.parseInt(states[13]);
                        }
                    } catch (NumberFormatException ex) {
                        Log.w(LOG_TAG, "error parsing RegistrationState: " + ex);
                    }
                } else {
                    throw new RuntimeException("Warning! Wrong number of parameters returned from "
                                         + "RIL_REQUEST_REGISTRATION_STATE: expected 14 got "
                                         + states.length);
                }

                mRegistrationState = registrationState;
                // When registration state is roaming and TSB58
                // roaming indicator is not in the carrier-specified
                // list of ERIs for home system, mCdmaRoaming is true.
                mCdmaRoaming =
                        regCodeIsRoaming(registrationState) && !isRoamIndForHomeSystem(states[10]);
                newSS.setState (regCodeToServiceState(registrationState));

                newSS.setRadioTechnology(radioTechnology);
                newNetworkType = radioTechnology;

                newSS.setCssIndicator(cssIndicator);
                newSS.setSystemAndNetworkId(systemId, networkId);
                mRoamingIndicator = roamingIndicator;
                mIsInPrl = (systemIsInPrl == 0) ? false : true;
                mDefaultRoamingIndicator = defaultRoamingIndicator;


                // Values are -1 if not available.
                newCellLoc.setCellLocationData(baseStationId, baseStationLatitude,
                        baseStationLongitude, systemId, networkId);

                if (reasonForDenial == 0) {
                    mRegistrationDeniedReason = ServiceStateTracker.REGISTRATION_DENIED_GEN;
                } else if (reasonForDenial == 1) {
                    mRegistrationDeniedReason = ServiceStateTracker.REGISTRATION_DENIED_AUTH;
                } else {
                    mRegistrationDeniedReason = "";
                }

                if (mRegistrationState == 3) {
                    if (DBG) log("Registration denied, " + mRegistrationDeniedReason);
                }
                break;

            case EVENT_POLL_STATE_OPERATOR_CDMA: // Handle RIL_REQUEST_OPERATOR
                String opNames[] = (String[])ar.result;

                if (opNames != null && opNames.length >= 3) {
                    if (mCdmaSubscriptionSource == Phone.CDMA_SUBSCRIPTION_NV) {
                        // In CDMA in case on NV, the ss.mOperatorAlphaLong is set later with the
                        // ERI text, so here it is ignored what is coming from the modem.
                        newSS.setOperatorName(null, opNames[1], opNames[2]);
                    } else {
                        newSS.setOperatorName(opNames[0], opNames[1], opNames[2]);
                    }
                } else {
                    Log.w(LOG_TAG, "error parsing opNames");
                }
                break;

            default:
                Log.e(LOG_TAG, "RIL response handle in wrong phone!"
                    + " Expected CDMA RIL request and get GSM RIL request.");
            break;
            }

        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "Exception while polling service state. "
                    + "Probably malformed RIL response.", ex);
        }

        pollingContext[0]--;

        if (pollingContext[0] == 0) {
            boolean namMatch = false;
            if (!isSidsAllZeros() && isHomeSid(newSS.getSystemId())) {
                namMatch = true;
            }

            // Setting SS Roaming (general)
            if (mCdmaSubscriptionSource == Phone.CDMA_SUBSCRIPTION_RUIM_SIM) {
                newSS.setRoaming(isRoamingBetweenOperators(mCdmaRoaming, newSS));
            } else {
                newSS.setRoaming(mCdmaRoaming);
            }

            // Setting SS CdmaRoamingIndicator and CdmaDefaultRoamingIndicator
            newSS.setCdmaDefaultRoamingIndicator(mDefaultRoamingIndicator);
            newSS.setCdmaRoamingIndicator(mRoamingIndicator);
            boolean isPrlLoaded = true;
            if (TextUtils.isEmpty(mPrlVersion)) {
                isPrlLoaded = false;
            }
            if (!isPrlLoaded) {
                newSS.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_FLASH);
            } else if (!isSidsAllZeros()) {
                if (!namMatch && !mIsInPrl) {
                    // Use default
                    newSS.setCdmaRoamingIndicator(mDefaultRoamingIndicator);
                } else if (namMatch && !mIsInPrl) {
                    newSS.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_FLASH);
                } else if (!namMatch && mIsInPrl) {
                    // Use the one from PRL/ERI
                    newSS.setCdmaRoamingIndicator(mRoamingIndicator);
                } else {
                    // It means namMatch && mIsInPrl
                    if ((mRoamingIndicator <= 2)) {
                        newSS.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_OFF);
                    } else {
                        // Use the one from PRL/ERI
                        newSS.setCdmaRoamingIndicator(mRoamingIndicator);
                    }
                }
            }

            int roamingIndicator = newSS.getCdmaRoamingIndicator();
            newSS.setCdmaEriIconIndex(phone.mEriManager.getCdmaEriIconIndex(roamingIndicator,
                    mDefaultRoamingIndicator));
            newSS.setCdmaEriIconMode(phone.mEriManager.getCdmaEriIconMode(roamingIndicator,
                    mDefaultRoamingIndicator));

            // NOTE: Some operator may require overriding mCdmaRoaming
            // (set by the modem), depending on the mRoamingIndicator.

            if (DBG) {
                log("Set CDMA Roaming Indicator to: " + newSS.getCdmaRoamingIndicator()
                    + ". mCdmaRoaming = " + mCdmaRoaming + ", isPrlLoaded = " + isPrlLoaded
                    + ". namMatch = " + namMatch + " , mIsInPrl = " + mIsInPrl
                    + ", mRoamingIndicator = " + mRoamingIndicator
                    + ", mDefaultRoamingIndicator= " + mDefaultRoamingIndicator);
            }
            pollStateDone();
        }

    }

    private void setSignalStrengthDefaultValues() {
        mSignalStrength = new SignalStrength();
        mSignalStrength.setGsm(false);
    }

    /**
     * A complete "service state" from our perspective is
     * composed of a handful of separate requests to the radio.
     *
     * We make all of these requests at once, but then abandon them
     * and start over again if the radio notifies us that some
     * event has changed
     */
    private void
    pollState() {
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
            // Issue all poll-related commands at once, then count
            // down the responses which are allowed to arrive
            // out-of-order.

            pollingContext[0]++;
            // RIL_REQUEST_OPERATOR is necessary for CDMA
            cm.getOperator(
                    obtainMessage(EVENT_POLL_STATE_OPERATOR_CDMA, pollingContext));

            pollingContext[0]++;
            // RIL_REQUEST_REGISTRATION_STATE is necessary for CDMA
            cm.getRegistrationState(
                    obtainMessage(EVENT_POLL_STATE_REGISTRATION_CDMA, pollingContext));

            break;
        }
    }

    private void fixTimeZone(String isoCountryCode) {
        TimeZone zone = null;
        // If the offset is (0, false) and the time zone property
        // is set, use the time zone property rather than GMT.
        String zoneName = SystemProperties.get(TIMEZONE_PROPERTY);
        if ((mZoneOffset == 0) && (mZoneDst == false) && (zoneName != null)
                && (zoneName.length() > 0)
                && (Arrays.binarySearch(GMT_COUNTRY_CODES, isoCountryCode) < 0)) {
            // For NITZ string without time zone,
            // need adjust time to reflect default time zone setting
            zone = TimeZone.getDefault();
            long tzOffset;
            tzOffset = zone.getOffset(System.currentTimeMillis());
            if (getAutoTime()) {
                setAndBroadcastNetworkSetTime(System.currentTimeMillis() - tzOffset);
            } else {
                // Adjust the saved NITZ time to account for tzOffset.
                mSavedTime = mSavedTime - tzOffset;
            }
        } else if (isoCountryCode.equals("")) {
            // Country code not found. This is likely a test network.
            // Get a TimeZone based only on the NITZ parameters (best guess).
            zone = getNitzTimeZone(mZoneOffset, mZoneDst, mZoneTime);
        } else {
            zone = TimeUtils.getTimeZone(mZoneOffset, mZoneDst, mZoneTime, isoCountryCode);
        }

        mNeedFixZone = false;

        if (zone != null) {
            if (getAutoTime()) {
                setAndBroadcastNetworkSetTimeZone(zone.getID());
            }
            saveNitzTimeZone(zone.getID());
        }
    }

    private void pollStateDone() {
        if (DBG) log("Poll ServiceState done: oldSS=[" + ss + "] newSS=[" + newSS + "]");

        boolean hasRegistered =
            ss.getState() != ServiceState.STATE_IN_SERVICE
            && newSS.getState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered =
            ss.getState() == ServiceState.STATE_IN_SERVICE
            && newSS.getState() != ServiceState.STATE_IN_SERVICE;

        boolean hasChanged = !newSS.equals(ss);

        boolean hasRoamingOn = !ss.getRoaming() && newSS.getRoaming();

        boolean hasRoamingOff = ss.getRoaming() && !newSS.getRoaming();

        boolean hasLocationChanged = !newCellLoc.equals(cellLoc);

        // Add an event log when connection state changes
        if (ss.getState() != newSS.getState()) {
            EventLog.writeEvent(EventLogTags.CDMA_SERVICE_STATE_CHANGE,
                    ss.getState(),
                    newSS.getState());
        }

        ServiceState tss;
        tss = ss;
        ss = newSS;
        newSS = tss;
        // clean slate for next time
        newSS.setStateOutOfService();

        CdmaCellLocation tcl = cellLoc;
        cellLoc = newCellLoc;
        newCellLoc = tcl;

        networkType = newNetworkType;

        newSS.setStateOutOfService(); // clean slate for next time

        if (hasRegistered) {
            networkAttachedRegistrants.notifyRegistrants();
        }

        if (hasChanged) {
            if (mCdmaSubscriptionSource == Phone.CDMA_SUBSCRIPTION_NV) {
                String eriText;
                // Now the CDMAPhone sees the new ServiceState so it can get the new ERI text
                if (ss.getState() == ServiceState.STATE_IN_SERVICE) {
                    eriText = phone.getCdmaEriText();
                } else {
                    // Note that ServiceState.STATE_OUT_OF_SERVICE is valid used for
                    // mRegistrationState 0,2,3 and 4
                    eriText = phone.getContext().getText(
                            com.android.internal.R.string.roamingTextSearching).toString();
                }
                ss.setCdmaEriText(eriText);
            }

            String operatorNumeric;

            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ALPHA,
                    ss.getOperatorAlphaLong());

            operatorNumeric = ss.getOperatorNumeric();
            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, operatorNumeric);

            if (operatorNumeric == null) {
                phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, "");
            } else {
                String isoCountryCode = "";
                try{
                    isoCountryCode = MccTable.countryCodeForMcc(Integer.parseInt(
                            operatorNumeric.substring(0,3)));
                } catch ( NumberFormatException ex){
                    Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
                } catch ( StringIndexOutOfBoundsException ex) {
                    Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
                }

                phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY,
                        isoCountryCode);
                mGotCountryCode = true;
                if (mNeedFixZone) {
                    fixTimeZone(isoCountryCode);
                }
            }

            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISROAMING,
                    ss.getRoaming() ? "true" : "false");

            updateSpnDisplay();
            phone.notifyServiceStateChanged(ss);
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

    void updateIccAvailability() {

        UiccCardApplication new3gpp2Application = mUiccManager
                .getCurrentApplication(AppFamily.APP_FAM_3GPP2);

        if (m3gpp2Application != new3gpp2Application) {
            if (m3gpp2Application != null) {
                log("Removing stale 3gpp Application.");
                m3gpp2Application.unregisterForReady(this);
                if (mRuimRecords != null) {
                    mRuimRecords.unregisterForRecordsLoaded(this);
                    mRuimRecords = null;
                }
                m3gpp2Application = null;
            }
            if (new3gpp2Application != null) {
                log("New 3gpp2 application found");
                m3gpp2Application = new3gpp2Application;
                m3gpp2Application.registerForReady(this, EVENT_RUIM_READY, null);
                mRuimRecords = (RuimRecords) m3gpp2Application.getApplicationRecords();
                mRuimRecords.registerForRecordsLoaded(this, EVENT_RUIM_RECORDS_LOADED, null);
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
        if (DBG) log("getNitzTimeZone returning " + (guess == null ? guess : guess.getID()));
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

    /**
     * TODO: This code is exactly the same as in GsmServiceStateTracker
     * and has a TODO to not poll signal strength if screen is off.
     * This code should probably be hoisted to the base class so
     * the fix, when added, works for both.
     */
    private void
    queueNextSignalStrengthPoll() {
        if (dontPollSignalStrength) {
            // The radio is telling us about signal strength changes
            // we don't have to ask it
            return;
        }

        Message msg;

        msg = obtainMessage();
        msg.what = EVENT_POLL_SIGNAL_STRENGTH;

        // TODO Don't poll signal strength if screen is off
        sendMessageDelayed(msg, POLL_PERIOD_MILLIS);
    }

    /** code is registration state 0-5 from TS 27.007 7.2 */
    private int regCodeToServiceState(int code) {
        phone.setPowerSaveStatus(false);
        switch (code) {
        case 0: // Not searching and not registered
            phone.setPowerSaveStatus(true);
            return ServiceState.STATE_OUT_OF_SERVICE;
        case 1:
            return ServiceState.STATE_IN_SERVICE;
        case 2: // 2 is "searching", fall through
        case 3: // 3 is "registration denied", fall through
        case 4: // 4 is "unknown", not valid in current baseband
            return ServiceState.STATE_OUT_OF_SERVICE;
        case 5:// 5 is "Registered, roaming"
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
    private boolean
    regCodeIsRoaming (int code) {
        // 5 is  "in service -- roam"
        return 5 == code;
    }

    /**
     * Determine whether a roaming indicator is in the carrier-specified list of ERIs for
     * home system
     *
     * @param roamInd roaming indicator in String
     * @return true if the roamInd is in the carrier-specified list of ERIs for home network
     */
    private boolean isRoamIndForHomeSystem(String roamInd) {
        // retrieve the carrier-specified list of ERIs for home system
        String homeRoamIndicators = SystemProperties.get("ro.cdma.homesystem");

        if (!TextUtils.isEmpty(homeRoamIndicators)) {
            // searches through the comma-separated list for a match,
            // return true if one is found.
            for (String homeRoamInd : homeRoamIndicators.split(",")) {
                if (homeRoamInd.equals(roamInd)) {
                    return true;
                }
            }
            // no matches found against the list!
            return false;
        }

        // no system property found for the roaming indicators for home system
        return false;
    }

    /**
     * Set roaming state when cdmaRoaming is true and ons is different from spn
     * @param cdmaRoaming TS 27.007 7.2 CREG registered roaming
     * @param s ServiceState hold current ons
     * @return true for roaming state set
     */
    private
    boolean isRoamingBetweenOperators(boolean cdmaRoaming, ServiceState s) {
        String spn = SystemProperties.get(TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA, "empty");

        // NOTE: in case of RUIM we should completely ignore the ERI data file and
        // mOperatorAlphaLong is set from RIL_REQUEST_OPERATOR response 0 (alpha ONS)
        String onsl = s.getOperatorAlphaLong();
        String onss = s.getOperatorAlphaShort();

        boolean equalsOnsl = onsl != null && spn.equals(onsl);
        boolean equalsOnss = onss != null && spn.equals(onss);

        return cdmaRoaming && !(equalsOnsl || equalsOnss);
    }


    /**
     * nitzReceiveTime is time_t that the NITZ time was posted
     */

    private
    void setTimeFromNITZString (String nitz, long nitzReceiveTime)
    {
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

                /**
                 * Correct the NITZ time by how long its taken to get here.
                 */
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

                if (getAutoTime()) {
                    /**
                     * Update system time automatically
                     */
                    long gained = c.getTimeInMillis() - System.currentTimeMillis();
                    long timeSinceLastUpdate = SystemClock.elapsedRealtime() - mSavedAtTime;
                    int nitzUpdateSpacing = Settings.Secure.getInt(cr,
                            Settings.Secure.NITZ_UPDATE_SPACING, mNitzUpdateSpacing);
                    int nitzUpdateDiff = Settings.Secure.getInt(cr,
                            Settings.Secure.NITZ_UPDATE_DIFF, mNitzUpdateDiff);

                    if ((mSavedAtTime == 0) || (timeSinceLastUpdate > nitzUpdateSpacing)
                            || (Math.abs(gained) > nitzUpdateDiff)) {
                        Log.i(LOG_TAG, "NITZ: Auto updating time of day to " + c.getTime()
                                + " NITZ receive delay=" + millisSinceNitzReceived
                                + "ms gained=" + gained + "ms from " + nitz);

                        setAndBroadcastNetworkSetTime(c.getTimeInMillis());
                    } else {
                        Log.i(LOG_TAG, "NITZ: ignore, a previous update was "
                                + timeSinceLastUpdate + "ms ago and gained=" + gained + "ms");
                        return;
                    }
                }

                /**
                 * Update properties and save the time we did the update
                 */
                Log.i(LOG_TAG, "NITZ: update nitz time property");
                SystemProperties.set("gsm.nitz.time", String.valueOf(c.getTimeInMillis()));
                mSavedTime = c.getTimeInMillis();
                mSavedAtTime = SystemClock.elapsedRealtime();
            } finally {
                long end = SystemClock.elapsedRealtime();
                Log.i(LOG_TAG, "NITZ: end=" + end + " dur=" + (end - start));
                mWakeLock.release();
            }
        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "NITZ: Parsing NITZ time " + nitz, ex);
        }
    }

    private boolean getAutoTime() {
        // Always returns true for CDMA mode
        return true;
    }

    private void saveNitzTimeZone(String zoneId) {
        mSavedTimeZone = zoneId;
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
        if (Settings.System.getInt(cr, Settings.System.AUTO_TIME, 0) == 0) {
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

    private boolean isSidsAllZeros() {
        if (mHomeSystemId != null) {
            for (int i=0; i < mHomeSystemId.length; i++) {
                if (mHomeSystemId[i] != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Check whether a specified system ID that matches one of the home system IDs.
     */
    private boolean isHomeSid(int sid) {
        if (mHomeSystemId != null) {
            for (int i=0; i < mHomeSystemId.length; i++) {
                if (sid == mHomeSystemId[i]) {
                    return true;
                }
            }
        }
        return false;
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[CdmaServiceStateTracker] " + s);
    }

    public String getMdnNumber() {
        return mMdn;
    }

    public String getCdmaMin() {
         return mMin;
    }

    /** Returns null if NV is not yet ready */
    public String getPrlVersion() {
        return mPrlVersion;
    }

    /**
     * Returns IMSI as MCC + MNC + MIN
     */
    String getImsi() {
        // TODO: When RUIM is enabled, IMSI will come from RUIM not build-time props.
        String operatorNumeric = SystemProperties.get(
                TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, "");

        if (!TextUtils.isEmpty(operatorNumeric) && getCdmaMin() != null) {
            return (operatorNumeric + getCdmaMin());
        } else {
            return null;
        }
    }

    /**
     * Check if subscription data has been assigned to mMin
     *
     * return true if MIN info is ready; false otherwise.
     */
    public boolean isMinInfoReady() {
        return mIsMinInfoReady;
    }

    private void hangupBeforePowerOff() {
        // hang up all active voice calls
        phone.mCT.ringingCall.hangupIfAlive();
        phone.mCT.backgroundCall.hangupIfAlive();
        phone.mCT.foregroundCall.hangupIfAlive();
        cm.setRadioPower(false, null);
    }
}
