/*
 *  Solar Conditions Sensor (Open-Meteo)
 *
 *  Hubitat driver that reports solar radiation, cloud cover, daylight state,
 *  and estimated illuminance from Open-Meteo forecast data.
 *
 *  Author: Jon Wallace
 *  Copyright 2026 Jon Wallace
 *  License: MIT
 *
 *  Notes:
 *  - Open-Meteo provides solar radiation in W/m^2, not native lux.
 *  - Illuminance is estimated from shortwave radiation using a configurable
 *    lux-per-W/m^2 conversion factor.
 *  - Requires the hub location coordinates to be configured in Hubitat.
 */

metadata {
  definition (
    name: "Solar Conditions Sensor (Open-Meteo)",
    namespace: "jonw",
    author: "Jon Wallace"
  ) {
    capability "IlluminanceMeasurement"
    capability "Sensor"
    capability "Refresh"

    attribute "solarRadiation", "number"
    attribute "directRadiation", "number"
    attribute "diffuseRadiation", "number"
    attribute "directNormalIrradiance", "number"
    attribute "cloudCover", "number"
    attribute "isDay", "string"
    attribute "lastUpdated", "string"
  }

  preferences {
    def lat = location?.latitude
    def lon = location?.longitude
    def hubLocationDescription = (lat != null && lon != null)
      ? "Using hub coordinates: ${lat}, ${lon}"
      : "Hub coordinates are not configured. Set the hub location in Hubitat before this driver can retrieve solar data."

    input name: "hubLocationStatus", type: "paragraph", title: hubLocationDescription, displayDuringSetup: true
    input name: "updateFrequency", type: "number", title: "Auto-refresh interval (minutes)", description: "How often the driver refreshes solar data.", defaultValue: 30, range: "1..1440"
    input name: "luxConversionFactor", type: "number", title: "Lux conversion factor", description: "Estimated lux per W/m^2 of shortwave radiation. 120 is a common outdoor approximation.", defaultValue: 120, range: "1..200"
    input name: "minIlluminanceChange", type: "number", title: "Minimum illuminance change to report", description: "Suppresses illuminance events until the change is at least this many lux. Use 0 to report every refresh.", defaultValue: 100, range: "0..100000"
    input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Log API requests and update decisions.", defaultValue: false
  }
}

// ===== Lifecycle =====

def installed() {
  log.info "Installed Open-Meteo Solar Conditions Sensor"
  initialize()
}

def updated() {
  log.info "Updated settings"
  initialize()
}

def initialize() {
  unschedule()
  scheduleAutoRefresh()
  refreshSolarData()
}

// ===== Commands =====

def refresh() {
  refreshSolarData()
}

def scheduleAutoRefresh() {
  def minutes = settings?.updateFrequency != null ? settings.updateFrequency as Integer : 30
  minutes = Math.max(1, Math.min(1440, minutes))

  if (logEnable) log.debug "Scheduling auto-refresh every ${minutes} minutes"

  if (minutes == 60) {
    runEvery1Hour(refreshSolarData)
  } else if (minutes > 60) {
    runIn(minutes * 60, refreshSolarData)
  } else {
    schedule("0 */${minutes} * ? * *", refreshSolarData)
  }
}

def refreshSolarData() {
  def coords = getHubCoordinates()
  if (!coords) {
    log.error "Hub coordinates are not configured. Set the hub location in Hubitat before retrieving solar data."
    scheduleNextRefresh()
    return
  }

  def lat = coords.lat
  def lon = coords.lon
  def currentFields = "cloud_cover,is_day"
  def hourlyFields = "shortwave_radiation,shortwave_radiation_instant,direct_radiation,diffuse_radiation,direct_normal_irradiance"
  def url = "https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current=${currentFields}&hourly=${hourlyFields}&past_hours=1&forecast_hours=2&timezone=auto"

  if (logEnable) log.debug "Requesting solar data from: ${url}"

  try {
    httpGet([uri: url, contentType: "application/json", timeout: 30]) { resp ->
      if (logEnable) log.debug "Open-Meteo solar response status: ${resp.status}"

      if (resp.status == 200) {
        def data = resp.data
        def solarRadiation = getClosestHourlyValue(data, "shortwave_radiation_instant")

        if (solarRadiation == null) {
          solarRadiation = getClosestHourlyValue(data, "shortwave_radiation")
        }
        def directRadiation = getClosestHourlyValue(data, "direct_radiation")
        def diffuseRadiation = getClosestHourlyValue(data, "diffuse_radiation")
        def directNormalIrradiance = getClosestHourlyValue(data, "direct_normal_irradiance")

        if (logEnable) {
          log.debug "Open-Meteo solar values: shortwave_radiation=${solarRadiation}, direct_radiation=${directRadiation}, diffuse_radiation=${diffuseRadiation}, direct_normal_irradiance=${directNormalIrradiance}, cloud_cover=${data?.current?.cloud_cover}, is_day=${data?.current?.is_day}"
        }

        updateIlluminance(solarRadiation)
        sendOptionalNumberEvent("directRadiation", directRadiation, "W/m^2", 1)
        sendOptionalNumberEvent("diffuseRadiation", diffuseRadiation, "W/m^2", 1)
        sendOptionalNumberEvent("directNormalIrradiance", directNormalIrradiance, "W/m^2", 1)
        sendOptionalNumberEvent("cloudCover", data?.current?.cloud_cover, "%", 0)

        if (data?.current?.is_day != null) {
          sendEvent(name: "isDay", value: data.current.is_day == 1 ? "day" : "night")
        } else {
          log.warn "Daylight data missing in Open-Meteo response"
        }

        sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
      } else {
        log.error "Failed to get solar data: ${resp.status}"
      }
    }
  } catch (java.net.SocketTimeoutException e) {
    log.warn "Timed out fetching solar data from Open-Meteo; retrying in 60 seconds."
    runIn(60, refreshSolarData)
  } catch (Exception e) {
    if (e.message?.toLowerCase()?.contains("timed out")) {
      log.warn "Timed out fetching solar data from Open-Meteo; retrying in 60 seconds."
      runIn(60, refreshSolarData)
    } else {
      log.error "Error fetching solar data: ${e.message}"
    }
  }

  scheduleNextRefresh()
}

// ===== Updates =====

def updateIlluminance(solarRadiation) {
  if (solarRadiation == null) {
    log.warn "Solar radiation data missing in Open-Meteo response"
    return
  }

  def radiationValue = (solarRadiation as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
  def conversionFactor = settings?.luxConversionFactor != null ? (settings.luxConversionFactor as BigDecimal) : new BigDecimal("120")
  def illuminanceValue = (radiationValue * conversionFactor).setScale(0, BigDecimal.ROUND_HALF_UP)
  def currentVal = device.currentValue("illuminance")
  def currentIlluminance = currentVal != null ? new BigDecimal(currentVal.toString()) : null
  def threshold = settings?.minIlluminanceChange != null ? (settings.minIlluminanceChange as BigDecimal) : new BigDecimal("100")

  sendEvent(name: "solarRadiation", value: radiationValue, unit: "W/m^2")

  if (currentIlluminance == null || (illuminanceValue - currentIlluminance).abs() >= threshold) {
    sendEvent(name: "illuminance", value: illuminanceValue, unit: "lux")
    if (logEnable) log.debug "Illuminance updated to ${illuminanceValue} lux from ${radiationValue} W/m^2"
  } else if (logEnable) {
    log.debug "Illuminance change (${illuminanceValue} lux) within threshold (${threshold} lux); event not sent."
  }
}

def sendOptionalNumberEvent(name, value, unit, scale) {
  if (value == null) {
    log.warn "${name} data missing in Open-Meteo response"
    return
  }

  def eventValue = (value as BigDecimal).setScale(scale, BigDecimal.ROUND_HALF_UP)
  sendEvent(name: name, value: eventValue, unit: unit)
  if (logEnable) log.debug "${name} updated to ${eventValue} ${unit}"
}

// ===== Helpers =====

def scheduleNextRefresh() {
  def minutes = settings?.updateFrequency != null ? settings.updateFrequency as Integer : 30
  minutes = Math.max(1, Math.min(1440, minutes))

  if (minutes > 60) {
    runIn(minutes * 60, refreshSolarData)
  }
}

def getClosestHourlyValue(data, variableName) {
  def times = data?.hourly?.time
  def values = data?.hourly?.get(variableName)

  if (!times || !values) {
    return null
  }

  def now = new Date()
  def closestValue = null
  def closestDiff = null

  values.eachWithIndex { value, index ->
    if (value != null && index < times.size()) {
      def sampleTime = parseOpenMeteoTime(times[index])
      if (sampleTime != null) {
        def diff = Math.abs(sampleTime.time - now.time)
        if (closestDiff == null || diff < closestDiff) {
          closestDiff = diff
          closestValue = value
        }
      }
    }
  }

  return closestValue
}

def parseOpenMeteoTime(value) {
  try {
    return Date.parse("yyyy-MM-dd'T'HH:mm", value.toString())
  } catch (Exception e) {
    if (logEnable) log.debug "Unable to parse Open-Meteo time '${value}': ${e.message}"
    return null
  }
}

def getHubCoordinates() {
  def lat = location?.latitude
  def lon = location?.longitude
  return (lat != null && lon != null) ? [lat: lat, lon: lon] : null
}
