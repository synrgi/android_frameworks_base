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

package com.android.internal.telephony;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.util.Log;

/**
 * {@hide}
 */
public abstract class ServiceStateTracker extends Handler {

    static final String LOG_TAG = "ServiceStateTracker";

    /**
     *  Access technology currently in use.
     */
    protected static final int DATA_ACCESS_UNKNOWN = 0;
    protected static final int DATA_ACCESS_GPRS = 1;
    protected static final int DATA_ACCESS_EDGE = 2;
    protected static final int DATA_ACCESS_UMTS = 3;
    protected static final int DATA_ACCESS_CDMA_IS95A = 4;
    protected static final int DATA_ACCESS_CDMA_IS95B = 5;
    protected static final int DATA_ACCESS_CDMA_1xRTT = 6;
    protected static final int DATA_ACCESS_CDMA_EvDo_0 = 7;
    protected static final int DATA_ACCESS_CDMA_EvDo_A = 8;
    protected static final int DATA_ACCESS_HSDPA = 9;
    protected static final int DATA_ACCESS_HSUPA = 10;
    protected static final int DATA_ACCESS_HSPA = 11;
    protected static final int DATA_ACCESS_CDMA_EvDo_B = 12;
    protected static final int DATA_ACCESS_EHRPD = 13;
    protected static final int DATA_ACCESS_LTE = 14;

    protected CommandsInterface cm;

    public ServiceState ss;
    protected ServiceState newSS;

    public SignalStrength mSignalStrength;

    /**
     * A unique identifier to track requests associated with a poll
     * and ignore stale responses.  The value is a count-down of
     * expected responses in this pollingContext.
     */
    protected int[] pollingContext;

    /**
     * By default, strength polling is enabled.  However, if we're
     * getting unsolicited signal strength updates from the radio, set
     * value to true and don't bother polling any more.
     */
    protected boolean dontPollSignalStrength = false;

    protected RegistrantList networkAttachedRegistrants = new RegistrantList();
    protected RegistrantList roamingOnRegistrants = new RegistrantList();
    protected RegistrantList roamingOffRegistrants = new RegistrantList();

    protected  static final boolean DBG = true;

    /** Signal strength poll rate. */
    protected static final int POLL_PERIOD_MILLIS = 20 * 1000;

    /** Waiting period before recheck gprs and voice registration. */
    public static final int DEFAULT_GPRS_CHECK_PERIOD_MILLIS = 60 * 1000;

    public static final int DATA_STATE_POLL_SLEEP_MS = 100;

    /** GSM events */
    protected static final int EVENT_RADIO_STATE_CHANGED               = 1;
    protected static final int EVENT_NETWORK_STATE_CHANGED             = 2;
    protected static final int EVENT_GET_SIGNAL_STRENGTH               = 3;
    protected static final int EVENT_POLL_STATE_REGISTRATION           = 4;
    protected static final int EVENT_POLL_STATE_OPERATOR               = 6;
    protected static final int EVENT_POLL_SIGNAL_STRENGTH              = 10;
    protected static final int EVENT_NITZ_TIME                         = 11;
    protected static final int EVENT_SIGNAL_STRENGTH_UPDATE            = 12;
    protected static final int EVENT_RADIO_AVAILABLE                   = 13;
    protected static final int EVENT_POLL_STATE_NETWORK_SELECTION_MODE = 14;
    protected static final int EVENT_GET_LOC_DONE                      = 15;
    protected static final int EVENT_SIM_RECORDS_LOADED                = 16;
    protected static final int EVENT_SIM_READY                         = 17;
    protected static final int EVENT_LOCATION_UPDATES_ENABLED          = 18;
    protected static final int EVENT_GET_PREFERRED_NETWORK_TYPE        = 19;
    protected static final int EVENT_SET_PREFERRED_NETWORK_TYPE        = 20;
    protected static final int EVENT_RESET_PREFERRED_NETWORK_TYPE      = 21;
    protected static final int EVENT_RESTRICTED_STATE_CHANGED          = 23;

    /** CDMA events */
    protected static final int EVENT_POLL_STATE_REGISTRATION_CDMA      = 24;
    protected static final int EVENT_POLL_STATE_OPERATOR_CDMA          = 25;
    protected static final int EVENT_RUIM_READY                        = 26;
    protected static final int EVENT_RUIM_RECORDS_LOADED               = 27;
    protected static final int EVENT_POLL_SIGNAL_STRENGTH_CDMA         = 28;
    protected static final int EVENT_GET_SIGNAL_STRENGTH_CDMA          = 29;
    protected static final int EVENT_NETWORK_STATE_CHANGED_CDMA        = 30;
    protected static final int EVENT_GET_LOC_DONE_CDMA                 = 31;
    protected static final int EVENT_SIGNAL_STRENGTH_UPDATE_CDMA       = 32;
    protected static final int EVENT_NV_LOADED                         = 33;
    protected static final int EVENT_POLL_STATE_CDMA_SUBSCRIPTION      = 34;
    protected static final int EVENT_NV_READY                          = 35;
    protected static final int EVENT_ERI_FILE_LOADED                   = 36;
    protected static final int EVENT_OTA_PROVISION_STATUS_CHANGE       = 37;
    protected static final int EVENT_SET_RADIO_POWER_OFF               = 38;
    protected static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED  = 40;
    protected static final int EVENT_CDMA_PRL_VERSION_CHANGED          = 41;
    protected static final int EVENT_GET_CDMA_PRL_VERSION              = 42;

    protected static final int EVENT_RADIO_ON                          = 43;
    protected static final int EVENT_ICC_CHANGED                       = 44;
    protected static final int EVENT_ICC_RECORD_EVENTS                 = 45;

    protected static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

    /**
     * List of ISO codes for countries that can have an offset of
     * GMT+0 when not in daylight savings time.  This ignores some
     * small places such as the Canary Islands (Spain) and
     * Danmarkshavn (Denmark).  The list must be sorted by code.
    */
    protected static final String[] GMT_COUNTRY_CODES = {
        "bf", // Burkina Faso
        "ci", // Cote d'Ivoire
        "eh", // Western Sahara
        "fo", // Faroe Islands, Denmark
        "gh", // Ghana
        "gm", // Gambia
        "gn", // Guinea
        "gw", // Guinea Bissau
        "ie", // Ireland
        "lr", // Liberia
        "is", // Iceland
        "ma", // Morocco
        "ml", // Mali
        "mr", // Mauritania
        "pt", // Portugal
        "sl", // Sierra Leone
        "sn", // Senegal
        "st", // Sao Tome and Principe
        "tg", // Togo
        "uk", // U.K
    };

    /** Reason for registration denial. */
    protected static final String REGISTRATION_DENIED_GEN  = "General";
    protected static final String REGISTRATION_DENIED_AUTH = "Authentication Failure";

    public ServiceStateTracker() {

    }

    /**
     * Registration point for combined roaming on
     * combined roaming is true when roaming is true and ONS differs SPN
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public  void registerForRoamingOn(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        roamingOnRegistrants.add(r);

        if (ss.getRoaming()) {
            r.notifyRegistrant();
        }
    }

    public  void unregisterForRoamingOn(Handler h) {
        roamingOnRegistrants.remove(h);
    }

    /**
     * Registration point for combined roaming off
     * combined roaming is true when roaming is true and ONS differs SPN
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public  void registerForRoamingOff(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        roamingOffRegistrants.add(r);

        if (!ss.getRoaming()) {
            r.notifyRegistrant();
        }
    }

    public  void unregisterForRoamingOff(Handler h) {
        roamingOffRegistrants.remove(h);
    }

    /**
     * Re-register network by toggling preferred network type.
     * This is a work-around to deregister and register network since there is
     * no ril api to set COPS=2 (deregister) only.
     *
     * @param onComplete is dispatched when this is complete.  it will be
     * an AsyncResult, and onComplete.obj.exception will be non-null
     * on failure.
     */
    public void reRegisterNetwork(Message onComplete) {
        cm.getPreferredNetworkType(
                obtainMessage(EVENT_GET_PREFERRED_NETWORK_TYPE, onComplete));
    }

    /**
     * These two flags manage the behavior of the cell lock -- the
     * lock should be held if either flag is true.  The intention is
     * to allow temporary acquisition of the lock to get a single
     * update.  Such a lock grab and release can thus be made to not
     * interfere with more permanent lock holds -- in other words, the
     * lock will only be released if both flags are false, and so
     * releases by temporary users will only affect the lock state if
     * there is no continuous user.
     */
    private boolean mWantContinuousLocationUpdates;
    private boolean mWantSingleLocationUpdate;

    public void enableSingleLocationUpdate() {
        if (mWantSingleLocationUpdate || mWantContinuousLocationUpdates) return;
        mWantSingleLocationUpdate = true;
        cm.setLocationUpdates(true, obtainMessage(EVENT_LOCATION_UPDATES_ENABLED));
    }

    public void enableLocationUpdates() {
        if (mWantSingleLocationUpdate || mWantContinuousLocationUpdates) return;
        mWantContinuousLocationUpdates = true;
        cm.setLocationUpdates(true, obtainMessage(EVENT_LOCATION_UPDATES_ENABLED));
    }

    protected void disableSingleLocationUpdate() {
        mWantSingleLocationUpdate = false;
        if (!mWantSingleLocationUpdate && !mWantContinuousLocationUpdates) {
            cm.setLocationUpdates(false, null);
        }
    }

    public void disableLocationUpdates() {
        mWantContinuousLocationUpdates = false;
        if (!mWantSingleLocationUpdate && !mWantContinuousLocationUpdates) {
            cm.setLocationUpdates(false, null);
        }
    }

    public abstract void handleMessage(Message msg);

    protected abstract void handlePollStateResult(int what, AsyncResult ar);
    protected abstract void updateSpnDisplay();

    /** Cancel a pending (if any) pollState() operation */
    protected void cancelPollState() {
        // This will effectively cancel the rest of the poll requests.
        pollingContext = new int[1];
    }

    /**
     * send signal-strength-changed notification if changed Called both for
     * solicited and unsolicited signal strength updates
     */
    protected void onSignalStrengthResult(AsyncResult ar, PhoneBase phone, boolean isGsm) {
        SignalStrength oldSignalStrength = mSignalStrength;
        int INVALID = 0x7FFFFFFF;
        int gsmRssi = 99, gsmBer = -1, cdmaDbm = -1, cdmaEcio = -1, evdoRssi = -1, evdoEcio = -1, evdoSnr = -1,
             lteRssi = 99, lteRsrp = INVALID, lteRsrq = INVALID, lteSnr=INVALID, lteCqi=INVALID ;

        // This signal is used for both voice and data radio signal so parse
        // all fields

        if (ar.exception == null) {
            int[] ints = (int[]) ar.result;
            // check for sizeof RIL_SignalStrength
            if (ints.length == 12) {
                gsmRssi = (ints[0] >= 0) ? ints[0] : 99;
                gsmBer = ints[1];
                cdmaDbm = (ints[2] > 0) ? -ints[2] : -120;
                cdmaEcio = (ints[3] > 0) ? -ints[3] : -160;
                evdoRssi = (ints[4] > 0) ? -ints[4] : -120;
                evdoEcio = (ints[5] > 0) ? -ints[5] : -1;
                evdoSnr = ((ints[6] > 0) && (ints[6] <= 8)) ? ints[6] : -1;
                lteRssi = (ints[7] >= 0) ? ints[7] : 99;
                lteRsrp = ((ints[8] >= 44) && (ints[8] <= 140)) ? -ints[8] : INVALID;
                lteRsrq = ints[9]; //not supported
                lteSnr = ((ints[10] >= -200) && (ints[10] <= 300)) ? ints[10] : INVALID;
                lteCqi = ints[11]; //not supported
            }
        }

        mSignalStrength = new SignalStrength(gsmRssi, gsmBer, cdmaDbm, cdmaEcio, evdoRssi,
                evdoEcio, evdoSnr, lteRssi, lteRsrp, lteRsrq, lteSnr, lteCqi, isGsm);

        if (!mSignalStrength.equals(oldSignalStrength)) {
            try {
                // This takes care of delayed EVENT_POLL_SIGNAL_STRENGTH
                // (scheduled after POLL_PERIOD_MILLIS) during Radio Technology
                // Change)
                phone.notifySignalStrength();
            } catch (NullPointerException ex) {
                Log.d(LOG_TAG, "onSignalStrengthResult() Phone already destroyed: " + ex
                        + "SignalStrength not notified");
            }
        }
    }

}
