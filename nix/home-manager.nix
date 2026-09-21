{ config, lib, pkgs, ... }:

with lib;

let
  cfg = config.services.watch-proximity;

  extensionUuid = "watch-proximity@mambuco";

  extensionPkg = pkgs.stdenv.mkDerivation {
    pname = "gnome-shell-extension-watch-proximity";
    version = "1.0.0";
    src = ../extension;

    installPhase = ''
      runHook preInstall
      extdir=$out/share/gnome-shell/extensions/${extensionUuid}
      mkdir -p "$extdir"
      cp -r * "$extdir/"
      runHook postInstall
    '';

    passthru.extensionUuid = extensionUuid;
  };

  daemonPkg = pkgs.writers.writePython3Bin "watch-proximity" {
    libraries = [ ];
    flakeIgnore = [ "E501" "E265" "F541" "E226" ];
    makeWrapperArgs = [
      "--prefix"
      "PATH"
      ":"
      (lib.makeBinPath [
        pkgs.bluez
        pkgs.systemd
        pkgs.glib
        pkgs.dbus
        pkgs.pipewire
        pkgs.wireplumber
        pkgs.android-tools
        pkgs.sound-theme-freedesktop
      ])
    ];
  } (builtins.readFile ../daemon/watch-proximity.py);
in
{
  options.services.watch-proximity = {
    enable = mkEnableOption "watch-proximity Bluetooth Proximity Guard & Locker";

    package = mkOption {
      type = types.package;
      default = daemonPkg;
      description = "The watch-proximity daemon package.";
    };

    extensionPackage = mkOption {
      type = types.package;
      default = extensionPkg;
      description = "The GNOME Shell extension package.";
    };

    deviceMac = mkOption {
      type = types.str;
      default = "64:9D:38:1A:4F:5A";
      description = "Bluetooth MAC address of the smartwatch.";
    };

    deviceName = mkOption {
      type = types.str;
      default = "Pixel Watch 4";
      description = "Friendly display name of the smartwatch.";
    };

    deskThreshold = mkOption {
      type = types.int;
      default = 1;
      description = "RSSI threshold in dB for Desk zone (> deskThreshold).";
    };

    warningThreshold = mkOption {
      type = types.int;
      default = -2;
      description = "RSSI threshold in dB for Warning zone (0 to warningThreshold).";
    };

    awayThreshold = mkOption {
      type = types.int;
      default = -3;
      description = "RSSI threshold in dB for Away zone (<= awayThreshold).";
    };

    tolerance = mkOption {
      type = types.int;
      default = 5;
      description = "Consecutive away strikes before screen lock.";
    };

    pollInterval = mkOption {
      type = types.float;
      default = 2.0;
      description = "Polling interval in seconds while unlocked.";
    };

    lockedInterval = mkOption {
      type = types.float;
      default = 2.5;
      description = "Polling interval in seconds while locked.";
    };

    autoWake = mkOption {
      type = types.bool;
      default = true;
      description = "Automatically wake display on return to Desk zone.";
    };

    pauseMedia = mkOption {
      type = types.bool;
      default = true;
      description = "Pause MPRIS media players on lock.";
    };

    resumeMedia = mkOption {
      type = types.bool;
      default = true;
      description = "Resume MPRIS media players on auto-wake.";
    };

    antiTheft = mkOption {
      type = types.bool;
      default = true;
      description = "Sound siren alarm if AC power disconnected while away.";
    };
  };

  config = mkIf cfg.enable {
    home.packages = [
      cfg.package
      cfg.extensionPackage
    ];

    xdg.configFile."watch-proximity.conf".text = ''
      # Configuration for watch-proximity
      DEVICE_MAC="${cfg.deviceMac}"
      DEVICE_NAME="${cfg.deviceName}"
      DESK_THRESHOLD=${toString cfg.deskThreshold}
      WARNING_THRESHOLD=${toString cfg.warningThreshold}
      AWAY_THRESHOLD=${toString cfg.awayThreshold}
      TOLERANCE_COUNT=${toString cfg.tolerance}
      POLL_INTERVAL=${toString cfg.pollInterval}
      LOCKED_INTERVAL=${toString cfg.lockedInterval}
      AUTO_WAKE=${lib.boolToString cfg.autoWake}
      PAUSE_MEDIA=${lib.boolToString cfg.pauseMedia}
      RESUME_MEDIA=${lib.boolToString cfg.resumeMedia}
      ANTI_THEFT=${lib.boolToString cfg.antiTheft}
      ALARM_SOUND_PATH="${pkgs.sound-theme-freedesktop}/share/sounds/freedesktop/stereo/alarm-clock-elapsed.oga"
    '';

    systemd.user.services.watch-proximity = {
      Unit = {
        Description = "watch-proximity Guard Daemon (${cfg.deviceName})";
        Documentation = "file://${cfg.package}/bin/watch-proximity";
        After = [ "bluetooth.target" "graphical-session.target" ];
        PartOf = [ "graphical-session.target" ];
      };

      Service = {
        Type = "simple";
        ExecStart = "${cfg.package}/bin/watch-proximity";
        Restart = "always";
        RestartSec = "5s";
        Environment = [
          "PATH=${lib.makeBinPath [
            pkgs.bluez
            pkgs.systemd
            pkgs.glib
            pkgs.dbus
            pkgs.pipewire
            pkgs.wireplumber
            pkgs.android-tools
            pkgs.sound-theme-freedesktop
          ]}:/run/current-system/sw/bin"
        ];
      };

      Install = {
        WantedBy = [ "default.target" ];
      };
    };
  };
}
