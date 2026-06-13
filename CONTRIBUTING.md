# Contributing to WyckFlow Free Studies

Thank you for your interest in contributing to the WyckFlow Free Studies! 

## Code of Conduct

Please be respectful and professional in all communications, issue descriptions, and pull requests.

## How to Contribute

1. **Report Bugs:** If you find a bug, please open an issue describing the bug, how to reproduce it, and your environment (MotiveWave version, OS, market data feed).
2. **Feature Requests:** Feel free to suggest enhancements or new features by opening an issue.
3. **Pull Requests:**
   * Fork the repository and create your branch from `main`.
   * Ensure the project compiles cleanly using `mvn clean package`.
   * Keep your changes focused and minimal.
   * Provide a clear description of the problem solved or the feature added in your PR.

## Development Setup

To build the project locally, you need the MotiveWave SDK. Since it is proprietary, the `pom.xml` is configured to resolve it from your local MotiveWave installation directory:

*   **macOS default:** `/Applications/MotiveWave.app/Contents/Java/mwave_sdk.jar`
*   **Windows default:** usually located in the installation folder (e.g. `C:\Program Files\MotiveWave\mwave_sdk.jar`).

If your installation is at a different location, override the path during compilation:
```bash
mvn clean package -Dmwave.sdk=/path/to/your/mwave_sdk.jar
```

## Licensing

By contributing, you agree that your contributions will be licensed under the project's [MIT License](LICENSE).
