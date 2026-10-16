var exec = require('cordova/exec');

var frameDiagnostics = {
    start: function (success, error) {
        exec(success || function () {}, error || function () {}, 'FrameDiagnostics', 'start', []);
    }
};

module.exports = frameDiagnostics;
