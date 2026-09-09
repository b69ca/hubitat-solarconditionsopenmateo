# Solar Conditions Sensor (Open-Meteo)

Creates a Hubitat illuminance sensor and reports solar radiation, cloud cover, and daylight state from Open-Meteo.

## What it does

- Estimates `illuminance` in lux from shortwave solar radiation.
- Reports `solarRadiation`, `directRadiation`, `diffuseRadiation`, and `directNormalIrradiance` in W/m².
- Reports `cloudCover` as a percentage and `isDay` as `day` or `night`.
- Uses the closest hourly radiation entry, preferring instantaneous shortwave radiation when available.
- Refreshes every 30 minutes by default.

## Installation

1. Open **Drivers Code** in the Hubitat hub interface and create a new driver.
2. Paste [SolarConditionsOpenMateo.groovy](SolarConditionsOpenMateo.groovy) (or import the [raw source](https://raw.githubusercontent.com/b69ca/hubitat-solarconditionsopenmateo/main/SolarConditionsOpenMateo.groovy)) and save it.
3. Under **Devices**, add a virtual device and select **Solar Conditions Sensor (Open-Meteo)** as its driver type.
4. Set the preferences below and save them.
5. Run **Refresh** and inspect the device’s current states.

Configure the hub’s latitude and longitude before use. The hub needs internet access to Open-Meteo; no separate weather hardware or other driver from this collection is required.

## Preferences

| Setting | Default | Purpose |
| --- | --- | --- |
| Auto-refresh interval (minutes) | 30 | How often the driver refreshes solar data. Range: 1..1440. |
| Lux conversion factor | 120 | Estimated lux per W/m^2 of shortwave radiation. 120 is a common outdoor approximation. Range: 1..200. |
| Minimum illuminance change to report | 100 | Suppresses illuminance events until the change is at least this many lux. Use 0 to report every refresh. Range: 0..100000. |
| Enable debug logging | false | Log API requests and update decisions. |

## Usage and behavior

Use `illuminance` to drive lighting rules or `cloudCover` to display outdoor conditions. Lux is estimated as radiation × conversion factor, rounded to a whole number; the default factor is 120. It is not a measurement from a physical light sensor at your home.

## Device interface

Capabilities: `IlluminanceMeasurement`, `Sensor`, `Refresh`.

Standard readings/state: `illuminance`.

Additional attributes: `solarRadiation`, `directRadiation`, `diffuseRadiation`, `directNormalIrradiance`, `cloudCover`, `isDay`, `lastUpdated`.

## Troubleshooting

- Check the configured coordinates and the hub’s internet connection if values are missing.
- Enable debug logging and inspect Hubitat Logs for API errors or missing fields.
- A successful refresh may not emit a new primary reading when its change is below the configured threshold.

Data is supplied by [Open-Meteo](https://open-meteo.com/). This project uses the [Forecast API](https://open-meteo.com/en/docs). The source uses public endpoints without an API key. Refer to the provider for data coverage and applicable usage terms.

## Updating

Replace the saved driver code in Hubitat with the latest source and save it. Keep existing devices; there is no need to recreate them. Save preferences and refresh as applicable.

## License

[MIT License](LICENSE). Author: Jon Wallace.
