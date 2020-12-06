/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.shelly.internal.handler;

import static org.openhab.binding.shelly.internal.ShellyBindingConstants.*;
import static org.openhab.binding.shelly.internal.api.ShellyApiJsonDTO.*;
import static org.openhab.binding.shelly.internal.util.ShellyUtils.*;

import java.io.IOException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.shelly.internal.api.ShellyApiException;
import org.openhab.binding.shelly.internal.api.ShellyApiJsonDTO.ShellySettingsEMeter;
import org.openhab.binding.shelly.internal.api.ShellyApiJsonDTO.ShellySettingsMeter;
import org.openhab.binding.shelly.internal.api.ShellyApiJsonDTO.ShellySettingsStatus;
import org.openhab.binding.shelly.internal.api.ShellyApiJsonDTO.ShellyStatusSensor;
import org.openhab.binding.shelly.internal.api.ShellyDeviceProfile;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.SmartHomeUnits;

/***
 * The{@link ShellyComponents} implements updates for supplemental components
 * Meter will be used by Relay + Light; Sensor is part of H&T, Flood, Door Window, Sense
 *
 * @author Markus Michels - Initial contribution
 */
@NonNullByDefault
public class ShellyComponents {

    /**
     * Update device status
     *
     * @param th Thing Handler instance
     * @param profile ShellyDeviceProfile
     */
    public static boolean updateDeviceStatus(ShellyBaseHandler thingHandler, ShellySettingsStatus status) {
        if (!thingHandler.areChannelsCreated()) {
            thingHandler.updateChannelDefinitions(ShellyChannelDefinitionsDTO
                    .createDeviceChannels(thingHandler.getThing(), thingHandler.getProfile(), status));
        }

        Integer rssi = getInteger(status.wifiSta.rssi);
        thingHandler.updateChannel(CHANNEL_GROUP_DEV_STATUS, CHANNEL_DEVST_UPTIME,
                toQuantityType(new Double(getLong(status.uptime)), DIGITS_NONE, SmartHomeUnits.SECOND));
        thingHandler.updateChannel(CHANNEL_GROUP_DEV_STATUS, CHANNEL_DEVST_RSSI, mapSignalStrength(rssi));
        if (status.tmp != null) {
            thingHandler.updateChannel(CHANNEL_GROUP_DEV_STATUS, CHANNEL_DEVST_ITEMP,
                    toQuantityType(getDouble(status.tmp.tC), DIGITS_NONE, SIUnits.CELSIUS));
        } else if (status.temperature != null) {
            thingHandler.updateChannel(CHANNEL_GROUP_DEV_STATUS, CHANNEL_DEVST_ITEMP,
                    toQuantityType(getDouble(status.temperature), DIGITS_NONE, SIUnits.CELSIUS));
        }
        thingHandler.updateChannel(CHANNEL_GROUP_DEV_STATUS, CHANNEL_DEVST_UPDATE, getOnOff(status.hasUpdate));

        return false; // device status never triggers update
    }

    /**
     * Update Meter channel
     *
     * @param th Thing Handler instance
     * @param profile ShellyDeviceProfile
     * @param status Last ShellySettingsStatus
     */
    public static boolean updateMeters(ShellyBaseHandler thingHandler, ShellySettingsStatus status) {
        ShellyDeviceProfile profile = thingHandler.getProfile();

        double accumulatedWatts = 0.0;
        double accumulatedTotal = 0.0;
        double accumulatedReturned = 0.0;

        boolean updated = false;
        // Devices without power meters get no updates
        // We need to differ
        // Roler+RGBW2 have multiple meters -> aggregate consumption to the functional device
        // Meter and EMeter have a different set of channels
        if ((profile.numMeters > 0) && ((status.meters != null) || (status.emeters != null))) {
            if (!profile.isRoller && !profile.isRGBW2) {
                thingHandler.logger.trace("{}: Updating {} {}meter(s)", thingHandler.thingName, profile.numMeters,
                        !profile.isEMeter ? "standard " : "e-");

                // In Relay mode we map eacher meter to the matching channel group
                int m = 0;
                if (!profile.isEMeter) {
                    for (ShellySettingsMeter meter : status.meters) {
                        Integer meterIndex = m + 1;
                        if (getBool(meter.isValid) || profile.isLight) { // RGBW2-white doesn't report valid flag
                                                                         // correctly in white mode
                            String groupName = "";
                            if (profile.numMeters > 1) {
                                groupName = CHANNEL_GROUP_METER + meterIndex.toString();
                            } else {
                                groupName = CHANNEL_GROUP_METER;
                            }

                            if (!thingHandler.areChannelsCreated()) {
                                // skip for Shelly Bulb: JSON has a meter, but values don't get updated
                                if (!profile.isBulb) {
                                    thingHandler.updateChannelDefinitions(ShellyChannelDefinitionsDTO
                                            .createMeterChannels(thingHandler.getThing(), meter, groupName));
                                }
                            }

                            updated |= thingHandler.updateChannel(groupName, CHANNEL_METER_CURRENTWATTS,
                                    toQuantityType(getDouble(meter.power), DIGITS_WATT, SmartHomeUnits.WATT));
                            accumulatedWatts += getDouble(meter.power);

                            // convert Watt/Min to kw/h
                            if (meter.total != null) {
                                double kwh = getDouble(meter.total) / 60 / 1000;
                                updated |= thingHandler.updateChannel(groupName, CHANNEL_METER_TOTALKWH,
                                        toQuantityType(kwh, DIGITS_KWH, SmartHomeUnits.KILOWATT_HOUR));
                                accumulatedTotal += kwh;
                            }
                            if (meter.counters != null) {
                                updated |= thingHandler.updateChannel(groupName, CHANNEL_METER_LASTMIN1,
                                        toQuantityType(getDouble(meter.counters[0]), DIGITS_WATT, SmartHomeUnits.WATT));
                            }
                            thingHandler.updateChannel(groupName, CHANNEL_LAST_UPDATE,
                                    getTimestamp(getString(profile.settings.timezone), getLong(meter.timestamp)));
                        }
                        m++;
                    }
                } else {
                    for (ShellySettingsEMeter emeter : status.emeters) {
                        Integer meterIndex = m + 1;
                        if (getBool(emeter.isValid)) {
                            String groupName = profile.numMeters > 1 ? CHANNEL_GROUP_METER + meterIndex.toString()
                                    : CHANNEL_GROUP_METER;
                            if (!thingHandler.areChannelsCreated()) {
                                thingHandler.updateChannelDefinitions(ShellyChannelDefinitionsDTO
                                        .createEMeterChannels(thingHandler.getThing(), emeter, groupName));
                            }

                            // convert Watt/Hour tok w/h
                            updated |= thingHandler.updateChannel(groupName, CHANNEL_METER_CURRENTWATTS,
                                    toQuantityType(getDouble(emeter.power), DIGITS_WATT, SmartHomeUnits.WATT));
                            updated |= thingHandler.updateChannel(groupName, CHANNEL_METER_TOTALKWH, toQuantityType(
                                    getDouble(emeter.total) / 1000, DIGITS_KWH, SmartHomeUnits.KILOWATT_HOUR));
                            updated |= thingHandler.updateChannel(groupName, CHANNEL_EMETER_TOTALRET, toQuantityType(
                                    getDouble(emeter.totalReturned) / 1000, DIGITS_KWH, SmartHomeUnits.KILOWATT_HOUR));
                            updated |= thingHandler.updateChannel(groupName, CHANNEL_EMETER_REACTWATTS,
                                    toQuantityType(getDouble(emeter.reactive), DIGITS_WATT, SmartHomeUnits.WATT));
                            updated |= thingHandler.updateChannel(groupName, CHANNEL_EMETER_VOLTAGE,
                                    toQuantityType(getDouble(emeter.voltage), DIGITS_VOLT, SmartHomeUnits.VOLT));

                            if (emeter.current != null) {
                                // Shelly
                                updated |= thingHandler.updateChannel(groupName, CHANNEL_EMETER_CURRENT,
                                        toQuantityType(getDouble(emeter.current), DIGITS_VOLT, SmartHomeUnits.AMPERE));
                                updated |= thingHandler.updateChannel(groupName, CHANNEL_EMETER_PFACTOR,
                                        getDecimal(emeter.pf));
                            }

                            accumulatedWatts += getDouble(emeter.power);
                            accumulatedTotal += getDouble(emeter.total) / 1000;
                            accumulatedReturned += getDouble(emeter.totalReturned) / 1000;
                            if (updated) {
                                thingHandler.updateChannel(groupName, CHANNEL_LAST_UPDATE, getTimestamp());
                            }
                        }
                        m++;
                    }
                }
            } else {
                // In Roller Mode we accumulate all meters to a single set of meters
                thingHandler.logger.trace("{}: Updating Meter (accumulated)", thingHandler.thingName);
                double currentWatts = 0.0;
                double totalWatts = 0.0;
                double lastMin1 = 0.0;
                long timestamp = 0l;
                String groupName = CHANNEL_GROUP_METER;
                for (ShellySettingsMeter meter : status.meters) {
                    if (meter.isValid) {
                        currentWatts += getDouble(meter.power);
                        totalWatts += getDouble(meter.total);
                        if (meter.counters != null) {
                            lastMin1 += getDouble(meter.counters[0]);
                        }
                        if (getLong(meter.timestamp) > timestamp) {
                            timestamp = getLong(meter.timestamp); // newest one
                        }
                    }
                }
                // Create channels for 1 Meter
                if (!thingHandler.areChannelsCreated()) {
                    thingHandler.updateChannelDefinitions(ShellyChannelDefinitionsDTO
                            .createMeterChannels(thingHandler.getThing(), status.meters.get(0), groupName));
                }

                updated |= thingHandler.updateChannel(groupName, CHANNEL_METER_LASTMIN1,
                        toQuantityType(getDouble(lastMin1), DIGITS_WATT, SmartHomeUnits.WATT));

                // convert totalWatts into kw/h
                totalWatts = totalWatts / (60.0 * 1000.0);
                updated |= thingHandler.updateChannel(groupName, CHANNEL_METER_CURRENTWATTS,
                        toQuantityType(getDouble(currentWatts), DIGITS_WATT, SmartHomeUnits.WATT));
                updated |= thingHandler.updateChannel(groupName, CHANNEL_METER_TOTALKWH,
                        toQuantityType(getDouble(totalWatts), DIGITS_KWH, SmartHomeUnits.KILOWATT_HOUR));

                if (updated) {
                    thingHandler.updateChannel(groupName, CHANNEL_LAST_UPDATE,
                            getTimestamp(getString(profile.settings.timezone), timestamp));
                }
            }

            if (updated && !profile.isRoller && !profile.isRGBW2) {
                thingHandler.updateChannel(CHANNEL_GROUP_DEV_STATUS, CHANNEL_DEVST_ACCUWATTS,
                        toQuantityType(accumulatedWatts, DIGITS_WATT, SmartHomeUnits.WATT));
                thingHandler.updateChannel(CHANNEL_GROUP_DEV_STATUS, CHANNEL_DEVST_ACCUTOTAL,
                        toQuantityType(accumulatedTotal, DIGITS_KWH, SmartHomeUnits.KILOWATT_HOUR));
                thingHandler.updateChannel(CHANNEL_GROUP_DEV_STATUS, CHANNEL_DEVST_ACCURETURNED,
                        toQuantityType(accumulatedReturned, DIGITS_KWH, SmartHomeUnits.KILOWATT_HOUR));
            }
        }

        return updated;
    }

    /**
     * Update Sensor channel
     *
     * @param th Thing Handler instance
     * @param profile ShellyDeviceProfile
     * @param status Last ShellySettingsStatus
     *
     * @throws IOException
     */
    public static boolean updateSensors(ShellyBaseHandler thingHandler, ShellySettingsStatus status)
            throws ShellyApiException {
        ShellyDeviceProfile profile = thingHandler.getProfile();

        boolean updated = false;
        if (profile.isSensor || profile.hasBattery || profile.isSense) {
            ShellyStatusSensor sdata = thingHandler.api.getSensorStatus();

            if (!thingHandler.areChannelsCreated()) {
                thingHandler.logger.trace("{}: Create missing sensor channel(s)", thingHandler.thingName);
                thingHandler.updateChannelDefinitions(
                        ShellyChannelDefinitionsDTO.createSensorChannels(thingHandler.getThing(), profile, sdata));
            }

            updated |= thingHandler.updateWakeupReason(sdata.actReasons);

            if ((sdata.contact != null) && sdata.contact.isValid) {
                // Shelly DW: “sensor”:{“state”:“open”, “is_valid”:true},
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_CONTACT,
                        getString(sdata.contact.state).equalsIgnoreCase(SHELLY_API_DWSTATE_OPEN) ? OpenClosedType.OPEN
                                : OpenClosedType.CLOSED);
                boolean changed = thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_ERROR,
                        getStringType(sdata.sensorError));
                if (changed) {
                    thingHandler.postEvent(sdata.sensorError, true);
                }
                updated |= changed;
            }
            if ((sdata.tmp != null) && getBool(sdata.tmp.isValid)) {
                thingHandler.logger.trace("{}: Updating temperature", thingHandler.thingName);
                Double temp = getString(sdata.tmp.units).toUpperCase().equals(SHELLY_TEMP_CELSIUS)
                        ? getDouble(sdata.tmp.tC)
                        : getDouble(sdata.tmp.tF);
                if (getString(sdata.tmp.units).toUpperCase().equals(SHELLY_TEMP_FAHRENHEIT)) {
                    // convert Fahrenheit to Celsius
                    temp = ImperialUnits.FAHRENHEIT.getConverterTo(SIUnits.CELSIUS).convert(temp).doubleValue();
                }
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_TEMP,
                        toQuantityType(temp.doubleValue(), DIGITS_TEMP, SIUnits.CELSIUS));
            }
            if (sdata.hum != null) {
                thingHandler.logger.trace("{}: Updating humidity", thingHandler.thingName);
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_HUM,
                        toQuantityType(getDouble(sdata.hum.value), DIGITS_PERCENT, SmartHomeUnits.PERCENT));
            }
            if ((sdata.lux != null) && getBool(sdata.lux.isValid)) {
                // “lux”:{“value”:30, “illumination”: “dark”, “is_valid”:true},
                thingHandler.logger.trace("{}: Updating lux", thingHandler.thingName);
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_LUX,
                        toQuantityType(getDouble(sdata.lux.value), DIGITS_LUX, SmartHomeUnits.LUX));
                if (sdata.lux.illumination != null) {
                    updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_ILLUM,
                            getStringType(sdata.lux.illumination));
                }
            }
            if (sdata.accel != null) {
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_TILT, toQuantityType(
                        getDouble(sdata.accel.tilt.doubleValue()), DIGITS_NONE, SmartHomeUnits.DEGREE_ANGLE));
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_VIBRATION,
                        getInteger(sdata.accel.vibration) == 1 ? OnOffType.ON : OnOffType.OFF);
            }
            if (sdata.flood != null) {
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_FLOOD,
                        getOnOff(sdata.flood));
            }
            if (sdata.smoke != null) {
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_SMOKE,
                        getOnOff(sdata.smoke));
            }
            if (sdata.gasSensor != null) {
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_DEV_STATUS, CHANNEL_DEVST_SELFTTEST,
                        getStringType(sdata.gasSensor.selfTestState));
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_ALARM_STATE,
                        getStringType(sdata.gasSensor.alarmState));
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_SSTATE,
                        getStringType(sdata.gasSensor.sensorState));
            }
            if ((sdata.concentration != null) && sdata.concentration.isValid) {
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_PPM,
                        getDecimal(sdata.concentration.ppm));
            }

            if (sdata.bat != null) { // no update for Sense
                thingHandler.logger.trace("{}: Updating battery", thingHandler.thingName);
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_BATTERY, CHANNEL_SENSOR_BAT_LEVEL,
                        toQuantityType(getDouble(sdata.bat.value), DIGITS_PERCENT, SmartHomeUnits.PERCENT));
                boolean changed = thingHandler.updateChannel(CHANNEL_GROUP_BATTERY, CHANNEL_SENSOR_BAT_LOW,
                        getDouble(sdata.bat.value) < thingHandler.config.lowBattery ? OnOffType.ON : OnOffType.OFF);
                updated |= changed;
                if (changed && getDouble(sdata.bat.value) < thingHandler.config.lowBattery) {
                    thingHandler.postEvent(ALARM_TYPE_LOW_BATTERY, false);
                }
            }
            if (sdata.motion != null) {
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_MOTION,
                        getOnOff(sdata.motion));
            }
            if (sdata.charger != null) {
                updated |= thingHandler.updateChannel(CHANNEL_GROUP_DEV_STATUS, CHANNEL_DEVST_CHARGER,
                        getOnOff(sdata.charger));
            }

            updated |= thingHandler.updateInputs(status);

            if (updated) {
                thingHandler.updateChannel(profile.getControlGroup(0), CHANNEL_LAST_UPDATE, getTimestamp());
            }
        }
        return updated;
    }
}
