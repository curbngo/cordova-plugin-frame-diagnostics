# Cordova Frame Diagnostics Plugin

Read-only samples of the kiosk WebView. The plugin records whether JavaScript timers and animation frames are advancing, and copies a small corner of the current frame. It does not reload, hide, pause, or replace the WebView.

## Installation

```bash
cordova plugin add https://github.com/curbngo/cordova-plugin-frame-diagnostics
```

## Supported Platforms

- Android

## Usage

```javascript
cordova.plugins.frameDiagnostics.start();
```

Samples are written to logcat under the tag `FrameDiag`.
