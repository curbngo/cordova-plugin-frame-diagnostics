# Cordova Frame Diagnostics Plugin

Read-only samples of a Cordova Android WebView. The same plugin is used by the kiosk and the POS. It records whether JavaScript timers and animation frames are advancing, and copies a small corner of the current frame. It does not reload, hide, pause, or replace the WebView. A page reload keeps the sampler running.

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
