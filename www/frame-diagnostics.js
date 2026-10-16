var exec = require('cordova/exec');
var channel = require('cordova/channel');

var state = {
    intervalSeq: 0,
    intervalTs: 0,
    rafSeq: 0,
    rafTs: 0
};

function tick() {
    var now = Date.now();
    state.intervalSeq += 1;
    state.intervalTs = now;
}

function onFrame() {
    state.rafSeq += 1;
    state.rafTs = Date.now();
    window.requestAnimationFrame(onFrame);
}

function start(success, error) {
    if (!state.started) {
        state.started = true;
        window.__cngFrameDiag = state;
        tick();
        window.requestAnimationFrame(onFrame);
        window.setInterval(tick, 1000);
    }
    exec(success || function () {}, error || function () {}, 'FrameDiagnostics', 'start', []);
}

// Runs again after a page reload, because Cordova loads this module with the new document.
channel.onDeviceReady.subscribe(function () {
    start();
});

module.exports = {
    start: start
};
