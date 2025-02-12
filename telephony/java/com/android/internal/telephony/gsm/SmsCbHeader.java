/*
 * Copyright (C) 2010 The Android Open Source Project
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
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

import android.os.Parcel;
import android.os.Parcelable;

public class SmsCbHeader implements Parcelable {
    /**
     * Length of SMS-CB header
     */
    public static final int PDU_HEADER_LENGTH = 6;

    /**
     * GSM pdu format, as defined in 3gpp TS 23.041, section 9.4.1
     */
    public static final int FORMAT_GSM = 1;

    /**
     * UMTS pdu format, as defined in 3gpp TS 23.041, section 9.4.2
     */
    public static final int FORMAT_UMTS = 2;

    /**
     * GSM pdu format, as defined in 3gpp TS 23.041, section 9.4.1.3
     */
    public static final int FORMAT_ETWS_PRIMARY = 3;

    /**
     * Message type value as defined in 3gpp TS 25.324, section 11.1.
     */
    private static final int MESSAGE_TYPE_CBS_MESSAGE = 1;

    /**
     * Length of GSM pdus
     */
    private static final int PDU_LENGTH_GSM = 88;

    /**
     * Maximum length of ETWS primary message GSM pdus
     */
    private static final int PDU_LENGTH_ETWS = 56;

    public final int geographicalScope;

    public final int messageCode;

    public final int updateNumber;

    public final int messageIdentifier;

    public final int dataCodingScheme;

    public final int pageIndex;

    public final int nrOfPages;

    public final int format;

    public final int etwsEmergencyUserAlert;

    public final int etwsPopup;

    public SmsCbHeader(byte[] pdu) throws IllegalArgumentException {
        if (pdu == null || pdu.length < PDU_HEADER_LENGTH) {
            throw new IllegalArgumentException("Illegal PDU");
        }

        if (pdu.length <= PDU_LENGTH_GSM) {
            // GSM pdus are no more than 88 bytes
            if (pdu.length <= PDU_LENGTH_ETWS) {
                format = FORMAT_ETWS_PRIMARY;
                geographicalScope = -1; //not applicable
                messageCode = -1;
                updateNumber = -1;
                messageIdentifier = ((pdu[2] & 0xff) << 8) | (pdu[3] & 0xff);
                dataCodingScheme = -1;
                pageIndex = -1;
                nrOfPages = -1;
                etwsEmergencyUserAlert = (pdu[4] & 0x1);
                etwsPopup = (pdu[5] & 0x8) >> 7;
            } else {
                format = FORMAT_GSM;
                geographicalScope = (pdu[0] & 0xc0) >> 6;
                messageCode = ((pdu[0] & 0x3f) << 4) | ((pdu[1] & 0xf0) >> 4);
                updateNumber = pdu[1] & 0x0f;
                messageIdentifier = ((pdu[2] & 0xff) << 8) | (pdu[3] & 0xff);
                dataCodingScheme = pdu[4] & 0xff;

                // Check for invalid page parameter
                int pageIndex = (pdu[5] & 0xf0) >> 4;
                int nrOfPages = pdu[5] & 0x0f;

                if (pageIndex == 0 || nrOfPages == 0 || pageIndex > nrOfPages) {
                    pageIndex = 1;
                    nrOfPages = 1;
                }

                this.pageIndex = pageIndex;
                this.nrOfPages = nrOfPages;
                etwsEmergencyUserAlert = -1;
                etwsPopup = -1;
            }
        } else {
            // UMTS pdus are always at least 90 bytes since the payload includes
            // a number-of-pages octet and also one length octet per page
            format = FORMAT_UMTS;

            int messageType = pdu[0];

            if (messageType != MESSAGE_TYPE_CBS_MESSAGE) {
                throw new IllegalArgumentException("Unsupported message type " + messageType);
            }

            messageIdentifier = ((pdu[1] & 0xff) << 8) | pdu[2] & 0xff;
            geographicalScope = (pdu[3] & 0xc0) >> 6;
            messageCode = ((pdu[3] & 0x3f) << 4) | ((pdu[4] & 0xf0) >> 4);
            updateNumber = pdu[4] & 0x0f;
            dataCodingScheme = pdu[5] & 0xff;

            // We will always consider a UMTS message as having one single page
            // since there's only one instance of the header, even though the
            // actual payload may contain several pages.
            pageIndex = 1;
            nrOfPages = 1;
            etwsEmergencyUserAlert = -1;
            etwsPopup = -1;
        }
    }

    // Copy constructor for CB Header
    public SmsCbHeader(SmsCbHeader other) {
        this.dataCodingScheme = other.dataCodingScheme;
        this.geographicalScope = other.geographicalScope;
        this.messageCode = other.messageCode;
        this.messageIdentifier = other.messageIdentifier;
        this.nrOfPages = other.nrOfPages;
        this.pageIndex = other.pageIndex;
        this.updateNumber = other.updateNumber;
        this.format = other.format;
        this.etwsEmergencyUserAlert = other.etwsEmergencyUserAlert;
        this.etwsPopup = other.etwsPopup;
    }

    @Override
    public String toString() {
        return ("[Id = " + messageIdentifier + " Code = " + messageCode
                + " Coding Scheme = " + dataCodingScheme + " Gs = "
                + geographicalScope + " Update Number = " + updateNumber + "]");
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(geographicalScope);
        dest.writeInt(messageCode);
        dest.writeInt(updateNumber);
        dest.writeInt(messageIdentifier);
        dest.writeInt(dataCodingScheme);
        dest.writeInt(pageIndex);
        dest.writeInt(nrOfPages);
        dest.writeInt(format);
        dest.writeInt(etwsEmergencyUserAlert);
        dest.writeInt(etwsPopup);
    }

    public static final Parcelable.Creator<SmsCbHeader> CREATOR = new Parcelable.Creator<SmsCbHeader>() {
        public SmsCbHeader createFromParcel(Parcel in) {
            return new SmsCbHeader(in);
        }

        public SmsCbHeader[] newArray(int size) {
            return new SmsCbHeader[size];
        }
    };

    private SmsCbHeader(Parcel in) {
        geographicalScope = in.readInt();
        messageCode = in.readInt();
        updateNumber = in.readInt();
        messageIdentifier = in.readInt();
        dataCodingScheme = in.readInt();
        pageIndex = in.readInt();
        nrOfPages = in.readInt();
        format = in.readInt();
        etwsEmergencyUserAlert = in.readInt();
        etwsPopup = in.readInt();
    }
}
