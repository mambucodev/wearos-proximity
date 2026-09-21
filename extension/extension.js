import GObject from 'gi://GObject';
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import * as QuickSettings from 'resource:///org/gnome/shell/ui/quickSettings.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import { Extension } from 'resource:///org/gnome/shell/extensions/extension.js';

const SERVICE_NAME = 'watch-proximity.service';
const LEGACY_SERVICE_NAME = 'bt-proximity-lock.service';
const RUNTIME_DIR = GLib.get_user_runtime_dir();
const STATUS_FILE_PATH = GLib.build_filenamev([RUNTIME_DIR, 'bt-proximity-status.json']);
const SNOOZE_FILE_PATH = GLib.build_filenamev([RUNTIME_DIR, 'bt-proximity-snooze']);
const CONTROL_FILE_PATH = GLib.build_filenamev([RUNTIME_DIR, 'bt-proximity-control.json']);

const ProximityToggle = GObject.registerClass(
class ProximityToggle extends QuickSettings.QuickMenuToggle {
    _init() {
        super._init({
            title: 'Watch Proximity',
            subtitle: 'Checking...',
            iconName: 'system-lock-screen-symbolic',
            toggleMode: true,
            menuButtonAccessibleName: 'Open Watch Proximity controls',
        });

        this._indicatorRef = null;

        this.menu.setHeader('system-lock-screen-symbolic', 'Watch Proximity', 'Pixel Watch 4');

        // Status meter items
        this._meterItem = new PopupMenu.PopupMenuItem('Signal: Initializing...', {
            reactive: false,
            can_focus: false,
        });
        this.menu.addMenuItem(this._meterItem);

        this._distItem = new PopupMenu.PopupMenuItem('Zone: Checking...', {
            reactive: false,
            can_focus: false,
        });
        this.menu.addMenuItem(this._distItem);

        this._watchSecurityItem = new PopupMenu.PopupMenuItem('Watch: Checking...', {
            reactive: false,
            can_focus: false,
        });
        this._watchSecurityItem.visible = false;
        this.menu.addMenuItem(this._watchSecurityItem);

        this.menu.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());

        // Snooze controls
        const snoozeSection = new PopupMenu.PopupMenuSection();
        const snooze15 = new PopupMenu.PopupMenuItem('Snooze 15 minutes');
        snooze15.connect('activate', () => this._snooze(15 * 60));
        snoozeSection.addMenuItem(snooze15);

        const snooze60 = new PopupMenu.PopupMenuItem('Snooze 1 hour');
        snooze60.connect('activate', () => this._snooze(60 * 60));
        snoozeSection.addMenuItem(snooze60);

        const resumeNow = new PopupMenu.PopupMenuItem('Resume Now');
        resumeNow.connect('activate', () => this._clearSnooze());
        snoozeSection.addMenuItem(resumeNow);
        this.menu.addMenuItem(snoozeSection);

        this.menu.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());

        // Feature switches
        this._autoWakeSwitch = new PopupMenu.PopupSwitchMenuItem('Auto-Wake Display', true);
        this._autoWakeSwitch.connect('toggled', (item, state) => this._setControl('AUTO_WAKE', state));
        this.menu.addMenuItem(this._autoWakeSwitch);

        this._antiTheftSwitch = new PopupMenu.PopupSwitchMenuItem('Anti-Theft Charger Alarm', true);
        this._antiTheftSwitch.connect('toggled', (item, state) => this._setControl('ANTI_THEFT', state));
        this.menu.addMenuItem(this._antiTheftSwitch);

        // Click handler for primary toggle
        this.connect('clicked', () => this._onToggle());

        // Initial sync and non-blocking polling timer (every 2 seconds)
        this._syncState();
        this._pollTimer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 2, () => {
            this._syncState();
            return GLib.SOURCE_CONTINUE;
        });
    }

    _getActiveServiceName() {
        try {
            const [res1, out1] = GLib.spawn_command_line_sync('systemctl --user is-active ' + SERVICE_NAME);
            if (out1 && new TextDecoder().decode(out1).trim() === 'active')
                return SERVICE_NAME;
            const [res2, out2] = GLib.spawn_command_line_sync('systemctl --user is-active ' + LEGACY_SERVICE_NAME);
            if (out2 && new TextDecoder().decode(out2).trim() === 'active')
                return LEGACY_SERVICE_NAME;
        } catch (e) {}
        return SERVICE_NAME;
    }

    _isServiceActive() {
        try {
            const svc = this._getActiveServiceName();
            const [res, stdout] = GLib.spawn_command_line_sync('systemctl --user is-active ' + svc);
            return stdout ? new TextDecoder().decode(stdout).trim() === 'active' : false;
        } catch (e) {
            return false;
        }
    }

    _readStatus() {
        try {
            const file = Gio.File.new_for_path(STATUS_FILE_PATH);
            if (!file.query_exists(null))
                return null;
            const [ok, contents] = file.load_contents(null);
            if (!ok)
                return null;
            return JSON.parse(new TextDecoder().decode(contents));
        } catch (e) {
            return null;
        }
    }

    _snooze(seconds) {
        try {
            const until = (Date.now() / 1000) + seconds;
            GLib.file_set_contents(SNOOZE_FILE_PATH, until.toString());
            this._syncState();
        } catch (e) {
            console.error('Failed to set snooze:', e);
        }
    }

    _clearSnooze() {
        try {
            const file = Gio.File.new_for_path(SNOOZE_FILE_PATH);
            if (file.query_exists(null))
                file.delete(null);
            this._syncState();
        } catch (e) {
            // ignore
        }
    }

    _setControl(key, value) {
        try {
            let data = {};
            const file = Gio.File.new_for_path(CONTROL_FILE_PATH);
            if (file.query_exists(null)) {
                const [ok, contents] = file.load_contents(null);
                if (ok) {
                    try {
                        data = JSON.parse(new TextDecoder().decode(contents));
                    } catch (err) {
                        data = {};
                    }
                }
            }
            data[key] = value;
            GLib.file_set_contents(CONTROL_FILE_PATH, JSON.stringify(data));
        } catch (e) {
            console.error('Failed to set control key:', e);
        }
    }

    _syncState() {
        const active = this._isServiceActive();
        this.checked = active;

        if (!active) {
            this.subtitle = 'Paused';
            this.iconName = 'system-lock-screen-symbolic';
            this._meterItem.label.text = 'Signal: Service Paused';
            this._distItem.label.text = 'Status: Inactive';
            this._watchSecurityItem.visible = false;
            if (this._indicatorRef)
                this._indicatorRef.visible = false;
            return;
        }

        if (this._indicatorRef)
            this._indicatorRef.visible = true;

        const status = this._readStatus();
        if (!status) {
            this.subtitle = 'Active';
            this.iconName = 'system-lock-screen-symbolic';
            return;
        }

        const state = status.state || 'UNKNOWN';
        const rssi = status.rssi;
        const dist = status.distance_est || '';

        // Subtitle and icon updates
        if (state === 'SNOOZED') {
            const mins = Math.max(1, Math.round((status.snooze_remaining || 0) / 60));
            this.subtitle = `Snoozed (${mins}m left)`;
            this.iconName = 'media-playback-pause-symbolic';
            if (this._indicatorRef) this._indicatorRef.icon_name = 'media-playback-pause-symbolic';
        } else if (state === 'ALARM' || status.alarm_active) {
            this.subtitle = 'SIREN ACTIVE!';
            this.iconName = 'dialog-warning-symbolic';
            if (this._indicatorRef) this._indicatorRef.icon_name = 'dialog-warning-symbolic';
        } else if (state === 'DESK') {
            const prefix = (rssi !== null && rssi >= 0) ? '+' : '';
            this.subtitle = `Desk (${rssi !== null ? prefix + rssi + ' dB' : ''})`;
            this.iconName = 'system-lock-screen-symbolic';
            if (this._indicatorRef) this._indicatorRef.icon_name = 'system-lock-screen-symbolic';
        } else if (state === 'NORMAL' || state === 'NEAR') {
            const prefix = (rssi !== null && rssi >= 0) ? '+' : '';
            this.subtitle = `Normal (${rssi !== null ? prefix + rssi + ' dB' : ''})`;
            this.iconName = 'system-lock-screen-symbolic';
            if (this._indicatorRef) this._indicatorRef.icon_name = 'system-lock-screen-symbolic';
        } else if (state === 'WARNING') {
            const prefix = (rssi !== null && rssi >= 0) ? '+' : '';
            this.subtitle = `Warning (${rssi !== null ? prefix + rssi + ' dB' : ''})`;
            this.iconName = 'dialog-warning-symbolic';
            if (this._indicatorRef) this._indicatorRef.icon_name = 'dialog-warning-symbolic';
        } else if (state === 'AWAY') {
            this.subtitle = `Away (${status.strikes}/${status.max_strikes})`;
            this.iconName = 'system-lock-screen-symbolic';
            if (this._indicatorRef) this._indicatorRef.icon_name = 'system-lock-screen-symbolic';
        } else if (state === 'LOCKED') {
            this.subtitle = 'Locked';
            this.iconName = 'system-lock-screen-symbolic';
            if (this._indicatorRef) this._indicatorRef.icon_name = 'system-lock-screen-symbolic';
        } else {
            this.subtitle = state;
            this.iconName = 'system-lock-screen-symbolic';
        }

        // Submenu items update
        if (rssi !== null && rssi !== undefined) {
            const bars = Math.max(0, Math.min(10, Math.floor((rssi + 20) / 2)));
            const meter = '█'.repeat(bars) + '░'.repeat(10 - bars);
            this._meterItem.label.text = `Signal: [${meter}] ${rssi >= 0 ? '+' : ''}${rssi} dB`;
        } else {
            this._meterItem.label.text = 'Signal: Disconnected / Out of range';
        }

        this._distItem.label.text = `Zone: ${state} (${dist})`;

        // Watch Security Telemetry readout (unlocked / on-body)
        if (status.watch_unlocked !== null && status.watch_unlocked !== undefined) {
            const lockStr = status.watch_unlocked ? 'Unlocked' : 'Locked';
            const wristStr = status.watch_on_body ? 'On-Wrist' : 'Off-Wrist';
            this._watchSecurityItem.label.text = `Watch: ${lockStr} • ${wristStr}`;
            this._watchSecurityItem.visible = true;
        } else {
            this._watchSecurityItem.visible = false;
        }

        // Sync switch states from status if not matching
        if (status.auto_wake !== undefined && this._autoWakeSwitch.state !== status.auto_wake)
            this._autoWakeSwitch.setToggleState(status.auto_wake);
    }

    _onToggle() {
        const svc = this._getActiveServiceName();
        const targetCmd = this.checked
            ? 'systemctl --user start ' + svc
            : 'systemctl --user stop ' + svc;
        GLib.spawn_command_line_async(targetCmd);
        this.subtitle = this.checked ? 'Active' : 'Paused';
    }

    destroy() {
        if (this._pollTimer) {
            GLib.source_remove(this._pollTimer);
            this._pollTimer = null;
        }
        if (super.destroy)
            super.destroy();
    }
});

const ProximityIndicator = GObject.registerClass(
class ProximityIndicator extends QuickSettings.SystemIndicator {
    _init(toggle) {
        super._init();
        this._indicator = this._addIndicator();
        this._indicator.icon_name = 'system-lock-screen-symbolic';
        this._indicator.visible = true;

        toggle._indicatorRef = this._indicator;
        this.quickSettingsItems.push(toggle);
    }
});

export default class WatchProximityExtension extends Extension {
    enable() {
        this._toggle = new ProximityToggle();
        this._indicator = new ProximityIndicator(this._toggle);
        Main.panel.statusArea.quickSettings.addExternalIndicator(this._indicator);
    }

    disable() {
        if (this._indicator) {
            this._indicator.quickSettingsItems.forEach(item => item.destroy());
            this._indicator.destroy();
            this._indicator = null;
        }
        this._toggle = null;
    }
}
