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

/**
 * Contains a list of string constants used to get or set telephone properties
 * in the system. You can use {@link android.os.SystemProperties os.SystemProperties}
 * to get and set these values.
 * @hide
 */
public interface TelephonyProperties
{
    //****** Baseband and Radio Interface version

    //TODO T: property strings do not have to be gsm specific
    //        change gsm.*operator.*" properties to "operator.*" properties

    /**
     * Baseband version
     * Availability: property is available any time radio is on
     */
    static final String PROPERTY_BASEBAND_VERSION = "gsm.version.baseband";

    /** Radio Interface Layer (RIL) library implementation. */
    static final String PROPERTY_RIL_IMPL = "gsm.version.ril-impl";

    //****** Current Network

    /** Alpha name of current registered operator.<p>
     *  Availability: when registered to a network. Result may be unreliable on
     *  CDMA networks.
     */
    static final String PROPERTY_OPERATOR_ALPHA = "gsm.operator.alpha";
    //TODO: most of these proprieties are generic, substitute gsm. with phone. bug 1856959

    /** Numeric name (MCC+MNC) of current registered operator.<p>
     *  Availability: when registered to a network. Result may be unreliable on
     *  CDMA networks.
     */
    static final String PROPERTY_OPERATOR_NUMERIC = "gsm.operator.numeric";

    /** 'true' if the device is on a manually selected network
     *
     *  Availability: when registered to a network
     */
    static final String PROPERTY_OPERATOR_ISMANUAL = "operator.ismanual";

    /** 'true' if the device is considered roaming on this network for GSM
     *  purposes.
     *  Availability: when registered to a network
     */
    static final String PROPERTY_OPERATOR_ISROAMING = "gsm.operator.isroaming";

    /** The ISO country code equivalent of the current registered operator's
     *  MCC (Mobile Country Code)<p>
     *  Availability: when registered to a network. Result may be unreliable on
     *  CDMA networks.
     */
    static final String PROPERTY_OPERATOR_ISO_COUNTRY = "gsm.operator.iso-country";

    static final String CURRENT_ACTIVE_PHONE = "gsm.current.phone-type";

    //****** SIM Card
    /**
     * One of <code>"UNKNOWN"</code> <code>"ABSENT"</code> <code>"PIN_REQUIRED"</code>
     * <code>"PUK_REQUIRED"</code> <code>"NETWORK_LOCKED"</code> or <code>"READY"</code>
     */
    static String PROPERTY_SIM_STATE = "gsm.sim.state";

    /** The MCC+MNC (mobile country code+mobile network code) of the
     *  provider of the SIM. 5 or 6 decimal digits.
     *  Availablity: SIM state must be "READY"
     */
    static String PROPERTY_ICC_OPERATOR_NUMERIC = "gsm.sim.operator.numeric";

    /** PROPERTY_ICC_OPERATOR_ALPHA is also known as the SPN, or Service Provider Name.
     *  Availablity: SIM state must be "READY"
     */
    static String PROPERTY_ICC_OPERATOR_ALPHA = "gsm.sim.operator.alpha";

    /** ISO country code equivalent for the SIM provider's country code*/
    static String PROPERTY_ICC_OPERATOR_ISO_COUNTRY = "gsm.sim.operator.iso-country";

    /**
     * Indicates the available radio technology.  Values include: <code>"unknown"</code>,
     * <code>"GPRS"</code>, <code>"EDGE"</code> and <code>"UMTS"</code>.
     */
    static String PROPERTY_DATA_NETWORK_TYPE = "gsm.network.type";

    /** Indicate if phone is in emergency callback mode */
    static final String PROPERTY_INECM_MODE = "ril.cdma.inecmmode";

    /** Indicate the timer value for exiting emergency callback mode */
    static final String PROPERTY_ECM_EXIT_TIMER = "ro.cdma.ecmexittimer";

    /** The international dialing prefix conversion string */
    static final String PROPERTY_IDP_STRING = "ro.cdma.idpstring";

    /**
     * Defines the schema for the carrier specified OTASP number
     */
    static final String PROPERTY_OTASP_NUM_SCHEMA = "ro.cdma.otaspnumschema";

    /**
     * Disable all calls including Emergency call when it set to true.
     */
    static final String PROPERTY_DISABLE_CALL = "ro.telephony.disable-call";

    /**
     * Set to true for vendor RIL's that send multiple UNSOL_CALL_RING notifications.
     */
    static final String PROPERTY_RIL_SENDS_MULTIPLE_CALL_RING =
        "ro.telephony.call_ring.multiple";

    /**
     * The number of milli-seconds between CALL_RING notifications.
     */
    static final String PROPERTY_CALL_RING_DELAY = "ro.telephony.call_ring.delay";

    /**
     * Track CDMA SMS message id numbers to ensure they increment
     * monotonically, regardless of reboots.
     */
    static final String PROPERTY_CDMA_MSG_ID = "persist.radio.cdma.msgid";

    /**
     * Property to override DEFAULT_WAKE_LOCK_TIMEOUT
     */
    static final String PROPERTY_WAKE_LOCK_TIMEOUT = "ro.ril.wake_lock_timeout";

    /**
     * Set to true to indicate that the modem needs to be reset
     * when there is a radio technology change.
     */
    static final String PROPERTY_RESET_ON_RADIO_TECH_CHANGE = "persist.radio.reset_on_switch";

    static final String PROPERTY_OMH_ENABLED = "persist.omh.enabled";

    /**
     * Priorities associated with different data services (apn types). These
     * priorities will be used to determine which data profiles (apns) need to
     * be used when network is not known to support multiple PDN.
     * Priorities.
     */
    static final String PROPERTY_DATA_SERVICE_PRIORITIES = "persist.telephony.ds.priorities";
}
