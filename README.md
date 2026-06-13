# WyckFlow.com Free Studies for MotiveWave

A clean-room open-source implementation of Cumulative Volume Delta (CVD) and Bid/Ask Volume studies compatible with MotiveWave Community and Standard editions. 

These studies provide the core order-flow analysis layer (volume classification based on bid/ask aggressor ticks) which is typically restricted to premium editions of the platform.

## Features

### 1. Cumulative Volume Delta (CVD)
*   **OHLC Candle Rendering:** Plots cumulative delta as candlestick bars (open, high, low, close of running delta within each bar).
*   **Flexible Reset Options:** Resets to zero on session (RTH/ETH), weekly, monthly boundaries, or custom time schedules.
*   **Divergence Detection:** Built-in engine to identify price/CVD swing divergences (exhaustion/absorption).
*   **Immediate Divergence:** Flags short-term effort-vs-result imbalances (large delta with minimal price movement) on the candle they occur.
*   **Performance Optimized:** Throttled rendering updates to maintain UI responsiveness on volatile markets.

### 2. Bid/Ask Volume
*   **Split Paths:** Plots ask-aggressor volume (buying pressure) and bid-aggressor volume (selling pressure) as two independent paths in a subplot.
*   **Intra-bar Analysis:** Visualizes crossovers and dominance shifts between buying and selling pressure.
*   **RTH Filter:** Option to restrict volume accumulation to regular trading hours.

## Requirements

*   **MotiveWave:** Community Edition or higher (macOS or Windows).
*   **Market Data:** A Level 2 (DOM) or bid/ask tick data feed (required to classify trades).
*   **Build Environment:** Java Development Kit (JDK) 17+ and Apache Maven.

## Build Instructions

1.  Verify that MotiveWave is installed at the default location, or adjust the `<mwave.sdk>` path property in `pom.xml` to point to your `mwave_sdk.jar` (e.g. `/Applications/MotiveWave.app/Contents/Java/mwave_sdk.jar` on macOS).
2.  Build the project using Maven:
    ```bash
    mvn clean package
    ```
3.  The compiled extension will be generated as `target/WyckFlowFreeStudies.jar`.

## Installation

1.  Copy the compiled `WyckFlowFreeStudies.jar` to your MotiveWave extensions directory:
    *   **macOS / Windows:** Drop the jar into `~/MotiveWave Extensions/`
2.  Restart MotiveWave.
3.  The studies will be available under the **WyckFlow.com** menu group (or via the Study search dialog).

## License

This project is licensed under the MIT License.
